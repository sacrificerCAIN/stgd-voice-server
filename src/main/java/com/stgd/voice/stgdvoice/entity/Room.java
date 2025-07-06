package com.stgd.voice.stgdvoice.entity;

import io.netty.channel.ChannelId;
import lombok.Data;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * @author Hzzz
 */
@Data
public class Room {
	private Integer id;

	private String name;

	private Set<String> userChannelIdSet = new CopyOnWriteArraySet<>();

	public void addUser(ChannelId channelId){
		this.userChannelIdSet.add(channelId.asLongText());
	}

	public void removeUser(ChannelId channelId){
		this.userChannelIdSet.remove(channelId.asLongText());
	}
}
