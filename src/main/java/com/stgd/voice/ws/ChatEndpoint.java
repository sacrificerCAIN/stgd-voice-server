package com.stgd.voice.ws;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.server.component.ConnectManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;

/**
 * WebSocket 聊天端点。
 * 客户端 chat.html 通过此端点与房间内其他用户通信。
 * 所有房间/用户/消息都通过 ConnectManager 统一管理，
 * 这样 TCP 客户端(Netty) 与浏览器客户端(WebSocket) 处于同一套房间系统。
 * 消息协议与 Netty TCP 文本通道保持一致：
 *   type: 1登录, 2心跳, 3房间消息, 4私聊, 5全服消息, 6加入房间, 7登出
 */
@Component
@ServerEndpoint("/ws/chat")
public class ChatEndpoint {

    private static ConnectManager connectManager;

    @Autowired
    public void setConnectManager(ConnectManager cm) {
        connectManager = cm;
    }

    // ==================== 连接生命周期 ====================

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
        sendLogoutAck(session);
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
                default: sendSystem(session, "未知消息类型");
            }
        } catch (Exception e) {
            sendSystem(session, "消息解析失败");
        }
    }

    // ==================== 业务处理（全部通过 ConnectManager ====================

    /** 设置昵称 / 登录 */
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

    /** 心跳 */
    private void handleIdle(Session session, Message message) {
        JSONObject resp = new JSONObject();
        resp.put("type", "idle");
        resp.put("timestamp", System.currentTimeMillis());
        sendJson(session, resp);
    }

    /** 加入房间 — 真正写入 ConnectManager 的 Room，更新 userNum */
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
        Room room = connectManager.joinWsRoom(session.getId(), targetRoomId);
        if (room == null) {
            sendSystem(session, "房间不存在");
            return;
        }
        JSONObject resp = new JSONObject();
        resp.put("type", "join");
        resp.put("roomId", targetRoomId);
        resp.put("roomName", room.getName());
        resp.put("userNum", room.getUserNum());
        sendJson(session, resp);
    }

    /** 房间消息 — 通过 ConnectManager 统一推送给房间内所有成员（含 Netty） */
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

    /** 私聊 — 支持发给 WebSocket 或 Netty 客户端 */
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

    /** 全服广播 — 简单推给所有 WebSocket 客户端 */
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
        // 向所有已打开的 WebSocket session 广播
        for (Session s : session.getOpenSessions()) {
            if (s.isOpen()) {
                try { s.getBasicRemote().sendText(text); } catch (IOException ignored) {}
            }
        }
    }

    /** 退出 — 从 ConnectManager 移除，并离开所在房间 */
    private void handleLogout(Session session) {
        if (connectManager != null) {
            connectManager.removeWsSession(session.getId());
        }
        sendLogoutAck(session);
    }

    // ==================== 辅助方法 ====================

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

    private void sendLogoutAck(Session session) {
        if (session == null || !session.isOpen()) return;
        try {
            JSONObject resp = new JSONObject();
            resp.put("type", "logout");
            resp.put("userId", session.getId());
            session.getBasicRemote().sendText(resp.toJSONString());
        } catch (IOException ignored) {}
    }
}