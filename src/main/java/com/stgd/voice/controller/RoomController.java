package com.stgd.voice.controller;

import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/room/")
public class RoomController {

	@Autowired
	private RoomMapper RoomMapper;

	@PostMapping("insertRoom")
	@ResponseBody
	public Integer insertRoom(@RequestBody Room Room) {
		return RoomMapper.insert(Room);
	}

	@PostMapping("removeRoom")
	@ResponseBody
	public Integer removeRoom(@RequestBody Room Room) {
		return RoomMapper.deleteById(Room.getId());
	}

	@PostMapping("updateRoom")
	@ResponseBody
	public Integer updateRoom(@RequestBody Room Room) {
		return RoomMapper.updateById(Room);
	}

	@PostMapping("getAllRoom")
	@ResponseBody
	public List<Room> getAllRoom(@RequestBody Room Room) {
		return RoomMapper.selectAll();
	}
}
