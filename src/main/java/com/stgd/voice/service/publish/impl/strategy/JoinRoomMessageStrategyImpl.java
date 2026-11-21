package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.model.entity.Message;
import com.stgd.voice.model.entity.Room;
import com.stgd.voice.model.entity.User;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;


@Service("JoinRoomMessageStrategy")
public class JoinRoomMessageStrategyImpl implements MessagePublishStrategy {

	@Resource
	private ConnectManager connectManager;

	@Resource
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
		String displayName = (userName != null) ? userName : "某用户";

		//如果当前已在某房间中，离开当前房间
		if (roomId != null){
			Room nowRoom = connectManager.findRoomById(roomId);
			if (nowRoom != null) {
				nowRoom.removeUser(userId);
				connectManager.addRoom(nowRoom);
				// 通知旧房间内的 TCP 与 WebSocket 成员
				connectManager.publishRoomSystemWs(roomId, displayName + " 离开了房间", userId.asLongText());
				logPublisher.publish("leave", userName, nowRoom.getName(),
						displayName + " 离开房间 [" + nowRoom.getName() + "]");
			}
		}

		//加入新房间
		targetRoom.addUser(ctx.channel().id());
		connectManager.addRoom(targetRoom);
		// 通知新房间内的 TCP 与 WebSocket 成员
		connectManager.publishRoomSystemWs(targetRoomId, displayName + " 加入了房间", userId.asLongText());
		connectManager.publishOne(ctx, 1 + "\n");
		logPublisher.publish("join", userName, targetRoom.getName(),
				displayName + " 加入房间 [" + targetRoom.getName() + "]");
	}
}