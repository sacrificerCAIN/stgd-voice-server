package com.stgd.voice.controller;

import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.ws.SystemLogPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/room/")
public class RoomController {

	@Autowired
	private RoomMapper RoomMapper;

	@Autowired
	private ConnectManager connectManager;

	@Autowired
	private SystemLogPublisher logPublisher;

	@PostMapping("insertRoom")
	@ResponseBody
	public Integer insertRoom(@RequestBody Room room) {
		Integer result = RoomMapper.insert(room);
		connectManager.addRoom(room);
		connectManager.broadcastRoomList();
		if (result > 0) {
			logPublisher.publish("room", null, room.getName(),
				"新增房间 [" + room.getName() + "]");
		}
		return result;
	}

	@PostMapping("removeRoom")
	@ResponseBody
	public Integer removeRoom(@RequestBody Room room) {
		String removedName = room.getName();
		connectManager.removeRoom(room.getId());
		Integer result = RoomMapper.deleteById(room.getId());
		connectManager.broadcastRoomList();
		connectManager.broadcastAllRoomUsers();
		if (result > 0) {
			logPublisher.publish("room", null, removedName,
				"删除房间 [" + (removedName != null ? removedName : "#" + room.getId()) + "]");
		}
		return result;
	}

	@PostMapping("updateRoom")
	@ResponseBody
	public Integer updateRoom(@RequestBody Room room) {
		int result = RoomMapper.updateById(room);
		if (result == 1){
			room.setUserNum(connectManager.findRoomById(room.getId()).getUserNum());
			connectManager.addRoom(room);
			connectManager.broadcastRoomList();
			logPublisher.publish("room", null, room.getName(),
				"更新房间 [" + room.getName() + "]");
		}
		return result;
	}

	@PostMapping("getAllRoom")
	@ResponseBody
	public List<Room> getAllRoom() {
        return connectManager.getAllRoom();
	}
}