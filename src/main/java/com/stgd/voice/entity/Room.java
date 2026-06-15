package com.stgd.voice.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.netty.channel.ChannelId;
import lombok.Data;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * @author Hzzz
 */
@Data
@TableName("room")
public class Room {
	private Integer id;

	private String name;

	private String password;

	private Integer userNum;

	@TableField(exist = false)
	private Set<String> userChannelIdSet = new CopyOnWriteArraySet<>();

	public void addUser(ChannelId channelId){
		if (channelId == null) {
			return;
		}
		addUser(channelId.asLongText());
	}

	public void addUser(String channelIdStr){
		if (channelIdStr == null || channelIdStr.isEmpty()) {
			return;
		}
		if (!this.userChannelIdSet.contains(channelIdStr)){
			this.userChannelIdSet.add(channelIdStr);
			if (userNum == null) {
				userNum = 0;
			}
			userNum++;
		}
	}

	public void removeUser(ChannelId channelId){
		if (channelId == null) {
			return;
		}
		removeUser(channelId.asLongText());
	}

	public void removeUser(String channelIdStr){
		if (channelIdStr == null || channelIdStr.isEmpty()) {
			return;
		}
		if (this.userChannelIdSet.remove(channelIdStr)){
			if (userNum == null) {
				userNum = 0;
			}
			if (userNum > 0) {
				userNum--;
			}
		}
	}
}