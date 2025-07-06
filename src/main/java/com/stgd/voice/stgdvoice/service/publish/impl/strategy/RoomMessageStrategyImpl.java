package com.stgd.voice.stgdvoice.service.publish.impl.strategy;

import com.alibaba.fastjson.JSON;
import com.stgd.voice.stgdvoice.entity.Message;
import com.stgd.voice.stgdvoice.entity.Room;
import com.stgd.voice.stgdvoice.server.component.ConnectManager;
import com.stgd.voice.stgdvoice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
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
		ctx.writeAndFlush(1 + "\n");
	}
}
