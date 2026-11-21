package com.stgd.voice.ws;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.springframework.stereotype.Component;

/**
 * 系统日志 Netty WebSocket 端点。
 * dashboard.html 在 ws://.../ws/system-log 订阅，订阅者集合保存在 Spring 单例中，
 * 不再使用 static 全局 ChannelGroup，避免全局状态在测试/多容器场景下串扰。
 */
@Component
public class SystemLogEndpoint {

    private final ChannelGroup channels = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);

    /** 连接建立 / 握手完成时调用 */
    public void addChannel(Channel ch) {
        if (ch == null) return;
        channels.add(ch);
    }

    /** 连接关闭时调用 */
    public void removeChannel(Channel ch) {
        if (ch == null) return;
        channels.remove(ch);
    }

    /** 广播一条 JSON 日志消息给所有订阅者 */
    public void broadcast(String json) {
        if (json == null) return;
        // Netty 的 ChannelGroup.writeAndFlush 会自动遍历所有活跃的 channel
        channels.writeAndFlush(new TextWebSocketFrame(json));
    }
}
