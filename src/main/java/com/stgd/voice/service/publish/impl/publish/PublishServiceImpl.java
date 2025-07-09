package com.stgd.voice.service.publish.impl.publish;

import com.stgd.voice.entity.Message;
import com.stgd.voice.service.publish.PublishService;
import io.netty.channel.ChannelId;
import org.springframework.stereotype.Service;

/**
 * @author Hzzz
 */
@Service
public class PublishServiceImpl implements PublishService {
	@Override
	public void publishRoomAll(Integer roomId, Message message) {

	}

	@Override
	public void publishById(ChannelId channelId, Message message) {

	}

	@Override
	public void publishAll(Message message) {

	}
}
