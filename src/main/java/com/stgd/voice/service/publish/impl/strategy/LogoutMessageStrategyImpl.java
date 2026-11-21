package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.model.entity.Message;
import com.stgd.voice.model.entity.Room;
import com.stgd.voice.model.entity.User;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service("LogoutMessageStrategy")
public class LogoutMessageStrategyImpl implements MessagePublishStrategy {

	@Resource
	private ConnectManager connectManager;

	@Resource
	private SystemLogPublisher logPublisher;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		// 先在移除前记录用户名，用于日志
		User user = connectManager.findUserByIdString(ctx.channel().id().asLongText());
		String userName = (user != null) ? user.getName() : null;

		if (message.getRoomId() != null){
			Room room = connectManager.findRoomById(message.getRoomId());
			if (room != null) {
				room.removeUser(ctx.channel().id());
				// 通知房间内剩余成员（TCP + WebSocket）
				connectManager.publishRoomSystemWs(message.getRoomId(),
					(userName != null ? userName : "某用户") + " 离开了房间",
					ctx.channel().id().asLongText());
				if (userName != null) {
					logPublisher.publish("leave", userName, room.getName(),
						userName + " 离开房间 [" + room.getName() + "]");
				}
			}
		}
		connectManager.removeUser(ctx.channel().id());
		connectManager.publishOne(ctx, 1 + "\n");
		connectManager.removeClient(ctx);

		logPublisher.publish("logout", userName, null,
				(userName != null ? userName : "某用户") + " 登出系统");
	}
}