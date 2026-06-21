package com.stgd.voice.server.handler.text;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.entity.Message;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.server.component.IpBlacklistManager;
import com.stgd.voice.service.publish.impl.strategy.factory.MessageStrategyFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;


/**
 * @author Hzzz
 */
@Component
public class TextHandler extends SimpleChannelInboundHandler<String> {

	private final ConnectManager connectManager;

	private final MessageStrategyFactory messageStrategyFactory;

	@Autowired(required = false)
	private IpBlacklistManager blacklistManager;

	public TextHandler(ConnectManager connectManager, MessageStrategyFactory messageStrategyFactory) {
		this.connectManager = connectManager;
		this.messageStrategyFactory = messageStrategyFactory;
	}

	private boolean isBlacklisted(ChannelHandlerContext ctx) {
		return blacklistManager != null && blacklistManager.isBlack(ctx);
	}

	/**
	 * @description
	 * channel开启回调函数
	 * @author zrj
	 * @date 2023-09-04 14:15:47
	 * @param ctx
	 * @return void
	 **/
	@Override
	public void channelActive(ChannelHandlerContext ctx){
		if (isBlacklisted(ctx)) {
			String ip = IpBlacklistManager.getChannelIp(ctx.channel());
			ctx.writeAndFlush("IP " + (ip == null ? "" : ip) + " 已被加入黑名单，连接已被拒绝\n")
				.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
			return;
		}
		connectManager.addClient(ctx);
	}

	/**
	 * @description
	 * channel关闭回调函数
	 * @author zrj
	 * @date 2023-09-04 14:15:47
	 * @param ctx
	 * @return void
	 **/
	@Override
	public void channelInactive(ChannelHandlerContext ctx){
		connectManager.removeClient(ctx);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, String messageString){
		ChannelId channelId = ctx.channel().id();
		try {
			Message message = JsonUtil.parse(messageString, Message.class);
			messageStrategyFactory.getStrategyByMessage(message.getType()).handleMessage(ctx, message);
		} catch (JsonProcessingException jsonException) {
			connectManager.publishOne(channelId.asLongText(), "你在说啥我听不懂\n");
		}
		catch (Exception e){
			e.printStackTrace();
		}
	}
}