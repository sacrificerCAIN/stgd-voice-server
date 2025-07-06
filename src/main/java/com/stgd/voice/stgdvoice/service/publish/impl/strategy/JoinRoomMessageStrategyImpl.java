package com.stgd.voice.stgdvoice.service.publish.impl.strategy;

import com.alibaba.fastjson.JSON;
import com.stgd.voice.stgdvoice.entity.Message;
import com.stgd.voice.stgdvoice.entity.Room;
import com.stgd.voice.stgdvoice.server.component.ConnectManager;
import com.stgd.voice.stgdvoice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service("JoinRoomMessageStrategy")
public class JoinRoomMessageStrategyImpl implements MessagePublishStrategy {

	@Autowired
	private ConnectManager connectManager;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		Integer roomId = message.getRoomId();
		Room room = connectManager.findRoomById(roomId);
		if (room == null){
			ctx.writeAndFlush("房间不存在\n");
			return;
		}
		room.addUser(ctx.channel().id());
		ctx.writeAndFlush(1 + "\n");
	}
}
