package com.stgd.voice.service.publish.impl.strategy.factory;

import com.stgd.voice.service.publish.MessagePublishStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MessageStrategyFactory {

	private static final Map<Integer, String> STRATEGY_MAP = new HashMap<>();

	static {
		STRATEGY_MAP.put(1, "LoginMessageStrategy");
		STRATEGY_MAP.put(2, "IdleMessageStrategy");
		STRATEGY_MAP.put(3, "RoomMessageStrategy");
		STRATEGY_MAP.put(4, "PrivateMessageStrategy");
		STRATEGY_MAP.put(5, "ToAllMessageStrategy");
		STRATEGY_MAP.put(6, "JoinRoomMessageStrategy");
		STRATEGY_MAP.put(7, "LogoutMessageStrategy");
	}

	@Autowired
	private ApplicationContext applicationContext;

	public MessagePublishStrategy getStrategyByMessage(Integer messageType) {
		String beanName = STRATEGY_MAP.get(messageType);
		if (beanName == null) {
			return (MessagePublishStrategy) applicationContext.getBean("ExceptionMessageStrategy");
		}
		return (MessagePublishStrategy) applicationContext.getBean(beanName);
	}
}
