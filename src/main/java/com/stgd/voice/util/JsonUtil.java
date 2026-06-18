package com.stgd.voice.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一的 JSON 工具类，基于 Jackson 实现。
 *
 * 提供以下核心能力：
 *   1. toJson(obj)         —— 将任意 Java 对象序列化为 JSON 字符串
 *   2. parse(json, Class)   —— 将 JSON 字符串反序列化为指定类型
 *   3. newMap()             —— 创建一个按插入顺序的 Map（替代 fastjson 的 new JSONObject()）
 *
 * 说明：
 *   - ObjectMapper 是线程安全的，可全局共享
 *   - newMap() 返回 LinkedHashMap，保证 put 顺序与最终 JSON 输出顺序一致
 *   - 使用 Jackson 作为 Spring Boot 原生 JSON 库，无需额外依赖
 */
public final class JsonUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    private JsonUtil() {}

    /** 序列化：Java 对象 → JSON 字符串 */
    public static String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** 反序列化：JSON 字符串 → 指定类型的对象 */
    public static <T> T parse(String json, Class<T> clazz) throws JsonProcessingException {
        return mapper.readValue(json, clazz);
    }

    /** 创建一个按插入顺序的 Map（替代 fastjson 的 new JSONObject()） */
    public static Map<String, Object> newMap() {
        return new LinkedHashMap<>();
    }
}