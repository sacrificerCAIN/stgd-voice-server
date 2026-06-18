package com.stgd.voice.ws;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * 系统日志 Netty WebSocket 端点。
 * 原来基于 javax.websocket.Session 的实现已替换为 Netty ChannelGroup。
 * dashboard.html 在 ws://.../ws/system-log 订阅，并由 NettyServer 注册这里的 Channel。
 */
public class SystemLogEndpoint {

    private static final ChannelGroup CHANNELS = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);

    /** 连接建立 / 握手完成时调用 */
    public static void addChannel(Channel ch) {
        if (ch == null) return;
        CHANNELS.add(ch);
    }

    /** 连接关闭时调用 */
    public static void removeChannel(Channel ch) {
        if (ch == null) return;
        CHANNELS.remove(ch);
    }

    /** 广播一条 JSON 日志消息给所有订阅者 */
    public static void broadcast(String json) {
        if (json == null) return;
        // Netty 的 ChannelGroup.writeAndFlush 会自动遍历所有活跃的 channel
        CHANNELS.writeAndFlush(new TextWebSocketFrame(json));
    }
}