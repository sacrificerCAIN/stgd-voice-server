package com.stgd.voice.stgdvoice.entity;

import io.netty.channel.ChannelId;
import lombok.Data;

import java.util.UUID;

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
