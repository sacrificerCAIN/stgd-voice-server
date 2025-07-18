package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Service;

@Service("IdleMessageStrategy")
public class IdleMessageStrategyImpl implements MessagePublishStrategy {
	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		return;
	}
}
