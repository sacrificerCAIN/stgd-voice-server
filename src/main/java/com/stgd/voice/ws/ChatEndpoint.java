package com.stgd.voice.ws;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.util.SessionUtil;
import com.stgd.voice.config.HttpSessionConfigurator;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;

import java.util.Map;

@Component
@ServerEndpoint(value = "/ws/chat", configurator = HttpSessionConfigurator.class)
public class ChatEndpoint {

    private static ConnectManager connectManager;
    private static RoomMapper roomMapper;
    private static SystemLogPublisher logPublisher;

    @Autowired
    public void setConnectManager(ConnectManager cm) {
        connectManager = cm;
    }

    @Autowired
    public void setRoomMapper(RoomMapper rm) {
        roomMapper = rm;
    }

    @Autowired
    public void setLogPublisher(SystemLogPublisher lp) {
        logPublisher = lp;
    }

    @OnOpen
    public void onOpen(Session session) {
        connectManager.addWsSession(session);
        // 从 URL query 中解析登录用户名（前端 connectChat 时通过 ?loginUser=xxx 传递）
        // 这是权限校验的核心依据，不依赖用户自定义昵称
        java.net.URI uri = session.getRequestURI();
        if (uri != null) {
            String query = uri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "loginUser".equals(pair[0])) {
                        try {
                            String loginUser = java.net.URLDecoder.decode(pair[1], "UTF-8");
                            if (loginUser != null && !loginUser.trim().isEmpty()) {
                                connectManager.setWsLoginUser(SessionUtil.getHttpSessionId(session), loginUser.trim());
                            }
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                }
            }
        }
        // 新连接建立时，立即推送一次房间列表
        connectManager.broadcastRoomList();
    }

    @OnClose
    public void onClose(Session session) {
        connectManager.removeWsSession(SessionUtil.getHttpSessionId(session));
        connectManager.broadcastAllRoomUsers();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        connectManager.removeWsSession(SessionUtil.getHttpSessionId(session));
    }

    @OnMessage
    public void onMessage(Session session, String text) {
        try {
            Message message = JsonUtil.parse(text, Message.class);
            if (message == null || message.getType() == null) {
                sendSystem(session, "消息格式错误");
                return;
            }
            int type = message.getType();
            switch (type) {
                case 1: handleLogin(session, message);  break;
                case 2: handleIdle(session, message);   break;
                case 3: handleRoomMessage(session, message); break;
                case 4: handlePrivateMessage(session, message); break;
                case 5: handleBroadcast(session, message); break;
                case 6: handleJoinRoom(session, message); break;
                case 7: handleLogout(session); break;
                case 8: handleGetAllRoomUsers(session); break;
                case 9: handleGetRoomList(session); break;
                case 10: handleInsertRoom(session, message); break;
                case 11: handleRemoveRoom(session, message); break;
                case 12: handleUpdateRoom(session, message); break;
                case 13: handleWebRtcSignal(session, message); break;
                default: sendSystem(session, "未知消息类型");
            }
        } catch (Exception e) {
            sendSystem(session, "消息解析失败");
            e.printStackTrace();
        }
    }

    private void handleLogin(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = message.getUserName();
        if (userName == null || userName.trim().isEmpty()) {
            sendSystem(session, "用户名不能为空");
            return;
        }
        // 用 userName（用户自定义的昵称）注册到 wsUserMap
        // 登录的真实用户名已在 onOpen 时通过 URL query 保存到 wsLoginUserMap（用于权限校验）
        String actualName = connectManager.registerWsUser(SessionUtil.getHttpSessionId(session), userName);
        Map<String, Object> resp = JsonUtil.newMap();
        resp.put("type", "login");
        resp.put("userId", SessionUtil.getHttpSessionId(session));
        resp.put("userName", actualName);
        sendJson(session, resp);
    }

    private void handleIdle(Session session, Message message) {
        Map<String, Object> resp = JsonUtil.newMap();
        resp.put("type", "idle");
        resp.put("timestamp", System.currentTimeMillis());
        sendJson(session, resp);
    }

    private void handleJoinRoom(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        Integer targetRoomId = message.getTargetRoomId();
        if (targetRoomId == null) {
            sendSystem(session, "目标房间ID不能为空");
            return;
        }
        Room room = connectManager.getWsRoomById(targetRoomId);
        if (room == null) {
            sendSystem(session, "房间不存在");
            return;
        }
        // 2. 密码校验逻辑
        String roomPwd = room.getPassword();
        String inputPwd = message.getPassword();
        // 房间有密码
        if (!StringUtils.isBlank(roomPwd)) {
            // 用户没输入密码 / 密码不匹配
            if (inputPwd == null || !inputPwd.equals(roomPwd)) {
                sendSystem(session, "房间密码错误");
                return;
            }
        }
        connectManager.joinWsRoom(SessionUtil.getHttpSessionId(session), targetRoomId);
        Map<String, Object> resp = JsonUtil.newMap();
        resp.put("type", "join");
        resp.put("roomId", targetRoomId);
        resp.put("roomName", room.getName());
        resp.put("userNum", room.getUserNum());
        sendJson(session, resp);
        connectManager.broadcastAllRoomUsers();
    }

    private void handleRoomMessage(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        Integer roomId = connectManager.getWsRoomId(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        String httpSessionId = SessionUtil.getHttpSessionId(session);
        if (roomId == null) {
            sendSystem(session, "请先加入房间");
            return;
        }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        connectManager.publishRoomMessageWs(roomId, userName, payload, httpSessionId);
    }

    /**
     * WebRTC 信令转发（type=13）。
     * 消息结构：{type:13, targetUserId:"...", payload:"{kind:offer|answer|ice, data:...}"}
     * targetUserId 为空时广播到房间内其他所有人。
     */
    private void handleWebRtcSignal(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        String httpSessionId = SessionUtil.getHttpSessionId(session);
        Integer roomId = connectManager.getWsRoomId(httpSessionId);
        if (roomId == null) {
            sendSystem(session, "请先加入房间");
            return;
        }
        String targetUserId = message.getTargetUserId();
        String payload = message.getPayload();
        if (payload == null) payload = "";
        // 构造转发消息，附带 fromUserId 方便接收方过滤
        Map<String, Object> forward = JsonUtil.newMap();
        forward.put("type", "webrtc");
        forward.put("roomId", roomId);
        forward.put("fromUserId", httpSessionId);
        forward.put("fromUserName", userName);
        forward.put("targetUserId", targetUserId);
        forward.put("payload", payload);
        String forwardStr = JsonUtil.toJson(forward);
        connectManager.publishRoomWebRtcWs(roomId, httpSessionId, targetUserId, forwardStr);
    }

    private void handlePrivateMessage(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        String targetUserId = message.getTargetUserId();
        if (targetUserId == null || targetUserId.isEmpty()) {
            sendSystem(session, "目标用户ID不能为空");
            return;
        }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        connectManager.sendWsPrivate(SessionUtil.getHttpSessionId(session), targetUserId, payload);
    }

    private void handleBroadcast(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        Map<String, Object> msg = JsonUtil.newMap();
        msg.put("type", "broadcast");
        msg.put("userName", userName);
        msg.put("payload", payload);
        msg.put("timestamp", System.currentTimeMillis());
        String text = JsonUtil.toJson(msg);
        // 通过 ConnectManager 的统一入口发送，保证每个 Session 的消息永远串行发送
        for (Session s : session.getOpenSessions()) {
            if (s != null) {
                ConnectManager.sendToWs(s, text);
            }
        }
    }

    private void handleLogout(Session session) {
        connectManager.removeWsSession(SessionUtil.getHttpSessionId(session));
        connectManager.broadcastAllRoomUsers();
    }

    /**
     * 主动向所有已登录的 WebSocket 客户端广播当前全量的"房间-用户"信息，
     * 用于客户端在侧栏以树形结构展示每个房间下的用户列表。
     */
    private void handleGetAllRoomUsers(Session session) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        connectManager.broadcastAllRoomUsers();
    }

    /**
     * 处理：主动拉取房间列表（type 9）
     */
    private void handleGetRoomList(Session session) {
        connectManager.broadcastRoomList();
    }

    /**
     * 处理：添加房间（type 10）
     * 消息体：{ type: 10, name: "房间名", password: "可选密码(sha256加密后)" }
     */
    private void handleInsertRoom(Session session, Message message) {
        // 用 HTTP Session 登录用户名做权限校验，避免通过伪造昵称绕过
        String loginUserName = connectManager.getWsLoginUser(SessionUtil.getHttpSessionId(session));
        if (loginUserName == null) {
            sendSystem(session, "登录信息丢失，请刷新页面");
            return;
        }
        if (!"super".equals(loginUserName)) {
            sendSystem(session, "没有权限添加房间");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        String roomName = message.getRoomName();
        if (roomName == null || roomName.trim().isEmpty()) {
            sendSystem(session, "房间名称不能为空");
            return;
        }
        Room room = new Room();
        room.setName(roomName.trim());
        String pwd = message.getPassword();
        if (pwd != null && !pwd.trim().isEmpty()) {
            room.setPassword(pwd.trim());
        }
        // 保存到数据库
        int dbResult = roomMapper.insert(room);
        // 添加到内存
        connectManager.addRoom(room);
        if (dbResult > 0) {
            sendSystem(session, "房间 [" + room.getName() + "] 添加成功");
            logPublisher.publish("room", null, room.getName(),
                userName + " 通过 WebSocket 添加了房间 [" + room.getName() + "]");
        } else {
            sendSystem(session, "房间添加失败");
        }
    }

    /**
     * 处理：删除房间（type 11）
     * 消息体：{ type: 11, id: 房间ID }
     */
    private void handleRemoveRoom(Session session, Message message) {
        // 用 HTTP Session 登录用户名做权限校验
        String loginUserName = connectManager.getWsLoginUser(SessionUtil.getHttpSessionId(session));
        if (loginUserName == null) {
            sendSystem(session, "登录信息丢失，请刷新页面");
            return;
        }
        if (!"super".equals(loginUserName)) {
            sendSystem(session, "没有权限删除房间");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        Integer roomId = message.getRoomId();
        if (roomId == null) {
            sendSystem(session, "房间ID不能为空");
            return;
        }
        Room room = connectManager.findRoomById(roomId);
        String removedName = room != null ? room.getName() : ("房间#" + roomId);
        // 从内存移除（会广播给所有客户端）
        connectManager.removeRoom(roomId);
        // 从数据库移除
        int dbResult = roomMapper.deleteById(roomId);
        if (dbResult > 0) {
            sendSystem(session, "房间 [" + removedName + "] 已删除");
            logPublisher.publish("room", null, removedName,
                userName + " 通过 WebSocket 删除了房间 [" + removedName + "]");
        } else {
            sendSystem(session, "房间删除失败（数据库中可能不存在）");
        }
    }

    /**
     * 处理：更新房间（type 12）
     * 消息体：{ type: 12, id: 房间ID, name: "新名称", password: "新密码(sha256加密后)" }
     */
    private void handleUpdateRoom(Session session, Message message) {
        // 用 HTTP Session 登录用户名做权限校验
        String loginUserName = connectManager.getWsLoginUser(SessionUtil.getHttpSessionId(session));
        if (loginUserName == null) {
            sendSystem(session, "登录信息丢失，请刷新页面");
            return;
        }
        if (!"super".equals(loginUserName)) {
            sendSystem(session, "没有权限更新房间");
            return;
        }
        String userName = connectManager.getWsUserName(SessionUtil.getHttpSessionId(session));
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        Integer roomId = message.getRoomId();
        if (roomId == null) {
            sendSystem(session, "房间ID不能为空");
            return;
        }
        Room existRoom = connectManager.findRoomById(roomId);
        if (existRoom == null) {
            sendSystem(session, "房间不存在");
            return;
        }
        Room updateRoom = new Room();
        updateRoom.setId(roomId);
        String newName = message.getRoomName();
        if (newName != null && !newName.trim().isEmpty()) {
            updateRoom.setName(newName.trim());
        } else {
            updateRoom.setName(existRoom.getName());
        }
        String pwd = message.getPassword();
        if (pwd != null) {
            updateRoom.setPassword(pwd.trim());
        } else {
            updateRoom.setPassword(existRoom.getPassword());
        }
        // 更新数据库
        int dbResult = roomMapper.updateById(updateRoom);
        updateRoom.setUserNum(existRoom.getUserNum());
        // 更新内存
        connectManager.addRoom(updateRoom);
        if (dbResult > 0) {
            sendSystem(session, "房间 [" + updateRoom.getName() + "] 更新成功");
            logPublisher.publish("room", null, updateRoom.getName(),
                userName + " 通过 WebSocket 更新了房间 [" + updateRoom.getName() + "]");
        } else {
            sendSystem(session, "房间更新失败");
        }
    }

    private void sendSystem(Session session, String text) {
        if (session == null) return;
        Map<String, Object> msg = JsonUtil.newMap();
        msg.put("type", "system");
        msg.put("payload", text);
        // 通过 ConnectManager 的统一入口发送，保证每个 Session 的消息永远串行发送
        ConnectManager.sendToWs(session, JsonUtil.toJson(msg));
    }

    private void sendJson(Session session, Map<String, Object> json) {
        if (session == null || json == null) return;
        // 通过 ConnectManager 的统一入口发送，保证每个 Session 的消息永远串行发送
        ConnectManager.sendToWs(session, JsonUtil.toJson(json));
    }
}