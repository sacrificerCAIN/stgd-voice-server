package com.stgd.voice.server.handler.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;

/**
 * @author Hzzz
 */
public class VoiceHandler extends SimpleChannelInboundHandler<DatagramPacket> {
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
		ByteBuf content = packet.content();
		String data = content.toString(CharsetUtil.UTF_8);
		System.out.println("收到来自 " + packet.sender() + " 的语音数据: " + data);
		// 根据需要处理语音数据
	}
}
