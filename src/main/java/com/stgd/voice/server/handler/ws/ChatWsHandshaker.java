package com.stgd.voice.server.handler.ws;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.UUID;

/**
 * WebSocket 握手前置处理器。
 *
 * 在 WebSocketServerProtocolHandler 完成协议升级之前，
 * 由服务端生成当前连接的 wsId，
 * 并通过 {@link ChatWsHandler#setWsInfo} 注册到业务 handler，
 * 之后把请求继续交给下游完成协议升级。
 *
 * wsId 是连接在服务端的身份标识，只能由服务端生成：
 *   - 不再读取客户端传入的 ?wsId=，避免伪造他人 wsId 冒名顶替或顶掉对方连接；
 *   - 游客无需任何身份参数即可连接，昵称仍在连接后通过 type=1 设置，
 *     服务端通过 type:"login" 响应把 wsId 下发回客户端。
 *
 * 注意：?loginUser= 参数同样不可信，已被忽略。
 * 房间管理等敏感操作仅能通过 HTTP REST 接口（需登录 Session）完成。
 */
public class ChatWsHandshaker extends ChannelInboundHandlerAdapter {

    private final ChatWsHandler chatHandler;

    /** 是否已为本连接注册过 wsId（握手前可能收到多个 HTTP 请求，只注册一次） */
    private boolean registered;

    public ChatWsHandshaker(ChatWsHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest && !registered) {
            registered = true;
            // wsId 即连接身份，只能由服务端生成，忽略客户端传入的任何 wsId
            chatHandler.setWsInfo(ctx.channel(), UUID.randomUUID().toString());
        }
        ctx.fireChannelRead(msg);
    }
}