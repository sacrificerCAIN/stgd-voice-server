package com.stgd.voice.controller;

import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
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

	@PostMapping("insertRoom")
	@ResponseBody
	public Integer insertRoom(@RequestBody Room room) {
		connectManager.addRoom(room);
		return RoomMapper.insert(room);
	}

	@PostMapping("removeRoom")
	@ResponseBody
	public Integer removeRoom(@RequestBody Room room) {
		connectManager.removeRoom(room.getId());
		return RoomMapper.deleteById(room.getId());
	}

	@PostMapping("updateRoom")
	@ResponseBody
	public Integer updateRoom(@RequestBody Room room) {
		return RoomMapper.updateById(room);
	}

	@PostMapping("getAllRoom")
	@ResponseBody
	public List<Room> getAllRoom() {
		List<Room> roomList = connectManager.getAllRoom();
		return roomList;
	}
}
