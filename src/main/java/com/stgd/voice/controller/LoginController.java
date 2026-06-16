package com.stgd.voice.controller;

import com.stgd.voice.Util.SessionUtil;
import com.stgd.voice.entity.AdminUser;
import com.stgd.voice.mapper.AdminUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/login/")
public class LoginController {

	@Autowired
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
		HttpSession httpSession = request.getSession(false);

		if (httpSession != null) {
			// 每次检查会话时刷新过期时间
			httpSession.setMaxInactiveInterval(259200);
			response.put("isAuthenticated", true);
			response.put("id", httpSession.getAttribute("id"));
			String username = (String) httpSession.getAttribute("username");
			response.put("username", username);
			response.put("sessionId", httpSession.getId());
			// 只有 super 用户拥有房间增删改权限
			response.put("isAdmin", "super".equals(username));
		} else {
			response.put("isAuthenticated", false);
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