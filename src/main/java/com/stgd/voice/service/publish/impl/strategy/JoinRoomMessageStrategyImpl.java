package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.entity.User;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service("JoinRoomMessageStrategy")
public class JoinRoomMessageStrategyImpl implements MessagePublishStrategy {

	@Autowired
	private ConnectManager connectManager;

	@Autowired
	private SystemLogPublisher logPublisher;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		Integer roomId = message.getRoomId();
		Integer targetRoomId = message.getTargetRoomId();
		ChannelId userId = ctx.channel().id();
		Room targetRoom = connectManager.findRoomById(targetRoomId);
		if (targetRoom == null){
			ctx.writeAndFlush("房间不存在\n");
			return;
		}

		User user = connectManager.findUserByIdString(userId.asLongText());
		String userName = (user != null) ? user.getName() : null;

		//如果当前已在某房间中，离开当前房间
		if (roomId != null){
			Room nowRoom = connectManager.findRoomById(roomId);
			nowRoom.removeUser(userId);
			connectManager.addRoom(nowRoom);
			logPublisher.publish("leave", userName, nowRoom.getName(),
					(userName != null ? userName : "某用户")
						+ " 离开房间 [" + nowRoom.getName() + "]");
		}

		//加入新房间
		targetRoom.addUser(ctx.channel().id());
		connectManager.addRoom(targetRoom);
		connectManager.publishOne(ctx, 1 + "\n");
		logPublisher.publish("join", userName, targetRoom.getName(),
				(userName != null ? userName : "某用户")
					+ " 加入房间 [" + targetRoom.getName() + "]");
	}
}