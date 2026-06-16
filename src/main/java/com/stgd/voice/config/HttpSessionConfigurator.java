package com.stgd.voice.config;

import javax.servlet.http.HttpSession;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

public class HttpSessionConfigurator extends ServerEndpointConfig.Configurator {
    @Override
    public void modifyHandshake(ServerEndpointConfig config,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        // 从握手请求中获取HttpSession
        HttpSession httpSession = (HttpSession) request.getHttpSession();
        // 存入userProperties，key为HttpSession类全限定名，和你取值代码对应
        config.getUserProperties().put(HttpSession.class.getName(), httpSession);
    }
}