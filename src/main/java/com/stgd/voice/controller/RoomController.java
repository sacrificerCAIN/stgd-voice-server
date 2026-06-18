package com.stgd.voice.controller;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.ws.SystemLogPublisher;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Controller
@RequestMapping("/room/")
public class RoomController {

	@Resource
	private RoomMapper roomMapper;

	@Resource
	private ConnectManager connectManager;

	@Resource
	private SystemLogPublisher logPublisher;

	/**
	 * 校验是否为已登录用户。只有登录用户（特别是 super 管理员）可进行房间增删改操作。
	 */
	private boolean isNotAdmin(HttpSession session) {
		if (session == null) return true;
		String username = (String) session.getAttribute("username");
		return !StringUtils.isNotBlank(username);
	}

	@PostMapping("insertRoom")
	@ResponseBody
	public Map<String, Object> insertRoom(@RequestBody Room room, HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotAdmin(session)) {
			response.put("success", false);
			response.put("message", "未登录，禁止操作");
			return response;
		}
		int result = roomMapper.insert(room);
		connectManager.addRoom(room);
		connectManager.broadcastRoomList();
		if (result > 0) {
			logPublisher.publish("room", null, room.getName(),
				"新增房间 [" + room.getName() + "]");
			response.put("success", true);
			response.put("affectedRows", result);
		} else {
			response.put("success", false);
			response.put("message", "新增房间失败");
		}
		return response;
	}

	@PostMapping("removeRoom")
	@ResponseBody
	public Map<String, Object> removeRoom(@RequestBody Room room, HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotAdmin(session)) {
			response.put("success", false);
			response.put("message", "未登录，禁止操作");
			return response;
		}
		String removedName = room.getName();
		connectManager.removeRoom(room.getId());
		int result = roomMapper.deleteById(room.getId());
		connectManager.broadcastRoomList();
		connectManager.broadcastAllRoomUsers();
		if (result > 0) {
			logPublisher.publish("room", null, removedName,
				"删除房间 [" + (removedName != null ? removedName : "#" + room.getId()) + "]");
			response.put("success", true);
			response.put("affectedRows", result);
		} else {
			response.put("success", false);
			response.put("message", "删除房间失败");
		}
		return response;
	}

	@PostMapping("updateRoom")
	@ResponseBody
	public Map<String, Object> updateRoom(@RequestBody Room room, HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotAdmin(session)) {
			response.put("success", false);
			response.put("message", "未登录，禁止操作");
			return response;
		}
		int result = roomMapper.updateById(room);
		if (result == 1){
			room.setUserNum(connectManager.findRoomById(room.getId()).getUserNum());
			connectManager.addRoom(room);
			connectManager.broadcastRoomList();
			logPublisher.publish("room", null, room.getName(),
				"更新房间 [" + room.getName() + "]");
			response.put("success", true);
			response.put("affectedRows", result);
		} else {
			response.put("success", false);
			response.put("message", "更新房间失败");
		}
		return response;
	}

	@PostMapping("getAllRoom")
	@ResponseBody
	public List<Room> getAllRoom() {
        return connectManager.getAllRoom();
	}

	/**
	 * 获取指定房间内的在线用户列表（dashboard 下拉使用）。
	 * 入参 JSON: { "id": 房间ID }
	 * 返回: { success, list: [{ userId, userName, ip, firstSeenTs }] }
	 */
	@PostMapping("getRoomOnlineUsers")
	@ResponseBody
	public Map<String, Object> getRoomOnlineUsers(@RequestBody(required = false) Room room,
											  HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotAdmin(session)) {
			response.put("success", false);
			response.put("message", "未登录，禁止操作");
			return response;
		}
		if (room == null || room.getId() == null) {
			response.put("success", false);
			response.put("message", "房间ID不能为空");
			response.put("list", new java.util.ArrayList<>());
			return response;
		}
		response.put("success", true);
		response.put("list", connectManager.getRoomOnlineUsers(room.getId()));
		return response;
	}

	/**
	 * 管理员：强制指定用户下线。
	 * 入参 JSON: { "userId": wsId }
	 * 返回: { success, message }
	 */
	@PostMapping("kickUser")
	@ResponseBody
	public Map<String, Object> kickUser(@RequestBody(required = false) Map<String, Object> payload,
										 HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isNotAdmin(session)) {
			response.put("success", false);
			response.put("message", "未登录，禁止操作");
			return response;
		}
		if (payload == null) {
			response.put("success", false);
			response.put("message", "参数不能为空");
			return response;
		}
		Object userIdObj = payload.get("userId");
		if (userIdObj == null || !(userIdObj instanceof String)) {
			response.put("success", false);
			response.put("message", "userId 参数非法");
			return response;
		}
		String wsId = (String) userIdObj;
		if (wsId.isEmpty()) {
			response.put("success", false);
			response.put("message", "userId 不能为空");
			return response;
		}
		boolean ok = connectManager.kickWsUser(wsId);
		response.put("success", ok);
		response.put("message", ok ? "已下线" : "用户不存在或已下线");
		return response;
	}
}