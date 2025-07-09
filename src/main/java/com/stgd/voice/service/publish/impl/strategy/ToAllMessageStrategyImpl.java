package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("ToAllMessageStrategy")
public class ToAllMessageStrategyImpl implements MessagePublishStrategy {

	@Autowired
	private ConnectManager connectManager;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		connectManager.publishAll(message.getPayload());
		connectManager.publishOne(ctx, 1 + "\n");
	}
}
