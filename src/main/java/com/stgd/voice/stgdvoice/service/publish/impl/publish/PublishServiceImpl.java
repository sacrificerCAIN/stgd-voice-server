package com.stgd.voice.stgdvoice.service.publish.impl.publish;

import com.stgd.voice.stgdvoice.entity.Message;
import com.stgd.voice.stgdvoice.service.publish.PublishService;
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
