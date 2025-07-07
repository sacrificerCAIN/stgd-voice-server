package com.stgd.voice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stgd.voice.entity.Room;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 1. @description:  
 2. @author: zrj
 3. @time: 2025/7/6
 */
@Mapper
public interface RoomMapper extends BaseMapper<Room> {
	 List<Room> selectAll();
}
