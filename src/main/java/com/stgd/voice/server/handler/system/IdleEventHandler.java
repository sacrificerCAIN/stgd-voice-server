package com.stgd.voice.server.handler.system;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 空闲事件处理器：当 IdleStateHandler 检测到读空闲超过配置时间时，关闭连接。
 */
public class IdleEventHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
    private final String protocol;

    public IdleEventHandler(String protocol) {
        this.protocol = protocol;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                System.out.println("[" + protocol + "] 连接读空闲超时，主动关闭：" + ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
	}
