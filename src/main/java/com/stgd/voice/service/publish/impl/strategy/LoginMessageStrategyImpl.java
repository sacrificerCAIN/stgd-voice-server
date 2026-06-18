package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.User;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service("LoginMessageStrategy")
public class LoginMessageStrategyImpl implements MessagePublishStrategy {

	@Resource
	private ConnectManager connectManager;

	@Resource
	private SystemLogPublisher logPublisher;

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
		connectManager.publishOne(ctx, JsonUtil.toJson(user) + "\n");
		logPublisher.publish("login", user.getName(), null,
				user.getName() + " 登录系统");
	}
}