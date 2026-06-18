package com.stgd.voice.util;

import javax.servlet.http.HttpSession;
import javax.websocket.Session;

public class SessionUtil {

    public static String getHttpSessionId(Session session) {
        HttpSession httpSession = (HttpSession) session.getUserProperties().get(HttpSession.class.getName());
        String httpSessionId = null;
        if (httpSession != null) {
            httpSessionId = httpSession.getId();
        }
        return httpSessionId;
    }

}
