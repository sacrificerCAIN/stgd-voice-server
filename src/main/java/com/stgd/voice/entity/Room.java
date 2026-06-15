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
		String channelIdStr = channelId.asLongText();
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
		if (this.userChannelIdSet.remove(channelId.asLongText())){
			if (userNum == null) {
				userNum = 0;
			}
			if (userNum > 0) {
				userNum--;
			}
		}
	}
}