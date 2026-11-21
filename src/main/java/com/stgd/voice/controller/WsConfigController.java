package com.stgd.voice.controller;

import com.stgd.voice.config.ServerConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 前端连接配置接口。
 *
 * WebSocket 由独立的 Netty 端口提供服务，前端启动时先向本接口询问端口，
 * 不再在 HTML 中硬编码，避免与 application.yml 的 server.ws-port 各改各的。
 *
 * 返回 port=0 表示使用同源地址（例如 Nginx 反向代理 /ws 的场景），
 * 此时前端直接连接当前页面的 host，HTTPS 下自动使用 wss。
 */
@RestController
@RequestMapping("/ws/")
public class WsConfigController {

	@Resource
	private ServerConfig serverConfig;

	@GetMapping("config")
	public Map<String, Object> config() {
		Map<String, Object> response = new HashMap<>();
		Integer port = serverConfig.getWsPort();
		// 未配置或配置非法时按同源处理
		response.put("port", (port == null || port <= 0) ? 0 : port);
		return response;
	}
}
