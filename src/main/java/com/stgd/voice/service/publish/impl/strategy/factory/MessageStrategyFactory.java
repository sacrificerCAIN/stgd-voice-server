package com.stgd.voice.service.publish.impl.strategy.factory;

import com.stgd.voice.service.publish.MessagePublishStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class MessageStrategyFactory {

	/** 消息类型 -> 策略 Bean 名称（实例级不可变表，不再使用 static 全局容器） */
	private final Map<Integer, String> strategyMap = buildStrategyMap();

	private static Map<Integer, String> buildStrategyMap() {
		Map<Integer, String> map = new HashMap<>();
		map.put(1, "LoginMessageStrategy");
		map.put(2, "IdleMessageStrategy");
		map.put(3, "RoomMessageStrategy");
		map.put(4, "PrivateMessageStrategy");
		map.put(5, "ToAllMessageStrategy");
		map.put(6, "JoinRoomMessageStrategy");
		map.put(7, "LogoutMessageStrategy");
		return Collections.unmodifiableMap(map);
	}

	@Resource
	private ApplicationContext applicationContext;

	public MessagePublishStrategy getStrategyByMessage(Integer messageType) {
		String beanName = strategyMap.get(messageType);
		if (beanName == null) {
			return (MessagePublishStrategy) applicationContext.getBean("ExceptionMessageStrategy");
		}
		return (MessagePublishStrategy) applicationContext.getBean(beanName);
	}
}
