package com.stgd.voice.controller;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.entity.AdminUser;
import com.stgd.voice.mapper.AdminUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/login/")
public class LoginController {

	@Resource
	private AdminUserMapper adminUserMapper;

	@RequestMapping("login")
	@ResponseBody
	public Map<String, Object> login(@RequestBody AdminUser adminUser,
	                                 HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		AdminUser loginUser = adminUserMapper.login(adminUser);
		if (loginUser != null){
			// 获取或创建session
			HttpSession session = request.getSession();
			// 设置session属性
			session.setAttribute("id", loginUser.getId());
			session.setAttribute("username", loginUser.getUsername());
			// 显式设置3天过期(可选，因为已在配置中设置)
			session.setMaxInactiveInterval(259200);
			response.put("code", 1);
			response.put("username", loginUser.getUsername());
			return response;
		}else {
			response.put("code", 0);
			return response;
		}
	}


	@GetMapping("checkSession")
	public Map<String, Object> checkSession(HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		// 先尝试获取现有 session，不自动创建
		HttpSession httpSession = request.getSession(false);

		if (httpSession != null) {
			// 每次检查会话时刷新过期时间
			httpSession.setMaxInactiveInterval(259200);
			String username = (String) httpSession.getAttribute("username");
			if (StringUtils.isNotBlank(username)) {
				// 已登录用户
				response.put("isAuthenticated", true);
				response.put("id", httpSession.getAttribute("id"));
				response.put("username", username);
				response.put("sessionId", httpSession.getId());
				// 只有登录用户才是管理员
				response.put("isAdmin", true);
			} else {
				// session 存在但未登录（可能是自动创建的空 session）
				response.put("isAuthenticated", false);
				response.put("sessionId", httpSession.getId());
				response.put("username", null);
				response.put("isAdmin", false);
			}
		} else {
			// 未登录：创建一个新 session，以便前端能正常使用 chat 功能
			HttpSession newSession = request.getSession(true);
			response.put("isAuthenticated", false);
			response.put("sessionId", newSession.getId());
			response.put("username", null);
			response.put("isAdmin", false);
		}

		return response;
	}

	@PostMapping("/logout")
	public Map<String, Object> logout(HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);

		if (session != null) {
			session.invalidate();
			response.put("success", true);
			response.put("message", "登出成功");
		} else {
			response.put("success", false);
			response.put("message", "没有活跃的会话");
		}

		return response;
	}
}