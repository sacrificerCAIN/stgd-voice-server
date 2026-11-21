package com.stgd.voice.server.component;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.model.entity.Room;
import com.stgd.voice.model.entity.User;
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

import javax.annotation.PreDestroy;
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
	 * <p>
	 * 关键配置：
	 *   - 有界队列：最大 10000 个待推送任务，超过后触发拒绝策略（防止 OOM）
	 *   - CallerRunsPolicy：队列满时由提交线程自己执行（自然反压，不丢消息）
	 *   - 固定核心线程数：Math.max(2, CPU/2)，IO 密集型不需要太多线程
	 */
	public static final int BROADCAST_THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
	private static final int BROADCAST_QUEUE_CAPACITY = 10000;

	/**
	 * 实例级广播线程池：随 ConnectManager 单例创建，不再作为 static 全局状态。
	 */
	private final ExecutorService broadcastExecutor = new ThreadPoolExecutor(
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

	/** 优雅关停时等待在途推送完成的超时时间（秒） */
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

	/** 房间状态广播合并窗口（毫秒）：窗口内多次触发只推送一次 */
	private static final long BROADCAST_MERGE_WINDOW_MS = 150L;

	/**
	 * 每个 WebSocket 连接待发送队列的容量。
	 * 慢客户端（TCP 缓冲区写满）来不及消费时，队列不会无限增长；
	 * 满了之后丢弃最旧的消息，优先保证最新的房间状态/聊天消息送达。
	 */
	private static final int WS_QUEUE_CAPACITY = 256;

	private final ScheduledExecutorService broadcastScheduler =
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "broadcast-scheduler");
			t.setDaemon(true);
			return t;
		});

	private final AtomicBoolean roomListPending = new AtomicBoolean(false);
	private final AtomicBoolean roomUsersPending = new AtomicBoolean(false);

	// ============ Netty TCP 客户端 ============
	private final ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

	// channelId 字符串 -> Channel（O(1) 查找，避免 DefaultChannelGroup.find 的全量扫描）
	private final ConcurrentHashMap<String, Channel> channelStringToChannel = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();

	// ============ WebSocket 浏览器客户端（使用 Netty WebSocket 实现，替换原来的 javax.websocket） ============
	// wsId -> Netty Channel（wsId 即为原来的 httpSessionId，由前端传入或后端生成唯一ID）
	private final ConcurrentHashMap<String, Channel> wsChannelMap = new ConcurrentHashMap<>();
	// Netty Channel -> wsId（反向映射，用于从 Channel 快速反查 wsId，避免 O(n) 遍历）
	private final ConcurrentHashMap<Channel, String> channelToWsId = new ConcurrentHashMap<>();
	// wsId -> 昵称（用户在前端自定义设置）
	private final ConcurrentHashMap<String, String> wsUserMap = new ConcurrentHashMap<>();
	// wsId -> 当前房间ID（用于离开时清理）
	private final ConcurrentHashMap<String, Integer> wsRoomMap = new ConcurrentHashMap<>();
	// wsId -> 首次登录时间戳（毫秒），用于展示在线时长
	private final ConcurrentHashMap<String, Long> wsFirstSeenMap = new ConcurrentHashMap<>();


	// ============ WebSocket 消息推送队列（保证每个 Channel 串行发送，避免并发写入） ============
	// wsId -> 待发送消息队列（每个 Channel 独立一条有界队列，满时丢弃最旧消息）
	private final ConcurrentHashMap<String, LinkedBlockingQueue<String>> wsMessageQueues = new ConcurrentHashMap<>();
	// wsId -> 写标志（true = 已有写线程正在消费队列，false = 需要启动新的写线程）
	private final ConcurrentHashMap<String, AtomicBoolean> wsWritingFlags = new ConcurrentHashMap<>();

	// ============ 房间管理 ============
	private final ConcurrentHashMap<Integer, Room> roomMap = new ConcurrentHashMap<>();

	@Resource
	private RoomMapper roomMapper;

	@Resource
	private SystemLogPublisher logPublisher;

	public void init(){
		System.out.println("正在初始化房间...");
		//加载所有房间到内存
		List<Room> roomList = roomMapper.selectAll();
		roomList.forEach(room -> roomMap.put(room.getId(), room));
		System.out.println("房间初始化完成");
	}

	/**
	 * 优雅关停：先停止接收新任务，再等待在途广播发送完成；
	 * 超时仍未结束才强制中断，避免关停时丢消息或线程泄漏。
	 */
	@PreDestroy
	public void shutdown() {
		// 1. 先停调度器：让合并窗口内已排队的任务执行完，它们会把手上的广播交给线程池
		broadcastScheduler.shutdown();
		try {
			if (!broadcastScheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				broadcastScheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			broadcastScheduler.shutdownNow();
			Thread.currentThread().interrupt();
			return;
		}
		// 2. 再停线程池：不再接收新任务，等待在途推送全部发出
		broadcastExecutor.shutdown();
		try {
			if (!broadcastExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				broadcastExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			broadcastExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/** 提交广播任务；返回 false 表示容器正在关停、任务已被丢弃 */
	private boolean submitBroadcast(Runnable task) {
		if (broadcastExecutor.isShutdown()) return false;
		try {
			broadcastExecutor.submit(task);
			return true;
		} catch (RejectedExecutionException ignore) {
			// 并发关停窗口内被拒绝，直接丢弃
			return false;
		}
	}

	/** 延迟调度合并广播；容器关停后不再接受新调度 */
	private void scheduleBroadcast(Runnable task) {
		if (broadcastScheduler.isShutdown()) return;
		try {
			broadcastScheduler.schedule(task, BROADCAST_MERGE_WINDOW_MS, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException ignore) {
			// 并发关停窗口内被拒绝，直接丢弃
		}
	}

	/** 关闭所有已建立的连接（容器关停时由 NettyMain 调用） */
	public void closeAllConnections() {
		// TCP：ChannelGroup 批量关闭，会先尝试把已排队的数据写出去
		if (!channelGroup.isEmpty()) {
			channelGroup.close();
		}
		// WebSocket：逐个关闭
		for (Channel ch : wsChannelMap.values()) {
			if (ch != null && ch.isActive()) {
				ch.close();
			}
		}
	}

	public void addClient(ChannelHandlerContext ctx){
		String channelIdStr = ctx.channel().id().asLongText();
		channelGroup.add(ctx.channel());
		channelStringToChannel.put(channelIdStr, ctx.channel());
	}

	public void removeClient(ChannelHandlerContext ctx){
		String channelIdStr = ctx.channel().id().asLongText();
		// TCP 连接断开（含空闲超时、异常断网）：退出所在房间并通知房间内其他成员
		User user = userMap.get(channelIdStr);
		String userName = (user != null) ? user.getName() : null;
		boolean roomChanged = false;
		for (Room room : roomMap.values()) {
			if (room == null || !room.getUserChannelIdSet().contains(channelIdStr)) continue;
			room.removeUser(channelIdStr);
			roomChanged = true;
			if (userName != null) {
				publishRoomSystemWs(room.getId(), userName + " 离开了房间", channelIdStr);
				if (logPublisher != null) {
					logPublisher.publish("leave", userName, room.getName(),
						userName + " 离开房间 [" + room.getName() + "]");
				}
			}
		}
		if (roomChanged) {
			broadcastAllRoomUsers();
		}
		channelGroup.remove(ctx.channel());
		channelStringToChannel.remove(channelIdStr);
		// 异常断线时 userMap 可能残留，这里统一清理，避免幽灵用户
		userMap.remove(channelIdStr);
		ctx.channel().close();
	}

	public Channel getChannelByChannelId(String channelIdString){
		return channelStringToChannel.get(channelIdString);
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
		// 仅更新内存；房间列表广播由增删改入口显式触发，避免入房等场景重复全量广播
		roomMap.put(room.getId(), room);
	}

	public void removeRoom(Integer id) {
		Room room = roomMap.get(id);
		if (room == null) return;
		Set<String> userIds = room.getUserChannelIdSet();
		if (userIds != null) {
			// Netty TCP 客户端推送
			for (String idStr : userIds) {
				Channel ch = channelStringToChannel.get(idStr);
				if (ch != null && ch.isActive()) {
					ch.writeAndFlush("[系统]: 管理员解散了[" + room.getName() + "]\n");
				}
			}
			// Netty WebSocket 推送：异步 + 线程池
			final String roomName = room.getName();
			submitBroadcast(() -> {
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

	/** 保证消息以单个换行结尾，兼容调用方传入的 "\n"，避免双换行 */
	private String ensureNewline(String s) {
		if (s == null) return "\n";
		return s.endsWith("\n") ? s : s + "\n";
	}

	public void publishOne(ChannelHandlerContext ctx, String s){
		ctx.channel().writeAndFlush("[服务器]对你说：" + ensureNewline(s));
	}

	public void publishOne(String targetChannelIdString, String s){
		Channel channel = channelStringToChannel.get(targetChannelIdString);
		if (channel == null || !channel.isActive()){
			return;
		}
		channel.writeAndFlush("[服务器]对你说：" + ensureNewline(s));
	}

	public void publishOne(String sourceChannelIdString, String targetChannelIdString, String s){
		Channel channel = channelStringToChannel.get(targetChannelIdString);
		if (channel == null || !channel.isActive()){
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
			if (sourceUser != null){
				sourceUserName = sourceUser.getName();
			}else{
				return;
			}
		}

        channel.writeAndFlush("[" + sourceUserName + "]对你说：" + ensureNewline(s));
    }

	public void publishAll(String s){
		channelGroup.writeAndFlush(s + "\n");
	}

	// ==================== WebSocket 客户端支持（Netty WebSocket） ====================

	/** 注册一个 WebSocket 连接（连接建立时调用） */
	public void addWsChannel(String wsId, Channel wsChannel) {
		if (wsId == null || wsChannel == null) return;
		wsChannelMap.put(wsId, wsChannel);
		channelToWsId.put(wsChannel, wsId);
		if (!wsFirstSeenMap.containsKey(wsId)) {
			wsFirstSeenMap.put(wsId, System.currentTimeMillis());
		}
	}

	/** 尝试从 Netty Channel 获取远程客户端的 IP（IPv4/IPv6） */
	public String getWsIp(Channel channel) {
		if (channel == null) return "";
		try {
			java.net.SocketAddress sa = channel.remoteAddress();
			if (sa instanceof java.net.InetSocketAddress) {
				java.net.InetAddress ia = ((java.net.InetSocketAddress) sa).getAddress();
				if (ia != null) return ia.getHostAddress();
			}
			return String.valueOf(sa);
		} catch (Exception e) {
			return "";
		}
	}

	/** 移除一个 WebSocket 连接（连接关闭时调用，自动退出所在房间、清除用户信息） */
	public void removeWsChannel(String wsId) {
		if (wsId == null) return;
		Channel oldCh = wsChannelMap.remove(wsId);
		if (oldCh != null) {
			channelToWsId.remove(oldCh);
		}
		wsFirstSeenMap.remove(wsId);
		String userName = wsUserMap.remove(wsId);
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
	 *   3. 只有当没有写线程在工作时，才启动一个新的写线程到 broadcastExecutor
	 */
	public void sendToWs(String wsId, String text) {
		if (wsId == null || text == null) return;
		Channel channel = wsChannelMap.get(wsId);
		if (channel == null || !channel.isActive()) return;

		// 1. 获取/创建该 Channel 的消息队列（有界，防止慢客户端场景内存无限增长）
		LinkedBlockingQueue<String> queue = wsMessageQueues.computeIfAbsent(
			wsId, k -> new LinkedBlockingQueue<>(WS_QUEUE_CAPACITY)
		);
		// 2. 获取/创建该 Channel 的写标志
		AtomicBoolean writing = wsWritingFlags.computeIfAbsent(
			wsId, k -> new AtomicBoolean(false)
		);

		// 3. 把消息放入队列。队列已满（客户端积压过多）时丢弃最旧的一条，
		//    丢弃而不是阻塞：实时聊天场景下最新消息比历史积压更有价值，也不会反压业务线程。
		if (!queue.offer(text)) {
			queue.poll();
			queue.offer(text);
		}

		// 4. CAS 检查：如果当前没有写线程，就启动一个新的写线程消费队列
		if (writing.compareAndSet(false, true)) {
			if (!submitBroadcast(() -> drainWsQueue(channel, queue, writing))) {
				// 线程池已关停，释放写标志，避免后续消息永久卡在队列里
				writing.set(false);
			}
		}
	}

	/**
	 * 写线程：循环从队列取消息，通过 writeAndFlush(TextWebSocketFrame) 发送。
	 * 单线程内调用 writeAndFlush 串行写入，保证无并发冲突。
	 */
	private void drainWsQueue(Channel channel,
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
					// 异步写入：不阻塞广播线程池；同一 Channel 的写顺序由队列 + 单消费线程保证
					channel.writeAndFlush(new TextWebSocketFrame(msg));
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
	public void sendToWs(Channel channel, String text) {
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
					// 切房在协议层表现为「先离开旧房间再进入新房间」：同步广播 userLeft，
					// 避免其它客户端在 roomUsers 全量快照到达前把该用户同时算在旧房间里
					broadcastUserLeft(oldRoomId, wsId, userName);
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

	/** 获取指定 wsId 所在的房间 ID */
	public Integer getWsRoomId(String wsId) {
		return wsId == null ? null : wsRoomMap.get(wsId);
	}

	// ==================== 统一的房间消息推送（TCP + WebSocket） ====================

	/**
	 * 向房间内所有成员发送一条聊天消息（TCP 与 WebSocket 双通道）。
	 * - TCP 客户端收到: [userName]: payload
	 * - WebSocket 客户端收到: {"type":"room","roomId":...,"wsId":...,"userName":"...","payload":"...","timestamp":...}
	 * senderWsId 为发送者的 wsId；TCP 客户端发送时传 null。
	 */
	public void publishRoomMessage(Integer roomId, String userName, String payload) {
		publishRoomMessageToAll(roomId, userName, payload, null);
	}

	public void publishRoomMessageWs(Integer roomId, String userName, String payload, String wsId) {
		publishRoomMessageToAll(roomId, userName, payload, wsId);
	}

	/** 同一条房间消息同时推送给房间内的 TCP 与 WebSocket 成员 */
	private void publishRoomMessageToAll(Integer roomId, String userName, String payload, String senderWsId) {
		if (roomId == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;

		String safeName = (userName == null) ? "未知" : userName;
		String safePayload = (payload == null) ? "" : payload;
		String tcpText = "[" + safeName + "]: " + safePayload + "\n";

		Map<String, Object> wsJson = JsonUtil.newMap();
		wsJson.put("type", "room");
		wsJson.put("roomId", roomId);
		wsJson.put("wsId", senderWsId);
		wsJson.put("userName", safeName);
		wsJson.put("payload", safePayload);
		wsJson.put("timestamp", System.currentTimeMillis());
		String wsText = JsonUtil.toJson(wsJson);

		dispatchToRoomMembers(room, tcpText, wsText, null);
	}

	/**
	 * 向房间内所有成员推送一条消息（TCP 与 WebSocket 双通道）：
	 * - TCP 成员：直接写出 tcpText
	 * - WebSocket 成员：写出 wsText（统一走 sendToWs，保证每个 Channel 串行发送）
	 * excludeId 为需要跳过的成员 id（wsId 或 TCP channelId），传 null 表示不跳过。
	 */
	private void dispatchToRoomMembers(Room room, String tcpText, String wsText, String excludeId) {
		if (room == null) return;
		Set<String> memberIds = room.getUserChannelIdSet();
		if (memberIds == null) return;
		for (String idStr : memberIds) {
			if (StringUtils.equals(excludeId, idStr)) continue;
			Channel channel = channelStringToChannel.get(idStr);
			if (channel != null) {
				// TCP 成员
				if (channel.isActive()) {
					channel.writeAndFlush(tcpText);
				}
				continue;
			}
			// WebSocket 成员：走统一入口，保证每个 Channel 串行发送
			Channel wsChannel = wsChannelMap.get(idStr);
			if (wsChannel != null && wsChannel.isActive()) {
				sendToWs(idStr, wsText);
			}
		}
	}

	/**
	 * 向房间内的 TCP 与 WebSocket 成员推送系统提示（如 xxx 加入/离开了房间）。
	 * - TCP 客户端收到: [系统]: text
	 * - WebSocket 客户端收到: {"type":"system","payload":"...","timestamp":...}
	 * excludeId 为触发提示的成员 id（wsId 或 TCP channelId），不为空时不发给该成员。
	 */
	public void publishRoomSystemWs(Integer roomId, String text, String excludeId) {
		if (roomId == null || text == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;

		Map<String, Object> wsJson = JsonUtil.newMap();
		wsJson.put("type", "system");
		wsJson.put("payload", text);
		wsJson.put("timestamp", System.currentTimeMillis());
		String wsText = JsonUtil.toJson(wsJson);
		String tcpText = "[系统]: " + text + "\n";

		dispatchToRoomMembers(room, tcpText, wsText, excludeId);
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
		// 合并窗口内的多次触发，避免进出房/断线密集时重复构建与推送全量用户树
		if (!roomUsersPending.compareAndSet(false, true)) return;
		scheduleBroadcast(() -> {
			roomUsersPending.set(false);
			doBroadcastAllRoomUsers();
		});
	}

	private void doBroadcastAllRoomUsers() {
		if (wsChannelMap.isEmpty()) return;
		String text = buildAllRoomUsersJson();
		for (String wsId : wsChannelMap.keySet()) {
			sendToWs(wsId, text);
		}
	}

	/** 构建房间的公共字段（id/name/hasPassword/userNum），供 roomList 与 roomUsers 复用 */
	private Map<String, Object> buildRoomBaseJson(Room room) {
		Map<String, Object> rj = JsonUtil.newMap();
		rj.put("id", room.getId());
		rj.put("name", room.getName());
		if (StringUtils.isNotBlank(room.getPassword())) {
			rj.put("hasPassword", "1");
		} else {
			rj.put("hasPassword", "0");
		}
		rj.put("userNum", room.getUserNum() == null ? 0 : room.getUserNum());
		return rj;
	}

	private String buildAllRoomUsersJson() {
		Map<String, Object> root = JsonUtil.newMap();
		root.put("type", "roomUsers");
		List<Map<String, Object>> roomArr = new ArrayList<>();
		for (Room room : roomMap.values()) {
			if (room == null) continue;
			Map<String, Object> rj = buildRoomBaseJson(room);
			List<Map<String, Object>> userArr = new ArrayList<>();
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
		return JsonUtil.toJson(root);
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
	 * 获取指定房间的在线用户列表（dashboard 使用）。
	 * 返回结构：
	 *   [{ userId, userName, ip, firstSeenTs }, ...]
	 */
	public List<Map<String, Object>> getRoomOnlineUsers(Integer roomId) {
		List<Map<String, Object>> result = new ArrayList<>();
		if (roomId == null) return result;
		Room room = roomMap.get(roomId);
		if (room == null) return result;
		Set<String> ids = room.getUserChannelIdSet();
		if (ids == null) return result;
		for (String idStr : ids) {
			if (idStr == null) continue;
			Channel ch = wsChannelMap.get(idStr);
			if (ch == null || !ch.isActive()) continue;
			String un = wsUserMap.get(idStr);
			Long firstSeen = wsFirstSeenMap.get(idStr);
			Map<String, Object> u = JsonUtil.newMap();
			u.put("userId", idStr);
			u.put("userName", un == null ? "(未命名)" : un);
			u.put("ip", getWsIp(ch));
			u.put("firstSeenTs", firstSeen == null ? System.currentTimeMillis() : firstSeen);
			result.add(u);
		}
		return result;
	}

	/**
	 * 管理员强制指定 wsId 用户下线（关闭连接）。
	 * 返回：true = 成功找到并关闭，false = 找不到该用户
	 */
	public boolean kickWsUser(String wsId) {
		if (wsId == null || !wsChannelMap.containsKey(wsId)) return false;
		String userName = wsUserMap.get(wsId);
		Channel ch = wsChannelMap.get(wsId);
		// 先发送一条下线通知（可选），然后关闭连接
		try {
			if (ch != null && ch.isActive()) {
				Map<String, Object> msg = JsonUtil.newMap();
				msg.put("type", "system");
				msg.put("payload", "管理员已将你踢下线");
				sendToWs(ch, JsonUtil.toJson(msg));
				ch.close();
			}
		} catch (Exception ignore) {}
		removeWsChannel(wsId);
		if (logPublisher != null) {
			logPublisher.publish("logout", userName,
				null, (userName == null ? "某用户" : userName) + " 被管理员强制下线");
		}
		broadcastAllRoomUsers();
		return true;
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
		// 合并窗口内的多次触发，避免增删改房的连环调用重复构建与推送
		if (!roomListPending.compareAndSet(false, true)) return;
		scheduleBroadcast(() -> {
			roomListPending.set(false);
			doBroadcastRoomList();
		});
	}

	private void doBroadcastRoomList() {
		if (wsChannelMap.isEmpty()) return;
		String text = buildRoomListJson();
		// 通过统一入口发送，保证每个 Channel 与 roomUsers/聊天消息串行写入，避免帧顺序错乱
		for (String wsId : wsChannelMap.keySet()) {
			sendToWs(wsId, text);
		}
	}

	private String buildRoomListJson() {
		Map<String, Object> root = JsonUtil.newMap();
		root.put("type", "roomList");
		List<Map<String, Object>> roomArr = new ArrayList<>();
		for (Room room : roomMap.values()) {
			if (room == null) continue;
			roomArr.add(buildRoomBaseJson(room));
		}
		root.put("rooms", roomArr);
		return JsonUtil.toJson(root);
	}

	/** 向单个 WebSocket 客户端发送一次全量房间状态（握手时使用，避免向全员广播） */
	public void sendRoomStateToOne(String wsId) {
		if (wsId == null || !wsChannelMap.containsKey(wsId)) return;
		sendWsOne(wsId, buildRoomListJson());
		sendWsOne(wsId, buildAllRoomUsersJson());
	}
}