package com.stgd.voice.server.handler.ws;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.UUID;

/**
 * WebSocket 握手前置处理器。
 *
 * 在 WebSocketServerProtocolHandler 完成协议升级之前，
 * 读取第一个 HTTP（升级）请求，解析其 query 中的 wsId 与 loginUser，
 * 并通过 {@link ChatWsHandler#setWsInfo} 注册到业务 handler，
 * 之后把请求继续交给下游完成协议升级。
 *
 * 若请求中未提供 wsId，则自动生成一个，保证后续逻辑可用。
 */
public class ChatWsHandshaker extends ChannelInboundHandlerAdapter {

    private final ChatWsHandler chatHandler;

    public ChatWsHandshaker(ChatWsHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            try {
                QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
                String wsId = firstParam(decoder, "wsId");
                if (wsId == null || wsId.isEmpty()) {
                    wsId = UUID.randomUUID().toString();
                }
                String loginUser = firstParam(decoder, "loginUser");
                chatHandler.setWsInfo(ctx.channel(), wsId, loginUser);
            } finally {
                // FullHttpRequest 的内容会由下游继续读取，这里不 release
            }
        }
        ctx.fireChannelRead(msg);
    }

    private static String firstParam(QueryStringDecoder decoder, String name) {
        List<String> values = decoder.parameters().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}