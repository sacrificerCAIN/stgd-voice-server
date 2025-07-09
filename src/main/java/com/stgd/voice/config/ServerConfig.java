package com.stgd.voice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "server")
@Data
public class ServerConfig {

	private Integer port;

	private Integer tcpPort;

	private Integer udpPort;
}
