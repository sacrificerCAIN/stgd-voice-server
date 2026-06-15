package com.stgd.voice.ws;

import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

/**
 * 系统日志统一发布入口。
 * type: login | logout | join | leave | room
 */
@Component
public class SystemLogPublisher {

    public void publish(String type, String userName, String roomName, String message) {
        JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("level", "INFO");
        json.put("userName", userName);
        json.put("roomName", roomName);
        json.put("message", message);
        json.put("timestamp", System.currentTimeMillis());
        SystemLogEndpoint.broadcast(json.toJSONString());
    }
}