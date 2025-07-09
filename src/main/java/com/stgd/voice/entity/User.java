package com.stgd.voice.entity;

import lombok.Data;

/**
 * @author Hzzz
 */
@Data
public class User {

	private String id;

	private String name;

	private Integer role;

	private Integer joinRoomId;
}
