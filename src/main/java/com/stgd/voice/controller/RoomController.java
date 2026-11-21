package com.stgd.voice.controller;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.annotation.RequireAdminLogin;
import com.stgd.voice.model.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.model.vo.RoomVO;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.ws.SystemLogPublisher;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/room/")
public class RoomController {

	@Resource
	private RoomMapper roomMapper;

	@Resource
	private ConnectManager connectManager;

	@Resource
	private SystemLogPublisher logPublisher;

	@PostMapping("insertRoom")
	@ResponseBody
	@RequireAdminLogin
	public Map<String, Object> insertRoom(@RequestBody Room room) {
		Map<String, Object> response = new HashMap<>();
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
	@RequireAdminLogin
	public Map<String, Object> removeRoom(@RequestBody Room room) {
		Map<String, Object> response = new HashMap<>();
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
	@RequireAdminLogin
	public Map<String, Object> updateRoom(@RequestBody Room room) {
		Map<String, Object> response = new HashMap<>();
		int result = roomMapper.updateById(room);
		if (result == 1) {
			Room cached = connectManager.findRoomById(room.getId());
			if (cached != null) {
				// 保留内存中的在线成员集合，避免更新房间信息后在线人数被清零
				room.setUserChannelIdSet(cached.getUserChannelIdSet());
			}
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
	@RequireAdminLogin
	public Map<String, Object> getAllRoom() {
		Map<String, Object> response = new HashMap<>();
		List<Room> roomList = connectManager.getAllRoom();
		response.put("success", true);
		List<RoomVO> voList = roomList.stream().map(room -> {
			RoomVO vo = new RoomVO();
			vo.setId(room.getId());
			vo.setName(room.getName());
			vo.setUserNum(room.getUserNum());
			return vo;
		}).collect(Collectors.toList());
		response.put("list", voList);
		return response;
	}

	@PostMapping("getRoomOnlineUsers")
	@ResponseBody
	@RequireAdminLogin
	public Map<String, Object> getRoomOnlineUsers(@RequestBody(required = false) Room room) {
		Map<String, Object> response = new HashMap<>();
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

	@PostMapping("kickUser")
	@ResponseBody
	@RequireAdminLogin
	public Map<String, Object> kickUser(@RequestBody(required = false) Map<String, Object> payload) {
		Map<String, Object> response = new HashMap<>();
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
