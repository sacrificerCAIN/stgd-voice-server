package com.stgd.voice.server.handler.text;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.stgd.voice.entity.Message;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.impl.strategy.factory.MessageStrategyFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.stereotype.Component;


/**
 * @author Hzzz
 */
@Component
public class TextHandler extends SimpleChannelInboundHandler<String> {

	private final  ConnectManager connectManager;

	private final  MessageStrategyFactory messageStrategyFactory;

	public TextHandler(ConnectManager connectManager, MessageStrategyFactory messageStrategyFactory) {
		this.connectManager = connectManager;
		this.messageStrategyFactory = messageStrategyFactory;
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
			Message message = JSON.parseObject(messageString, Message.class);
			messageStrategyFactory.getStrategyByMessage(message.getType()).handleMessage(ctx, message);
		}catch (JSONException jsonException){
			connectManager.publishOne("服务器" ,channelId.asLongText(), "你在说啥我听不懂\n");
		}
		catch (Exception e){
			e.printStackTrace();
		}
	}
}
