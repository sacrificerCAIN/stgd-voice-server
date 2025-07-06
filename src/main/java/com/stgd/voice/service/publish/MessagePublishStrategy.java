package com.stgd.voice.service.publish;

import com.stgd.voice.entity.Message;
import io.netty.channel.ChannelHandlerContext;

public interface MessagePublishStrategy {

	void handleMessage(ChannelHandlerContext ctx, Message message);
}
