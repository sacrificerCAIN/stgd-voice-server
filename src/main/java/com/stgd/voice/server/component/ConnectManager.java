package com.stgd.voice.server.component;

import com.alibaba.fastjson.JSONObject;
import com.stgd.voice.entity.Room;
import com.stgd.voice.entity.User;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Hzzz
 * 统一的连接/用户/房间管理中心，同时支持 Netty TCP 客户端和 WebSocket 浏览器客户端。
 */
@Component
public class ConnectManager {

	// ============ Netty TCP 客户端 ============
	private static final ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

	private static final ConcurrentHashMap<String, ChannelId> channelWithStringIdMap = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();

	// ============ WebSocket 浏览器客户端 ============
	// sessionId -> javax.websocket.Session
	private static final ConcurrentHashMap<String, Session> wsSessionMap = new ConcurrentHashMap<>();
	// sessionId -> 用户名
	private static final ConcurrentHashMap<String, String> wsUserMap = new ConcurrentHashMap<>();
	// sessionId -> 当前房间ID（用于离开时清理）
	private static final ConcurrentHashMap<String, Integer> wsRoomMap = new ConcurrentHashMap<>();

	// ============ 房间管理 ============
	private static final ConcurrentHashMap<Integer, Room> roomMap = new ConcurrentHashMap<>();

	@Autowired
	private RoomMapper roomMapper;

	@Autowired
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

	public void removeRoom(Integer id){
		Room room = roomMap.get(id);
		if (room == null) return;
		Set<String> userIds = room.getUserChannelIdSet();
		if (userIds != null) {
			for (String idStr : userIds) {
				ChannelId channelId = channelWithStringIdMap.get(idStr);
				if (channelId != null) {
					Channel ch = channelGroup.find(channelId);
					if (ch != null && ch.isActive()) {
						ch.writeAndFlush("[系统]: 管理员解散了[" + room.getName() + "]\n");
						continue;
					}
				}
				Session session = wsSessionMap.get(idStr);
				if (session != null && session.isOpen()) {
					try {
						JSONObject msg = new JSONObject();
						msg.put("type", "system");
						msg.put("payload", "管理员解散了[" + room.getName() + "]");
						session.getBasicRemote().sendText(msg.toJSONString());
					} catch (IOException ignored) {}
				}
			}
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
		Channel channel;
		try {
			channel = channelGroup.find(channelId);
		}catch (NullPointerException e){
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
			sourceChannel.writeAndFlush("[服务器]对你说：该用户已下线\n");
			return;
		}

		String sourceUserName;
		if (sourceChannelIdString == null){
			sourceUserName = "服务器";
		}else{
			User sourceUser = findUserByIdString(sourceChannelIdString);
			sourceUserName =  sourceUser.getName();
		}

		channel.writeAndFlush("[" + sourceUserName + "]对你说：" + s + "\n");
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

	// ==================== WebSocket 客户端支持 ====================

	/** 注册一个 WebSocket 会话（连接建立时调用） */
	public void addWsSession(Session session) {
		if (session == null) return;
		wsSessionMap.put(session.getId(), session);
	}

	/** 移除一个 WebSocket 会话（连接关闭时调用，自动退出所在房间、清除用户信息） */
	public void removeWsSession(String sessionId) {
		if (sessionId == null) return;
		wsSessionMap.remove(sessionId);
		String userName = wsUserMap.remove(sessionId);
		Integer roomId = wsRoomMap.remove(sessionId);
		if (roomId != null) {
			Room room = roomMap.get(roomId);
			if (room != null) {
				room.removeUser(sessionId);
				if (userName != null) {
					publishRoomSystem(roomId, userName + " 离开了房间");
					if (logPublisher != null) {
						logPublisher.publish("leave", userName, room.getName(),
							userName + " 离开房间 [" + room.getName() + "]");
					}
					broadcastUserLeft(roomId, sessionId, userName);
				}
			}
		}
		// 系统日志：登出
		if (logPublisher != null && userName != null) {
			logPublisher.publish("logout", userName, null, userName + " 登出系统");
		}
		broadcastAllRoomUsers();
	}

	/** WebSocket 客户端设置昵称（类似 TCP 客户端 type=1 登录） */
	public String registerWsUser(String sessionId, String userName) {
		if (sessionId == null || userName == null || userName.trim().isEmpty()) {
			return null;
		}
		String actualName = userName.trim();
		wsUserMap.put(sessionId, actualName);
		// 系统日志广播（dashboard 会显示）
		if (logPublisher != null) {
			logPublisher.publish("login", actualName, null, actualName + " 登录系统");
		}
		// 刷新在线用户列表
		broadcastAllRoomUsers();
		return actualName;
	}

	/** 获取 WebSocket 客户端的昵称 */
	public String getWsUserName(String sessionId) {
		return sessionId == null ? null : wsUserMap.get(sessionId);
	}

	/** WebSocket 客户端真正加入房间（会更新房间 userNum，房间内所有成员可见） */
	public Room getWsRoomById(Integer targetRoomId) {
		return roomMap.get(targetRoomId);
	}

	/**
	 * WebSocket 客户端真正加入房间（会更新房间 userNum，房间内所有成员可见）
	 */
	public void joinWsRoom(String sessionId, Integer targetRoomId) {
		if (sessionId == null || targetRoomId == null) return;
		if (!wsUserMap.containsKey(sessionId)) return;
		Room targetRoom = roomMap.get(targetRoomId);
		if (targetRoom == null) return;

		// 如果当前已在某个房间，先离开
		Integer oldRoomId = wsRoomMap.get(sessionId);
		if (oldRoomId != null && !oldRoomId.equals(targetRoomId)) {
			Room oldRoom = roomMap.get(oldRoomId);
			if (oldRoom != null) {
				oldRoom.removeUser(sessionId);
				String userName = wsUserMap.get(sessionId);
				if (userName != null) {
					publishRoomSystem(oldRoomId, userName + " 离开了房间");
				}
			}
		}

		// 加入新房间
		targetRoom.addUser(sessionId);
		wsRoomMap.put(sessionId, targetRoomId);
		String userName = wsUserMap.get(sessionId);
		if (userName != null) {
			publishRoomSystem(targetRoomId, userName + " 加入了房间");
			// 系统日志：加入房间（dashboard 会显示）
			if (logPublisher != null) {
				logPublisher.publish("join", userName, targetRoom.getName(),
					userName + " 加入房间 [" + targetRoom.getName() + "]");
			}
			broadcastUserJoined(targetRoomId, sessionId, userName);
		}
		broadcastAllRoomUsers();
	}

	/** WebSocket 客户端离开房间（例如手动切换） */
	public void leaveWsRoom(String sessionId) {
		if (sessionId == null) return;
		Integer roomId = wsRoomMap.remove(sessionId);
		if (roomId != null) {
			Room room = roomMap.get(roomId);
			if (room != null) {
				room.removeUser(sessionId);
				String userName = wsUserMap.get(sessionId);
				if (userName != null) {
					publishRoomSystem(roomId, userName + " 离开了房间");
					broadcastUserLeft(roomId, sessionId, userName);
				}
			}
		}
		broadcastAllRoomUsers();
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

		JSONObject wsJson = new JSONObject();
		wsJson.put("type", "room");
		wsJson.put("roomId", roomId);
		wsJson.put("userName", userName);
		wsJson.put("payload", payload);
		wsJson.put("timestamp", System.currentTimeMillis());
		String wsText = wsJson.toJSONString();

		for (String idStr : room.getUserChannelIdSet()) {
			// 先尝试作为 Netty channelId 推送
			ChannelId channelId = channelWithStringIdMap.get(idStr);
			if (channelId != null) {
				Channel ch = channelGroup.find(channelId);
				if (ch != null && ch.isActive()) {
					ch.writeAndFlush(nettyText);
					continue;
				}
			}
			// 再尝试作为 WebSocket sessionId 推送
			Session session = wsSessionMap.get(idStr);
			if (session != null && session.isOpen()) {
				try {
					session.getBasicRemote().sendText(wsText);
				} catch (IOException ignored) {}
			}
		}
	}

	/** 向房间内所有成员发送一条系统提示（xxx 加入了房间 / xxx 离开了房间） */
	public void publishRoomSystem(Integer roomId, String text) {
		if (roomId == null || text == null) return;
		Room room = roomMap.get(roomId);
		if (room == null) return;

		String nettyText = "[系统]: " + text + "\n";
		JSONObject wsJson = new JSONObject();
		wsJson.put("type", "system");
		wsJson.put("payload", text);
		wsJson.put("timestamp", System.currentTimeMillis());
		String wsText = wsJson.toJSONString();

		for (String idStr : room.getUserChannelIdSet()) {
			ChannelId channelId = channelWithStringIdMap.get(idStr);
			if (channelId != null) {
				Channel ch = channelGroup.find(channelId);
				if (ch != null && ch.isActive()) {
					ch.writeAndFlush(nettyText);
					continue;
				}
			}
			Session session = wsSessionMap.get(idStr);
			if (session != null && session.isOpen()) {
				try {
					session.getBasicRemote().sendText(wsText);
				} catch (IOException ignored) {}
			}
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
		if (wsSessionMap.isEmpty()) return;
		JSONObject root = new JSONObject();
		root.put("type", "roomUsers");
		List<JSONObject> roomArr = new java.util.ArrayList<>();
		for (Room room : roomMap.values()) {
			if (room == null) continue;
			JSONObject rj = new JSONObject();
			rj.put("id", room.getId());
			rj.put("name", room.getName());
			rj.put("password", room.getPassword());
			rj.put("userNum", room.getUserNum() == null ? 0 : room.getUserNum());
			List<JSONObject> userArr = new java.util.ArrayList<>();
			Set<String> ids = room.getUserChannelIdSet();
			if (ids != null) {
				for (String idStr : ids) {
					String un = wsUserMap.get(idStr);
					if (un == null) continue;
					JSONObject uj = new JSONObject();
					uj.put("userId", idStr);
					uj.put("userName", un);
					userArr.add(uj);
				}
			}
			rj.put("users", userArr);
			roomArr.add(rj);
		}
		root.put("rooms", roomArr);
		String text = root.toJSONString();
		for (Session s : wsSessionMap.values()) {
			if (s != null && s.isOpen()) {
				try { s.getBasicRemote().sendText(text); } catch (IOException ignored) {}
			}
		}
	}

	/** 向所有已登录 WebSocket 客户端广播：某用户加入了某房间 */
	private void broadcastUserJoined(Integer roomId, String userId, String userName) {
		if (roomId == null || userId == null) return;
		JSONObject msg = new JSONObject();
		msg.put("type", "userJoined");
		msg.put("roomId", roomId);
		msg.put("userId", userId);
		msg.put("userName", userName);
		String text = msg.toJSONString();
		for (Session s : wsSessionMap.values()) {
			if (s != null && s.isOpen()) {
				try { s.getBasicRemote().sendText(text); } catch (IOException ignored) {}
			}
		}
	}

	/** 向所有已登录 WebSocket 客户端广播：某用户离开了某房间 */
	private void broadcastUserLeft(Integer roomId, String userId, String userName) {
		if (roomId == null || userId == null) return;
		JSONObject msg = new JSONObject();
		msg.put("type", "userLeft");
		msg.put("roomId", roomId);
		msg.put("userId", userId);
		msg.put("userName", userName);
		String text = msg.toJSONString();
		for (Session s : wsSessionMap.values()) {
			if (s != null && s.isOpen()) {
				try { s.getBasicRemote().sendText(text); } catch (IOException ignored) {}
			}
		}
	}

	/** 获取指定 WebSocket 会话所在的房间ID */
	public Integer getWsRoomId(String sessionId) {
		return sessionId == null ? null : wsRoomMap.get(sessionId);
	}

	/** 直接向指定 WebSocket 会话发送 JSON 文本（用于登录响应、私聊等特定场景） */
	public void sendWsOne(String sessionId, String text) {
		if (sessionId == null) return;
		Session session = wsSessionMap.get(sessionId);
		if (session != null && session.isOpen()) {
			try {
				session.getBasicRemote().sendText(text);
			} catch (IOException ignored) {}
		}
	}

	/** WebSocket 私聊：从一个 WebSocket 客户端发给另一个 WebSocket 或 Netty 客户端 */
	public void sendWsPrivate(String sourceSessionId, String targetId, String payload) {
		if (sourceSessionId == null || targetId == null) return;
		String sourceName = wsUserMap.get(sourceSessionId);

		// 先尝试目标是 WebSocket 客户端
		Session targetSession = wsSessionMap.get(targetId);
		if (targetSession != null && targetSession.isOpen()) {
			JSONObject json = new JSONObject();
			json.put("type", "private");
			json.put("fromUserName", sourceName == null ? "未知" : sourceName);
			json.put("fromUserId", sourceSessionId);
			json.put("payload", payload);
			json.put("timestamp", System.currentTimeMillis());
			try {
				targetSession.getBasicRemote().sendText(json.toJSONString());
			} catch (IOException ignored) {}
			return;
		}

		// 再尝试目标是 Netty 客户端
		ChannelId targetChannelId = channelWithStringIdMap.get(targetId);
		if (targetChannelId != null) {
			Channel ch = channelGroup.find(targetChannelId);
			if (ch != null && ch.isActive()) {
				ch.writeAndFlush("[" + (sourceName == null ? "未知" : sourceName) + "]对你说：" + (payload == null ? "" : payload) + "\n");
				return;
			}
		}

		// 对方不存在，告知发送方
		sendWsOne(sourceSessionId, "{\"type\":\"system\",\"payload\":\"对方已下线\"}");
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
		if (wsSessionMap.isEmpty()) return;
		JSONObject root = new JSONObject();
		root.put("type", "roomList");
		List<JSONObject> roomArr = new java.util.ArrayList<>();
		for (Room room : roomMap.values()) {
			if (room == null) continue;
			JSONObject rj = new JSONObject();
			rj.put("id", room.getId());
			rj.put("name", room.getName());
			rj.put("password", room.getPassword());
			rj.put("userNum", room.getUserNum() == null ? 0 : room.getUserNum());
			roomArr.add(rj);
		}
		root.put("rooms", roomArr);
		String text = root.toJSONString();
		for (Session s : wsSessionMap.values()) {
			if (s != null && s.isOpen()) {
				try { s.getBasicRemote().sendText(text); } catch (IOException ignored) {}
			}
		}
	}
}