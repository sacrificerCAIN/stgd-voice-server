package com.stgd.voice.server.component;

import com.stgd.voice.entity.Room;
import com.stgd.voice.entity.User;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Hzzz
 */
@Component
public class ConnectManager {

	private static final ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

	private static final ConcurrentHashMap<String, ChannelId> channelWithStringIdMap = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<Integer, Room> roomMap = new ConcurrentHashMap<>();

	public void addClient(ChannelHandlerContext ctx){
		channelGroup.add(ctx.channel());
		channelWithStringIdMap.put(ctx.channel().id().asLongText(), ctx.channel().id());
	}

	public void removeClient(ChannelHandlerContext ctx){
		channelGroup.remove(ctx.channel());
		channelWithStringIdMap.remove(ctx.channel().id().asLongText());
		ctx.channel().close();
	}

	public Channel getChannelByChannelId(String channelIdString){
		ChannelId channelId = channelWithStringIdMap.get(channelIdString);
		return channelGroup.find(channelId);
	}

	public Set<ChannelId> getChannelIdSetByChannelIdStringSet(Set<String> ChannelIdStringSet){
		Set<ChannelId> channelIdSet = new HashSet<>();
		for (String thisChannelIdString : ChannelIdStringSet){
			channelIdSet.add(channelWithStringIdMap.get(thisChannelIdString));
		}
		return channelIdSet;
	}

	public void addUser(User user){
		userMap.put(user.getId(), user);
	}

	public void removeUser(ChannelId id){
		userMap.remove(id.asLongText());
	}

	public User findUserByIdString(String channelIdString){
		return userMap.get(channelIdString);
	}

	public void addRoom(Room room){
		roomMap.put(room.getId(), room);
	}

	public void removeRoom(Integer id){
		roomMap.remove(id);
	}

	public Room findRoomById(Integer id){
		return roomMap.get(id);
	}

	public void publishOne(ChannelHandlerContext ctx, String s){
		ctx.channel().writeAndFlush("[服务器]对你说：" + s + "\n");
	}

	public void publishOne(String targetChannelIdString, String s){
		ChannelId channelId = channelWithStringIdMap.get(targetChannelIdString);
		Channel channel;
		try {
			channel = channelGroup.find(channelId);
		}catch (NullPointerException e){
			return;
		}
		channel.writeAndFlush("[服务器]对你说：" + s + "\n");
	}

	public void publishOne(String sourceChannelIdString, String targetChannelIdString, String s){
		ChannelId channelId = channelWithStringIdMap.get(targetChannelIdString);
		Channel channel;
		try {
			channel = channelGroup.find(channelId);
		}catch (NullPointerException e){
			Channel sourceChannel = getChannelByChannelId(sourceChannelIdString);
			sourceChannel.writeAndFlush("[服务器]对你说：该用户已下线\n");
			return;
		}
		User sourceUser = findUserByIdString(sourceChannelIdString);
		channel.writeAndFlush("[" + sourceUser.getName() + "]对你说：" + s + "\n");
	}

	public void publishAll(String s){
		channelGroup.writeAndFlush(s + "\n");
	}

	public void publishSet(Set<ChannelId> channelIdSet, String s){
		channelGroup.stream()
				.filter(ch -> {
					ChannelId id = ch.id();
					return id != null && channelIdSet.contains(id);
				})
				.forEach(ch -> ch.writeAndFlush(s + "\n"));
	}
}
