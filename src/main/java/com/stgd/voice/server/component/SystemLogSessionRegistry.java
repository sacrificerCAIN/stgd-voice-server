package com.stgd.voice.server.component;

import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已登录 HTTP 会话注册表。
 *
 * 背景：/ws/system-log 由独立的 Netty 端口提供服务，拿不到 Spring MVC 的会话对象，
 * 只能从握手请求的 Cookie 中读到 JSESSIONID。登录成功时把会话登记到本类，
 * 握手阶段再通过 {@link #isLoggedIn(String)} 判断该 JSESSIONID 是否仍处于登录状态，
 * 从而阻止游客订阅系统日志。
 *
 * 线程安全：Servlet 线程写入、Netty IO 线程读取，使用 ConcurrentHashMap；
 * HttpSession#getAttribute 在会话已失效时会抛 IllegalStateException，此时顺手移除。
 */
@Component
public class SystemLogSessionRegistry implements HttpSessionListener {

    private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

    /** 登录成功时调用 */
    public void register(HttpSession session) {
        if (session != null) {
            sessions.put(session.getId(), session);
        }
    }

    /** 登出时调用（会话销毁时也会自动触发） */
    public void unregister(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** 校验 JSESSIONID 是否对应一个仍然登录（session 中存在 username）的会话 */
    public boolean isLoggedIn(String sessionId) {
        if (sessionId == null) return false;
        HttpSession session = sessions.get(sessionId);
        if (session == null) return false;
        try {
            Object username = session.getAttribute("username");
            return username instanceof String && !((String) username).trim().isEmpty();
        } catch (IllegalStateException expired) {
            sessions.remove(sessionId);
            return false;
        }
    }

    /** 会话被动过期或被显式销毁时自动清理，避免注册表持有无效会话 */
    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        if (se != null && se.getSession() != null) {
            unregister(se.getSession().getId());
        }
    }
}
