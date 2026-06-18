package com.stgd.voice.server.component;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.entity.Room;
import com.stgd.voice.entity.User;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Hzzz
 * 统一的连接/用户/房间管理中心，同时支持 Netty TCP 客户端和 WebSocket 浏览器客户端。
 */
@Component
public class ConnectManager {

	/**
	 * 专用线程池：负责所有房间消息/广播的异步推送。
	 * 不阻塞业务线程，避免一条消息推送给 N 个用户时整个系统卡住。
	 *
	 * 关键配置：
	 *   - 有界队列：最大 10000 个待推送任务，超过后触发拒绝策略（防止 OOM）
	 *   - CallerRunsPolicy：队列满时由提交线程自己执行（自然反压，不丢消息）
	 *   - 固定核心线程数：Math.max(2, CPU/2)，IO 密集型不需要太多线程
	 */
	public static final int BROADCAST_THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
	private static final int BROADCAST_QUEUE_CAPACITY = 10000;

	public static final ExecutorService BROADCAST_EXECUTOR = new ThreadPoolExecutor(
			BROADCAST_THREAD_POOL_SIZE,
			BROADCAST_THREAD_POOL_SIZE,
			0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(BROADCAST_QUEUE_CAPACITY),
			new ThreadFactory() {
				private final AtomicInteger counter = new AtomicInteger(0);
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "broadcast-" + counter.incrementAndGet());
					t.setDaemon(true);
					return t;
				}
			},
			new ThreadPoolExecutor.CallerRunsPolicy()
	);

	// ============ Netty TCP 客户端 ============
	private static final ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

	private static final ConcurrentHashMap<String, ChannelId> channelWithStringIdMap = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();

	// ============ WebSocket 浏览器客户端（使用 Netty WebSocket 实现，替换原来的 javax.websocket） ============
	// wsId -> Netty Channel（wsId 即为原来的 httpSessionId，由前端传入或后端生成唯一ID）
	private static final ConcurrentHashMap<String, Channel> wsChannelMap = new ConcurrentHashMap<>();
	// Netty Channel -> wsId（反向映射，用于从 Channel 快速反查 wsId，避免 O(n) 遍历）
	private static final ConcurrentHashMap<Channel, String> channelToWsId = new ConcurrentHashMap<>();
	// wsId -> 昵称（用户在前端自定义设置）
	private static final ConcurrentHashMap<String, String> wsUserMap = new ConcurrentHashMap<>();
	// wsId -> HTTP 登录的真实用户名（用于权限校验，不可伪造，来自 URL query ?loginUser=xxx）
	private static final ConcurrentHashMap<String, String> wsLoginUserMap = new ConcurrentHashMap<>();
	// wsId -> 当前房间ID（用于离开时清理）
	private static final ConcurrentHashMap<String, Integer> wsRoomMap = new ConcurrentHashMap<>();

	// ============ WebSocket 消息推送队列（保证每个 Channel 串行发送，避免并发写入） ============
	// wsId -> 待发送消息队列（每个 Channel 独立一条队列）
	private static final ConcurrentHashMap<String, LinkedBlockingQueue<String>> wsMessageQueues = new ConcurrentHashMap<>();
	// wsId -> 写标志（true = 已有写线程正在消费队列，false = 需要启动新的写线程）
	private static final ConcurrentHashMap<String, AtomicBoolean> wsWritingFlags = new ConcurrentHashMap<>();

	// ============ 房间管理 ============
	private static final ConcurrentHashMap<Integer, Room> roomMap = new ConcurrentHashMap<>();

	@Resource
	private RoomMapper roomMapper;

	@Resource
	private SystemLogPublisher logPublisher;

	public void init(){
		System.out.println("正在初始化房间...");
		//加载所有房间到内存
		List<Room> roomList = roomMapper.selectAll();
		roomList.forEach(room -> room.setUserNum(0));
		roomList.forEach(room -> roomMap.put(room.getId(), room));
		System.out.println("房间初始化完成");
	}

	public void addClient(ChannelHandlerContext ctx){
		channelGroup.add(ctx.channel());
		channelWithStringIdMap.put(ctx.channel().id().asLongText(), ctx.channel().id());
	}

	public void removeClient(ChannelHandlerContext ctx){
		channelGroup.remove(ctx.channel());
		channelWithStringIdMap.remove(ctx.channel().id().asLongText());
		ctx.channel().close();
	}

	public Channel getChannelByChannelId(String channelIdString){
		ChannelId channelId = channelWithStringIdMap.get(channelIdString);
		return channelGroup.find(channelId);
	}

	public Set<ChannelId> getChannelIdSetByChannelIdStringSet(Set<String> ChannelIdStringSet){
		Set<ChannelId> channelIdSet = new HashSet<>();
		for (String thisChannelIdString : ChannelIdStringSet){
			channelIdSet.add(channelWithStringIdMap.get(thisChannelIdString));
		}
		return channelIdSet;
	}

	public void addUser(User user){
		userMap.put(user.getId(), user);
	}

	public void removeUser(ChannelId id){
		userMap.remove(id.asLongText());
	}

	public User findUserByIdString(String channelIdString){
		return userMap.get(channelIdString);
	}

	public void addRoom(Room room){
		if (room == null) return;
		if (room.getUserNum() == null) room.setUserNum(0);
		roomMap.put(room.getId(), room);
		broadcastRoomList();
	}

	public void removeRoom(Integer id) {
		Room room = roomMap.get(id);
		if (room == null) return;
		Set<String> userIds = room.getUserChannelIdSet();
		if (userIds != null) {
			// Netty TCP 客户端推送
			for (String idStr : userIds) {
				ChannelId channelId = channelWithStringIdMap.get(idStr);
				if (channelId != null) {
					Channel ch = channelGroup.find(channelId);
					if (ch != null && ch.isActive()) {
						ch.writeAndFlush("[系统]: 管理员解散了[" + room.getName() + "]\n");
					}
				}
			}
			// Netty WebSocket 推送：异步 + 线程池
			final String roomName = room.getName();
			BROADCAST_EXECUTOR.submit(() -> {
				Map<String, Object> msg = JsonUtil.newMap();
				msg.put("type", "system");
				msg.put("payload", "管理员解散了[" + roomName + "]");
				String wsText = JsonUtil.toJson(msg);
				for (String idStr : userIds) {
					Channel wsCh = wsChannelMap.get(idStr);
					if (wsCh != null && wsCh.isActive()) {
						wsCh.writeAndFlush(new TextWebSocketFrame(wsText));
					}
				}
			});
		}
		if (userIds != null) {
			for (String idStr : userIds) {
				Integer rId = wsRoomMap.get(idStr);
				if (rId != null && rId.equals(id)) {
					wsRoomMap.remove(idStr);
				}
			}
		}
		roomMap.remove(id);
		broadcastRoomList();
		broadcastAllRoomUsers();
	}

	public Room findRoomById(Integer id){
        return roomMap.get(id);
	}

	public List<Room> getAllRoom(){
		return new ArrayList<>(roomMap.values());
	}

	public void publishOne(ChannelHandlerContext ctx, String s){
		ctx.channel().writeAndFlush("[服务器]对你说：" + s + "\n");
	}

	public void publishOne(String targetChannelIdString, String s){
		ChannelId channelId = channelWithStringIdMap.get(targetChannelIdString);
		Channel channel = channelGroup.find(channelId);
		if (channelId == null){
			return;
		}
		channel.writeAndFlush("[服务器]对你说：" + s + "\n");
	}

	public void publishOne(String sourceChannelIdString, String targetChannelIdString, String s){
		ChannelId channelId = channelWithStringIdMap.get(targetChannelIdString);
		Channel channel;
		try {
			channel = channelGroup.find(channelId);
		}catch (NullPointerException e){
			Channel sourceChannel = getChannelByChannelId(sourceChannelIdString);
			if (sourceChannel != null){
				sourceChannel.writeAndFlush("[服务器]对你说：该用户已下线\n");
			}
			return;
		}

		String sourceUserName;
		if (sourceChannelIdString == null){
			sourceUserName = "服务器";
		}else{
			User sourceUser = findUserByIdString(sourceChannelIdString);
			sourceUserName =  sourceUser.getName();
		}

		if (channel != null){
			channel.writeAndFlush("[" + sourceUserName + "]对你说：" + s + "\n");
		}
	}

	public void publishAll(String s){
		channelGroup.writeAndFlush(s + "\n");
	}

	public void publishSet(Set<ChannelId> channelIdSet, String s){
		channelGroup.stream()
				.filter(ch -> {
					ChannelId id = ch.id();
					return id != null && channelIdSet.contains(id);
				})
				.forEach(ch -> ch.writeAndFlush(s + "\n"));
	}

	// ==================== WebSocket 客户端支持（Netty WebSocket） ====================

	/** 注册一个 WebSocket 连接（连接建立时调用） */
	public void addWsChannel(String wsId, Channel wsChannel) {
		if (wsId == null || wsChannel == null) return;
		wsChannelMap.put(wsId, wsChannel);
		channelToWsId.put(wsChannel, wsId);
	}

	/** 移除一个 WebSocket 连接（连接关闭时调用，自动退出所在房间、清除用户信息） */
	public void removeWsChannel(String wsId) {
		if (wsId == null) return;
		Channel oldCh = wsChannelMap.remove(wsId);
		if (oldCh != null) {
			channelToWsId.remove(oldCh);
		}
		String userName = wsUserMap.remove(wsId);
		wsLoginUserMap.remove(wsId);
		Integer roomId = wsRoomMap.remove(wsId);
		if (roomId != null) {
			Room room = roomMap.get(roomId);
			if (room != null) {
				room.removeUser(wsId);
				if (userName != null) {
					publishRoomSystemWs(roomId, userName + " 离开了房间", wsId);
					if (logPublisher != null) {
						logPublisher.publish("leave", userName, room.getName(),
							userName + " 离开房间 [" + room.getName() + "]");
					}
					broadcastUserLeft(roomId, wsId, userName);
				}
			}
		}
		// 系统日志：登出
		if (logPublisher != null && userName != null) {
			logPublisher.publish("logout", userName, null, userName + " 登出系统");
		}
		// 清理该 Channel 的消息队列与写标志
		wsMessageQueues.remove(wsId);
		wsWritingFlags.remove(wsId);
		broadcastAllRoomUsers();
	}

	/** 查找一个已注册的 WebSocket Channel（例如在消息处理中需要写回时调用） */
	public Channel getWsChannel(String wsId) {
		return wsId == null ? null : wsChannelMap.get(wsId);
	}

	/** 查找指定 WebSocket Channel 对应的 wsId（供 handler 内部回查，O(1) 反向映射） */
	public String getWsIdByChannel(Channel ch) {
		if (ch == null) return null;
		return channelToWsId.get(ch);
	}

	// ==================== 统一的 WebSocket 消息推送（保证每个 Channel 串行发送） ====================

	/**
	 * 统一发送 WebSocket 消息入口。
	 * 核心保证：每个 Channel 的消息永远串行发送，不会并发写入。
	 * 原理：
	 *   1. 每个 wsId 有独立的消息队列 wsMessageQueues
	 *   2. 用 wsWritingFlags 的 CAS 控制「该 Channel 是否已经有写线程在消费队列」
	 *   3. 只有当没有写线程在工作时，才启动一个新的写线程到 BROADCAST_EXECUTOR
	 */
	public static void sendToWs(String wsId, String text) {
		if (wsId == null || text == null) return;
		Channel channel = wsChannelMap.get(wsId);
		if (channel == null || !channel.isActive()) return;

		// 1. 获取/创建该 Channel 的消息队列
		LinkedBlockingQueue<String> queue = wsMessageQueues.computeIfAbsent(
			wsId, k -> new LinkedBlockingQueue<>()
		);
		// 2. 获取/创建该 Channel 的写标志
		AtomicBoolean writing = wsWritingFlags.computeIfAbsent(
			wsId, k -> new AtomicBoolean(false)
		);

		// 3. 把消息放入队列
		queue.offer(text);

		// 4. CAS 检查：如果当前没有写线程，就启动一个新的写线程消费队列
		if (writing.compareAndSet(false, true)) {
			BROADCAST_EXECUTOR.submit(() -> drainWsQueue(wsId, channel, queue, writing));
		}
	}

	/**
	 * 写线程：循环从队列取消息，通过 writeAndFlush(TextWebSocketFrame) 发送。
	 * 单线程内调用 writeAndFlush 串行写入，保证无并发冲突。
	 */
	private static void drainWsQueue(String wsId, Channel channel,
									 LinkedBlockingQueue<String> queue, AtomicBoolean writing) {
		try {
			while (true) {
				String msg = queue.poll();
				if (msg == null) {
					// 队列已空，释放写标志
					writing.set(false);
					// 二次检查：如果在释放后又有新消息入队，需要重新启动写线程
					if (!queue.isEmpty() && writing.compareAndSet(false, true)) {
						continue;
					}
					return;
				}
				// 再次确认连接仍然活跃（调用者在处理期间可能断开）
				if (channel == null || !channel.isActive()) {
					// 连接已关闭，清理队列并返回
					queue.clear();
					writing.set(false);
					return;
				}
				try {
					channel.writeAndFlush(new TextWebSocketFrame(msg)).sync();
				} catch (Exception ignored) {
					// 发送失败通常意味着连接已断开，退出循环
					queue.clear();
					writing.set(false);
					return;
				}
			}
		} catch (Exception e) {
			// 兜底：任何意外都释放写标志，避免永久死锁
			writing.set(false);
		}
	}

	/**
	 * 直接向指定 Channel 发送 JSON 文本（当调用者手头只有 Channel 时使用）。
	 * 会先通过反向映射反查 wsId（O(1)），然后走上面的统一发送逻辑，保证串行发送。
	 */
	public static void sendToWs(Channel channel, String text) {
		if (channel == null || text == null) return;
		if (!channel.isActive()) return;
		// 从反向映射 O(1) 反查 wsId
		String wsId = channelToWsId.get(channel);
		if (wsId == null) {
			// 极端情况：尚未在 map 中注册，那就直接发送一次
			channel.writeAndFlush(new TextWebSocketFrame(text));
			return;
		}
		sendToWs(wsId, text);
	}

	/** WebSocket 客户端设置昵称（类似 TCP 客户端 type=1 登录） */
	public String registerWsUser(String wsId, String userName) {
		if (wsId == null || userName == null || userName.trim().isEmpty()) {
			return null;
		}
		String actualName = userName.trim();
		wsUserMap.put(wsId, actualName);
		// 系统日志广播（dashboard 会显示）
		if (logPublisher != null) {
			logPublisher.publish("login", actualName, null, actualName + " 登录系统");
		}
		// 刷新在线用户列表
		broadcastAllRoomUsers();
		return actualName;
	}

	/** 获取 WebSocket 客户端的昵称 */
	public String getWsUserName(String wsId) {
		return wsId == null ? null : wsUserMap.get(wsId);
	}

	/** 设置 WebSocket 客户端对应的登录用户名（连接建立时，由 URL query 的 loginUser 传入） */
	public void setWsLoginUser(String wsId, String loginUserName) {
		if (wsId == null) return;
		if (loginUserName == null || loginUserName.trim().isEmpty()) return;
		wsLoginUserMap.put(wsId, loginUserName.trim());
	}

	/** 获取 WebSocket 客户端对应的登录用户名（用于权限校验） */
	public String getWsLoginUser(String wsId) {
		return wsId == null ? null : wsLoginUserMap.get(wsId);
	}

	/** WebSocket 客户端真正加入房间（会更新房间 userNum，房间内所有成员可见） */
	public Room getWsRoomById(Integer targetRoomId) {
		return roomMap.get(targetRoomId);
	}

	/**
	 * WebSocket 客户端真正加入房间（会更新房间 userNum，房间内所有成员可见）
	 */
	public void joinWsRoom(String wsId, Integer targetRoomId) {
		if (wsId == null || targetRoomId == null) return;
		if (!wsUserMap.containsKey(wsId)) return;
		Room targetRoom = roomMap.get(targetRoomId);
		if (targetRoom == null) return;

		// 如果当前已在某个房间，先离开
		Integer oldRoomId = wsRoomMap.get(wsId);
		if (oldRoomId != null && !oldRoomId.equals(targetRoomId)) {
			Room oldRoom = roomMap.get(oldRoomId);
			if (oldRoom != null) {
				oldRoom.removeUser(wsId);
				String userName = wsUserMap.get(wsId);
				if (userName != null) {
					publishRoomSystemWs(oldRoomId, userName + " 离开了房间", wsId);
				}
			}
		}

		// 加入新房间
		targetRoom.addUser(wsId);
		wsRoomMap.put(wsId, targetRoomId);
		String userName = wsUserMap.get(wsId);
		if (userName != null) {
			publishRoomSystemWs(targetRoomId, userName + " 加入了房间", wsId);
			// 系统日志：加入房间（dashboard 会显示）
			if (logPublisher != null) {
				logPublisher.publish("join", userName, targetRoom.getName(),
					userName + " 加入房间 [" + targetRoom.getName() + "]");
			}
			broadcastUserJoined(targetRoomId, wsId, userName);
		}
		broadcastAllRoomUsers();
	}

	/** WebSocket 客户端离开房间（例如手动切换） */
	public void leaveWsRoom(String wsId) {
		if (wsId == null) return;
		Integer roomId = wsRoomMap.remove(wsId);
		if (roomId != null) {
			Room room = roomMap.get(roomId);
			if (room != null) {
				room.removeUser(wsId);
				String userName = wsUserMap.get(wsId);
				if (userName != null) {
					publishRoomSystemWs(roomId, userName + " 离开了房间", wsId);
					broadcastUserLeft(roomId, wsId, userName);
				}
			}
		}
		broadcastAllRoomUsers();
	}

	/** 获取指定 wsId 所在的房间 ID */
	public Integer getWsRoomId(String wsId) {
		return wsId == null ? null : wsRoomMap.get(wsId);
	}

	// ==================== 统一的房间消息推送（TCP + WebSocket） ====================

	/**
	 * 向房间内所有成员发送一条聊天消息。
	 * - TCP 客户端收到: [userName]: payload
	 * - WebSocket 客户端收到: {"type":"room","userName":"...","payload":"...","roomId":...}
	 */
	public void publishRoomMessage(Integer roomId, String userName, String payload) {
		if (roomId == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;

		String nettyText = "[" + (userName == null ? "未知" : userName) + "]: " + (payload == null ? "" : payload) + "\n";

		for (String idStr : room.getUserChannelIdSet()) {
			// 先尝试作为 Netty channelId 推送
			ChannelId channelId = channelWithStringIdMap.get(idStr);
			if (channelId != null) {
				Channel channel = channelGroup.find(channelId);
				if (channel != null && channel.isActive()) {
					channel.writeAndFlush(nettyText);
                }
			}
		}
	}

	public void publishRoomMessageWs(Integer roomId, String userName, String payload, String wsId) {
		if (roomId == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;

		Map<String, Object> wsJson = JsonUtil.newMap();
		wsJson.put("type", "room");
		wsJson.put("roomId", roomId);
		wsJson.put("wsId", wsId);
		wsJson.put("userName", userName);
		wsJson.put("payload", payload);
		wsJson.put("timestamp", System.currentTimeMillis());
		String wsText = JsonUtil.toJson(wsJson);

		// 通过统一的 sendToWs 推送，保证每个 Channel 串行发送
		for (String idStr : room.getUserChannelIdSet()) {
			if (wsChannelMap.containsKey(idStr)) {
				sendToWs(idStr, wsText);
			}
		}
	}

	public void publishRoomSystemWs(Integer roomId, String text, String wsId) {
		if (roomId == null || text == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;

		Map<String, Object> wsJson = JsonUtil.newMap();
		wsJson.put("type", "system");
		wsJson.put("payload", text);
		wsJson.put("timestamp", System.currentTimeMillis());
		String wsText = JsonUtil.toJson(wsJson);

		for (String idStr : room.getUserChannelIdSet()) {
			if (!wsChannelMap.containsKey(idStr)) continue;
			if (StringUtils.equals(wsId, idStr)) continue;
			sendToWs(idStr, wsText);
		}
	}

	/**
	 * WebRTC 信令转发：将 forwardText 发送到房间内其他成员。
	 * 若 targetUserId 不为空，则只发送到目标用户（点对点）；否则发送到房间内除 sourceWsId 外的所有人。
	 */
	public void publishRoomWebRtcWs(Integer roomId, String sourceWsId, String targetUserId, String forwardText) {
		if (roomId == null || forwardText == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;
		if (sourceWsId == null) return;

		boolean hasTarget = targetUserId != null && !targetUserId.trim().isEmpty();

		for (String idStr : room.getUserChannelIdSet()) {
			if (!wsChannelMap.containsKey(idStr)) continue;
			if (StringUtils.equals(idStr, sourceWsId)) continue;
			if (hasTarget && !StringUtils.equals(idStr, targetUserId)) continue;
			sendToWs(idStr, forwardText);
		}
	}

	// ==================== 房间内用户广播（roomUsers / userJoined / userLeft） ====================

	/**
	 * 向所有已登录的 WebSocket 客户端广播全量 "房间-用户"信息。
	 * 结构：
	 *   {
	 *     type: 'roomUsers',
	 *     rooms: [
	 *       { id, name, userNum, users: [{ userId, userName }, ...] },
	 *       ...
	 *     ]
	 *   }
	 */
	public void broadcastAllRoomUsers() {
		if (wsChannelMap.isEmpty()) return;
		Map<String, Object> root = JsonUtil.newMap();
		root.put("type", "roomUsers");
		List<Map<String, Object>> roomArr = new java.util.ArrayList<>();
		for (Room room : roomMap.values()) {
			if (room == null) continue;
			Map<String, Object> rj = JsonUtil.newMap();
			rj.put("id", room.getId());
			rj.put("name", room.getName());
			if (StringUtils.isNotBlank(room.getPassword())) {
				rj.put("hasPassword", "1");
			} else {
				rj.put("hasPassword", "0");
			}

			rj.put("userNum", room.getUserNum() == null ? 0 : room.getUserNum());
			List<Map<String, Object>> userArr = new java.util.ArrayList<>();
			Set<String> ids = room.getUserChannelIdSet();
			if (ids != null) {
				for (String idStr : ids) {
					String un = wsUserMap.get(idStr);
					if (un == null) continue;
					Map<String, Object> uj = JsonUtil.newMap();
					uj.put("userId", idStr);
					uj.put("userName", un);
					userArr.add(uj);
				}
			}
			rj.put("users", userArr);
			roomArr.add(rj);
		}
		root.put("rooms", roomArr);
		String text = JsonUtil.toJson(root);
		for (String wsId : wsChannelMap.keySet()) {
			sendToWs(wsId, text);
		}
	}

	/** 向所有已登录 WebSocket 客户端广播：某用户加入了某房间 */
	private void broadcastUserJoined(Integer roomId, String userId, String userName) {
		if (roomId == null || userId == null) return;
		Map<String, Object> msg = JsonUtil.newMap();
		msg.put("type", "userJoined");
		msg.put("roomId", roomId);
		msg.put("userId", userId);
		msg.put("userName", userName);
		String text = JsonUtil.toJson(msg);
		for (String wsId : wsChannelMap.keySet()) {
			sendToWs(wsId, text);
		}
	}

	/** 向所有已登录 WebSocket 客户端广播：某用户离开了某房间 */
	private void broadcastUserLeft(Integer roomId, String userId, String userName) {
		if (roomId == null || userId == null) return;
		Map<String, Object> msg = JsonUtil.newMap();
		msg.put("type", "userLeft");
		msg.put("roomId", roomId);
		msg.put("userId", userId);
		msg.put("userName", userName);
		String text = JsonUtil.toJson(msg);
		for (String wsId : wsChannelMap.keySet()) {
			sendToWs(wsId, text);
		}
	}

	/** 直接向指定 WebSocket 客户端发送 JSON 文本（用于登录响应、私聊等特定场景） */
	public void sendWsOne(String wsId, String text) {
		if (wsId == null || text == null) return;
		if (!wsChannelMap.containsKey(wsId)) return;
		// 通过统一入口发送，保证每个 Channel 串行发送
		sendToWs(wsId, text);
	}

	/** WebSocket 私聊：从一个 WebSocket 客户端发给另一个 WebSocket 客户端 */
	public void sendWsPrivate(String sourceWsId, String targetId, String payload) {
		if (sourceWsId == null || targetId == null) return;
		String sourceName = wsUserMap.get(sourceWsId);

		if (wsChannelMap.containsKey(targetId)) {
			Map<String, Object> json = JsonUtil.newMap();
			json.put("type", "private");
			json.put("fromUserName", sourceName == null ? "未知" : sourceName);
			json.put("fromUserId", sourceWsId);
			json.put("payload", payload);
			json.put("timestamp", System.currentTimeMillis());
			sendToWs(targetId, JsonUtil.toJson(json));
			return;
		}

		// 对方不存在，告知发送方
		sendWsOne(sourceWsId, "{\"type\":\"system\",\"payload\":\"对方已下线\"}");
	}

	/** 获取所有在线的 WebSocket Channel（供系统日志 / dashboard 推送使用） */
	public Collection<Channel> getAllWsChannels() {
		return wsChannelMap.values();
	}

	/**
	 * 向所有 WebSocket 客户端广播房间列表（房间增删改时调用）。
	 * 结构：
	 *   {
	 *     type: 'roomList',
	 *     rooms: [
	 *       { id, name, password, userNum },
	 *       ...
	 *     ]
	 *   }
	 */
	public void broadcastRoomList() {
		if (wsChannelMap.isEmpty()) return;
		Map<String, Object> root = JsonUtil.newMap();
		root.put("type", "roomList");
		List<Map<String, Object>> roomArr = new java.util.ArrayList<>();
		for (Room room : roomMap.values()) {
			if (room == null) continue;
			Map<String, Object> rj = JsonUtil.newMap();
			rj.put("id", room.getId());
			rj.put("name", room.getName());
			if (StringUtils.isNotBlank(room.getPassword())) {
				rj.put("hasPassword", "1");
			} else {
				rj.put("hasPassword", "0");
			}
			rj.put("userNum", room.getUserNum() == null ? 0 : room.getUserNum());
			roomArr.add(rj);
		}
		root.put("rooms", roomArr);
		final String text = JsonUtil.toJson(root);
		// 提交到异步线程池，通过 Netty writeAndFlush 推送
		BROADCAST_EXECUTOR.submit(() -> {
			for (Channel ch : wsChannelMap.values()) {
				if (ch != null && ch.isActive()) {
					ch.writeAndFlush(new TextWebSocketFrame(text));
				}
			}
		});
	}
}