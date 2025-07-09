package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Service;

@Service("ExceptionMessageStrategy")
public class ExceptionMessageStrategyImpl implements MessagePublishStrategy {
	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		ctx.writeAndFlush("你在说啥我听不懂\n");
	}
}
