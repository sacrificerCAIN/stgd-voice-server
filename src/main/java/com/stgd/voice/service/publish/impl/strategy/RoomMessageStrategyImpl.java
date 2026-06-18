package com.stgd.voice.service.publish.impl.strategy;

import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.service.publish.MessagePublishStrategy;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Set;

@Service("RoomMessageStrategy")
public class RoomMessageStrategyImpl implements MessagePublishStrategy {

	@Resource
	private ConnectManager connectManager;

	@Override
	public void handleMessage(ChannelHandlerContext ctx, Message message) {
		Integer roomId = message.getRoomId();
		Room room = connectManager.findRoomById(roomId);
		if (room == null) {
			connectManager.publishOne(ctx, "房间不存在");
			return;
		}
		// 从用户映射中查找发送者用户名（TCP 客户端通过 type=1 登录消息注册）
		String senderId = ctx.channel().id().asLongText();
		com.stgd.voice.entity.User sender = connectManager.findUserByIdString(senderId);
		String userName = (sender != null && sender.getName() != null) ? sender.getName() : "未知";
		// 统一入口：同时推送给 Netty TCP 客户端和 WebSocket 浏览器客户端
		connectManager.publishRoomMessage(roomId, userName, message.getPayload());
		connectManager.publishOne(ctx, 1 + "\n");
	}
}