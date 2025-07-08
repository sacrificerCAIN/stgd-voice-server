package com.stgd.voice.entity;

import lombok.Data;

import java.util.UUID;

/**
 * @author Hzzz
 */
@Data
public class Message {

	/**
	 * 唯一id
	 */
	private UUID id;
	/**
	 * 消息对应用户id,就是channelid
	 */
	private String userId;
	/**
	 * 房间id
	 */
	private Integer roomId;
	/**
	 * 用户名
	 */
	private String userName;
	/**
	 * 1.登录消息 2.心跳消息 3.房间消息 4.私聊消息 5.全服消息 6.加入房间消息 7.登出消息
	 */
	private Integer type;
	/**
	 * 私聊对象id
	 */
	private String targetUserId;
	/**
	 * 私聊对象id
	 */
	private Integer targetRoomId;
	/**
	 * 消息载荷
	 */
	private String payload;
}
