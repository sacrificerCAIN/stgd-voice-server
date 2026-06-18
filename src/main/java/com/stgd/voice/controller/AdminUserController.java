package com.stgd.voice.controller;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.entity.AdminUser;
import com.stgd.voice.mapper.AdminUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/adminUser/")
public class AdminUserController {

	@Resource
	private AdminUserMapper adminUserMapper;

	/** 校验是否不是 super 管理员。未登录或非 super 账号返回 true，需拒绝操作。 */
	private boolean isNotSuper(HttpSession session) {
		if (session == null) return true;
		String username = (String) session.getAttribute("username");
		return !"super".equals(username);
	}

	@PostMapping("insertAdminUser")
	public Map<String, Object> insertAdminUser(@RequestBody AdminUser adminUser,
	                                            HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotSuper(session)) {
			response.put("success", false);
			response.put("message", "仅 super 管理员可添加账号");
			return response;
		}
		if (adminUser == null || StringUtils.isBlank(adminUser.getUsername())
				|| StringUtils.isBlank(adminUser.getPassword())) {
			response.put("success", false);
			response.put("message", "用户名和密码不能为空");
			return response;
		}
		int result = adminUserMapper.insert(adminUser);
		if (result > 0) {
			response.put("success", true);
			response.put("affectedRows", result);
		} else {
			response.put("success", false);
			response.put("message", "添加失败");
		}
		return response;
	}

	@PostMapping("removeAdminUser")
	public Map<String, Object> removeAdminUser(@RequestParam Integer id,
	                                            HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotSuper(session)) {
			response.put("success", false);
			response.put("message", "仅 super 管理员可删除账号");
			return response;
		}
		if (id == null) {
			response.put("success", false);
			response.put("message", "账号ID不能为空");
			return response;
		}
		if (id == 1) {
			response.put("success", false);
			response.put("message", "禁止删除 super 主账号");
			return response;
		}
		int result = adminUserMapper.deleteById(id);
		response.put("success", result > 0);
		response.put("affectedRows", result);
		return response;
	}

	@PostMapping("updateAdminUser")
	public Map<String, Object> updateAdminUser(@RequestBody AdminUser adminUser,
	                                            HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotSuper(session)) {
			response.put("success", false);
			response.put("message", "仅 super 管理员可修改账号");
			return response;
		}
		if (adminUser == null || adminUser.getId() == null) {
			response.put("success", false);
			response.put("message", "账号ID不能为空");
			return response;
		}
		if (adminUser.getId() == 1) {
			response.put("success", false);
			response.put("message", "禁止修改 super 主账号");
			return response;
		}
		int result = adminUserMapper.updateById(adminUser);
		response.put("success", result > 0);
		response.put("affectedRows", result);
		return response;
	}

	@PostMapping("getAllAdminUser")
	public Map<String, Object> getAllAdminUser(HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotSuper(session)) {
			response.put("success", false);
			response.put("message", "仅 super 管理员可查看账号列表");
			return response;
		}
		List<AdminUser> rawList = adminUserMapper.selectAll();
		// 不返回密码字段
		if (rawList != null) {
			for (AdminUser user : rawList) {
				user.setPassword(null);
			}
		}
		response.put("success", true);
		response.put("list", rawList == null ? new ArrayList<>() : rawList);
		return response;
	}

	@RequestMapping("login")
	@ResponseBody
	public Map<String, Object> login(@RequestBody AdminUser adminUser,
	                                 HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		AdminUser loginUser = adminUserMapper.login(adminUser);
		if (loginUser != null){
			// 获取或创建session
			HttpSession session = request.getSession();
			// 登录后切换 sessionId，防范会话固定攻击
			try {
				request.changeSessionId();
			} catch (Exception ignored) { /* 旧版本 Servlet 可能不支持，忽略 */ }
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
			response.put("message", "用户名或密码错误");
			return response;
		}
	}


	@GetMapping("checkSession")
	public Map<String, Object> checkSession(HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);

		if (session != null) {
			// 每次检查会话时刷新过期时间
			session.setMaxInactiveInterval(259200);
			response.put("isAuthenticated", true);
			response.put("id", session.getAttribute("id"));
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