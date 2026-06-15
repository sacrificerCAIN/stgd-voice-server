package com.stgd.voice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * 启用 JSR-356 (javax.websocket) 端点自动注册，
 * 使 Spring 容器能识别 @ServerEndpoint 注解的 Bean。
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}