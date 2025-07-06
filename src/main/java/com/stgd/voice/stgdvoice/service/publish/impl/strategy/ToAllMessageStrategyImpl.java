package com.stgd.voice.stgdvoice.service.publish.impl.strategy;

import com.alibaba.fastjson.JSON;
import com.stgd.voice.stgdvoice.entity.Message;
import com.stgd.voice.stgdvoice.server.component.ConnectManager;
import com.stgd.voice.stgdvoice.service.publish.MessagePublishStrategy;
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
		ctx.writeAndFlush(1 + "\n");
	}
}
