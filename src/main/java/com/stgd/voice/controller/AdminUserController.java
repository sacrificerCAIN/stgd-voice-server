package com.stgd.voice.controller;

import com.stgd.voice.entity.AdminUser;
import com.stgd.voice.mapper.AdminUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/adminUser/")
public class AdminUserController {

	@Autowired
	private AdminUserMapper adminUserMapper;

	@PostMapping("insertAdminUser")
	public Integer insertAdminUser(@RequestBody AdminUser adminUser) {
		return adminUserMapper.insert(adminUser);
	}

	@PostMapping("removeAdminUser")
	public Integer removeAdminUser(@RequestParam Integer id) {
		return adminUserMapper.deleteById(id);
	}

	@PostMapping("updateAdminUser")
	public Integer updateAdminUser(@RequestBody AdminUser adminUser) {
		return adminUserMapper.updateById(adminUser);
	}

	@PostMapping("getAllAdminUser")
	public List<AdminUser> getAllAdminUser(@RequestBody AdminUser adminUser) {
		return adminUserMapper.selectAll();
	}

	@RequestMapping("login")
	@ResponseBody
	public Integer login(@RequestBody AdminUser adminUser) {
		if (adminUserMapper.login(adminUser) > 0){
			return 1;
		}else {
			return 0;
		}
	}
}
