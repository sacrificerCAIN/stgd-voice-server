package com.stgd.voice.stgdvoice.service.publish;

import com.stgd.voice.stgdvoice.entity.Message;
import io.netty.channel.ChannelHandlerContext;

public interface MessagePublishStrategy {

	void handleMessage(ChannelHandlerContext ctx, Message message);
}
