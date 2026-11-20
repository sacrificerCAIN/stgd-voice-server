package com.stgd.voice.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author Hzzz
 */
@Data
@TableName("user")
public class User {

	private String id;

	private String userName;

	private Integer role;

	private String name;

	private Integer joinRoomId;
}
