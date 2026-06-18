package com.stgd.voice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "server")
@Data
public class ServerConfig {

	private Integer port;

	private String host;

	private Integer tcpPort;

	private Integer udpPort;

	private Integer wsPort;

	/**
	 * TCP/WebSocket 连接读空闲超时时间（秒）。超过此时间未收到客户端数据则关闭连接。
	 * 默认 60 秒；设置为 0 或负数表示不启用。
	 */
	private Integer idleTimeoutSeconds = 60;
}