package com.stgd.voice.ws;

import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 系统日志 WebSocket 端点。
 * dashboard.html 仪表盘订阅 /ws/system-log 接收实时事件
 */
@Component
@ServerEndpoint("/ws/system-log")
public class SystemLogEndpoint {

    private static final Set<Session> SESSIONS = new CopyOnWriteArraySet<>();

    @OnOpen
    public void onOpen(Session session) {
        SESSIONS.add(session);
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        SESSIONS.remove(session);
    }

    /**
     * 广播一条 JSON 日志消息给所有订阅者
     */
    public static void broadcast(String json) {
        if (json == null) {
            return;
        }
        for (Session session : SESSIONS) {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(json);
                } catch (IOException ignored) {
                    // 忽略单次发送失败
                }
            }
        }
    }
}