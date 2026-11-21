package com.stgd.voice.model.entity;

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

	@TableField(exist = false)
	private Set<String> userChannelIdSet = new CopyOnWriteArraySet<>();

	/**
	 * 在线人数直接由成员集合大小派生，避免 addUser/removeUser 中
	 * contains→add→userNum++ 的竞态；CopyOnWriteArraySet.add 本身是原子去重。
	 * userNum 字段仅保留以兼容原有 setUserNum 调用，不再作为人数来源。
	 */
	public Integer getUserNum() {
		return userChannelIdSet == null ? 0 : userChannelIdSet.size();
	}

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
		// CopyOnWriteArraySet.add 内部加锁并去重，重复加入不会影响 size
		this.userChannelIdSet.add(channelIdStr);
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
		this.userChannelIdSet.remove(channelIdStr);
	}
}