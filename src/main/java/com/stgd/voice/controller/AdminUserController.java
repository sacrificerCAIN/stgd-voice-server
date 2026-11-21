package com.stgd.voice.controller;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.model.entity.AdminUser;
import com.stgd.voice.mapper.AdminUserMapper;
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

	/** 登录/会话/登出与 /login/* 共用同一实现，避免两套逻辑不一致 */
	@Resource
	private LoginController loginController;

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

	@PostMapping("login")
	@ResponseBody
	public Map<String, Object> login(@RequestBody AdminUser adminUser,
	                                 HttpServletRequest request) {
		// 委托给统一登录入口：包含 changeSessionId、会话登记等安全处理
		return loginController.login(adminUser, request);
	}


	@GetMapping("checkSession")
	public Map<String, Object> checkSession(HttpServletRequest request) {
		return loginController.checkSession(request);
	}

	@PostMapping("/logout")
	public Map<String, Object> logout(HttpServletRequest request) {
		return loginController.logout(request);
	}
}