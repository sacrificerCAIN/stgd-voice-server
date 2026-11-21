package com.stgd.voice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/protected")
public class ProtectedController {

	@GetMapping("/dashboard")
	public Map<String, Object> dashboard(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		Map<String, Object> response = new HashMap<>();

		// 仅存在会话不够：游客的空会话也会存在，必须要求会话中已写入登录用户名
		String username = (session == null) ? null : (String) session.getAttribute("username");
		if (username == null || username.trim().isEmpty()) {
			response.put("error", "未授权访问");
			return response;
		}

		// 每次访问受保护资源时刷新会话
		session.setMaxInactiveInterval(259200);

		response.put("message", "欢迎访问仪表盘");
		response.put("id", session.getAttribute("id"));
		return response;
	}
}
