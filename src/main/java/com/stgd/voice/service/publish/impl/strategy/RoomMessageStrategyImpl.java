package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service("RoomMessageStrategy")
public class RoomMessageStrategyImpl implements MessagePublishStrategy {

	@Autowired
	private ConnectManager connectManager;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		Room room = connectManager.findRoomById(message.getRoomId());

		Set<ChannelId> channelIdSet = connectManager.getChannelIdSetByChannelIdStringSet(room.getUserChannelIdSet());
		connectManager.publishSet(channelIdSet, message.getPayload());
		connectManager.publishOne(ctx, 1 + "\n");
	}
}
