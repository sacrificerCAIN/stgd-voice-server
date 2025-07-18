package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("PrivateMessageStrategy")
public class PrivateMessageStrategyImpl implements MessagePublishStrategy {

	@Autowired
	private ConnectManager connectManager;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		ChannelId SourceUserId = ctx.channel().id();
		String sourceUserIdString = SourceUserId.asLongText();
		connectManager.publishOne(sourceUserIdString, message.getTargetUserId(), message.getPayload());
		connectManager.publishOne(ctx, 1 + "\n");
	}
}
