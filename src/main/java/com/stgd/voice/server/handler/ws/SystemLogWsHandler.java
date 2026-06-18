package com.stgd.voice.server.handler.ws;

import com.stgd.voice.ws.SystemLogEndpoint;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * 系统日志订阅（/ws/system-log）：客户端连接后立刻被加入 ChannelGroup，
 * 每当 SystemLogPublisher 广播一条日志时自动下发。
 */
public class SystemLogWsHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SystemLogEndpoint.addChannel(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        SystemLogEndpoint.removeChannel(ctx.channel());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            // 握手完成后客户端正式订阅
            SystemLogEndpoint.addChannel(ctx.channel());
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        // 不处理上行消息；忽略
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        SystemLogEndpoint.removeChannel(ctx.channel());
        ctx.close();
    }
}
