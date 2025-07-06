package com.stgd.voice.service.publish.impl.strategy;

import com.alibaba.fastjson.JSON;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.User;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("LoginMessageStrategy")
public class LoginMessageStrategyImpl implements MessagePublishStrategy {

	@Autowired
	private ConnectManager connectManager;
	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		User user = new User();
		user.setId(ctx.channel().id().asLongText());
		if (message.getUserName() == null){
			ctx.writeAndFlush("用户名不能为空" + "\n");
			return;
		}
		user.setName(message.getUserName());
		connectManager.addUser(user);
		ctx.writeAndFlush(JSON.toJSONString(user) + "\n");
	}
}
