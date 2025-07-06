package com.stgd.voice.stgdvoice.service.publish;

import com.stgd.voice.stgdvoice.entity.Message;
import io.netty.channel.ChannelId;

/**
 * @author Hzzz
 */
public interface PublishService {

	void publishRoomAll(Integer roomId, Message message);

	void publishById(ChannelId channelId, Message message);

	void publishAll(Message message);
}
