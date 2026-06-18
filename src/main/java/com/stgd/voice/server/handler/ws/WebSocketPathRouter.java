package com.stgd.voice.server.handler.ws;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * 单一端口上根据 URI 路径分派到不同的 WebSocket 子协议处理器。
 * - /ws/chat       -> ChatWsHandler
 * - /ws/system-log -> SystemLogWsHandler
 *
 * 工作方式：
 *   读取第一个 HTTP 请求（升级请求），根据 uri 动态地把对应 WebSocket 处理器
 *   注入到 pipeline 中；然后把请求继续交给协议处理器完成升级。
 *   自己会在注入完成后从 pipeline 里移除（self-remove）。
 */
public class WebSocketPathRouter extends ChannelInboundHandlerAdapter {

    private final ChatWsHandler chatHandler;
    private final SystemLogWsHandler logHandler;
    private final Runnable installChatProtocol;
    private final Runnable installLogProtocol;

    public WebSocketPathRouter(ChatWsHandler chatHandler,
                               SystemLogWsHandler logHandler,
                               Runnable installChatProtocol,
                               Runnable installLogProtocol) {
        this.chatHandler = chatHandler;
        this.logHandler = logHandler;
        this.installChatProtocol = installChatProtocol;
        this.installLogProtocol = installLogProtocol;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            String uri = req.uri();
            // 按路径分发：只判断前缀，忽略 query
            boolean isChat = uri != null && (uri.startsWith("/ws/chat") || uri.startsWith("/ws/chat/"));
            boolean isLog  = uri != null && (uri.startsWith("/ws/system-log") || uri.startsWith("/ws/system-log/"));

            if (isChat) {
                installChatProtocol.run();
                // ChatWsHandshaker 是 ChatWsHandler 的前置，必须在 ChatWsHandler 前插入；
                // 但我们这里已在 NettyMain 里一并插入 ChatWsHandshaker + ChatWsHandler（通过
                // installChatProtocol），因此直接把请求转发即可。
                ctx.fireChannelRead(msg);
                return;
            }
            if (isLog) {
                installLogProtocol.run();
                ctx.fireChannelRead(msg);
                return;
            }

            // 其它路径：简单拒绝并关闭
            ctx.writeAndFlush(new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY));
            ctx.close();
            return;
        }
        ctx.fireChannelRead(msg);
    }
}