package com.stgd.voice.service.publish;

import com.stgd.voice.entity.Message;
import io.netty.channel.ChannelId;

/**
 * @author Hzzz
 */
public interface PublishService {

	void publishRoomAll(Integer roomId, Message message);

	void publishById(ChannelId channelId, Message message);

	void publishAll(Message message);
}
