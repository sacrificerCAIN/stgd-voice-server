package com.stgd.voice.ws;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.server.component.ConnectManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;

@Component
@ServerEndpoint("/ws/chat")
public class ChatEndpoint {

    private static ConnectManager connectManager;

    @Autowired
    public void setConnectManager(ConnectManager cm) {
        connectManager = cm;
    }

    @OnOpen
    public void onOpen(Session session) {
        if (connectManager != null) {
            connectManager.addWsSession(session);
        }
    }

    @OnClose
    public void onClose(Session session) {
        if (connectManager != null) {
            connectManager.removeWsSession(session.getId());
        }
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        if (connectManager != null) {
            connectManager.removeWsSession(session.getId());
        }
    }

    @OnMessage
    public void onMessage(Session session, String text) {
        try {
            Message message = JSON.parseObject(text, Message.class);
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
                default: sendSystem(session, "未知消息类型");
            }
        } catch (Exception e) {
            sendSystem(session, "消息解析失败");
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
        String actualName = connectManager.registerWsUser(session.getId(), userName);
        JSONObject resp = new JSONObject();
        resp.put("type", "login");
        resp.put("userId", session.getId());
        resp.put("userName", actualName);
        sendJson(session, resp);
    }

    private void handleIdle(Session session, Message message) {
        JSONObject resp = new JSONObject();
        resp.put("type", "idle");
        resp.put("timestamp", System.currentTimeMillis());
        sendJson(session, resp);
    }

    private void handleJoinRoom(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(session.getId());
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
        connectManager.joinWsRoom(session.getId(), targetRoomId);
        JSONObject resp = new JSONObject();
        resp.put("type", "join");
        resp.put("roomId", targetRoomId);
        resp.put("roomName", room.getName());
        resp.put("userNum", room.getUserNum());
        sendJson(session, resp);
    }

    private void handleRoomMessage(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(session.getId());
        Integer roomId = connectManager.getWsRoomId(session.getId());
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        if (roomId == null) {
            sendSystem(session, "请先加入房间");
            return;
        }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        connectManager.publishRoomMessage(roomId, userName, payload);
    }

    private void handlePrivateMessage(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(session.getId());
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
        connectManager.sendWsPrivate(session.getId(), targetUserId, payload);
    }

    private void handleBroadcast(Session session, Message message) {
        if (connectManager == null) {
            sendSystem(session, "系统未就绪");
            return;
        }
        String userName = connectManager.getWsUserName(session.getId());
        if (userName == null) {
            sendSystem(session, "请先设置昵称");
            return;
        }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        JSONObject msg = new JSONObject();
        msg.put("type", "broadcast");
        msg.put("userName", userName);
        msg.put("payload", payload);
        msg.put("timestamp", System.currentTimeMillis());
        String text = msg.toJSONString();
        for (Session s : session.getOpenSessions()) {
            if (s.isOpen()) {
                try { s.getBasicRemote().sendText(text); } catch (IOException ignored) {}
            }
        }
    }

    private void handleLogout(Session session) {
        if (connectManager != null) {
            connectManager.removeWsSession(session.getId());
        }
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

    private void sendSystem(Session session, String text) {
        if (session == null || !session.isOpen()) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "system");
            msg.put("payload", text);
            session.getBasicRemote().sendText(msg.toJSONString());
        } catch (IOException ignored) {}
    }

    private void sendJson(Session session, JSONObject json) {
        if (session == null || !session.isOpen() || json == null) return;
        try {
            session.getBasicRemote().sendText(json.toJSONString());
        } catch (IOException ignored) {}
    }
}