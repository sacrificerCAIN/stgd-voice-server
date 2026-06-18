package com.stgd.voice.controller;

import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.ws.SystemLogPublisher;
import org.springframework.beans.factory.annotation.Autowired;
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
	private boolean isAdmin(HttpSession session) {
		if (session == null) return true;
		String username = (String) session.getAttribute("username");
		return username == null || username.trim().isEmpty();
	}

	@PostMapping("insertRoom")
	@ResponseBody
	public Map<String, Object> insertRoom(@RequestBody Room room, HttpServletRequest request) {
		Map<String, Object> response = new HashMap<>();
		HttpSession session = request.getSession(false);
		if (isAdmin(session)) {
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
		if (isAdmin(session)) {
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
		if (isAdmin(session)) {
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
}