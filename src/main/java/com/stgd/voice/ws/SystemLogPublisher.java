package com.stgd.voice.ws;

import com.stgd.voice.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 系统日志统一发布入口。
 * type: login | logout | join | leave | room
 */
@Component
public class SystemLogPublisher {

    public void publish(String type, String userName, String roomName, String message) {
        Map<String, Object> json = JsonUtil.newMap();
        json.put("type", type);
        json.put("level", "INFO");
        json.put("userName", userName);
        json.put("roomName", roomName);
        json.put("message", message);
        json.put("timestamp", System.currentTimeMillis());
        SystemLogEndpoint.broadcast(JsonUtil.toJson(json));
    }
}