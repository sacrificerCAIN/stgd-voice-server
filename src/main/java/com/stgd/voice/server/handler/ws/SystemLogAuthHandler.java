package com.stgd.voice.server.handler.ws;

import com.stgd.voice.server.component.SystemLogSessionRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * /ws/system-log 握手鉴权。
 *
 * 该端点由独立的 Netty 端口提供，不经过 Spring MVC 的鉴权链，
 * 因此在这里读取握手请求 Cookie 中的 JSESSIONID，校验对应会话是否已登录；
 * 未登录的请求直接返回 403 并关闭连接，避免任意访客订阅系统日志。
 *
 * 位置要求：必须放在 WebSocketServerProtocolHandler 之前（只处理升级前的 FullHttpRequest），
 * 且在 HttpObjectAggregator 之后。
 */
public class SystemLogAuthHandler extends ChannelInboundHandlerAdapter {

    /** Spring Boot 默认的会话 Cookie 名称（项目未修改 server.servlet.session.cookie.name） */
    private static final String SESSION_COOKIE_NAME = "JSESSIONID";

    private final SystemLogSessionRegistry registry;

    public SystemLogAuthHandler(SystemLogSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            // 注册表不可用或会话未登录时一律拒绝（fail-closed）
            if (registry == null || !registry.isLoggedIn(readSessionId(req))) {
                reject(ctx, req);
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    /** 从 Cookie 头中取出 JSESSIONID 的值 */
    private String readSessionId(FullHttpRequest req) {
        String cookie = req.headers().get(HttpHeaderNames.COOKIE);
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String item = part.trim();
            if (item.startsWith(SESSION_COOKIE_NAME + "=")) {
                return item.substring(SESSION_COOKIE_NAME.length() + 1);
            }
        }
        return null;
    }

    private void reject(ChannelHandlerContext ctx, FullHttpRequest req) {
        byte[] body = "未登录，禁止订阅系统日志".getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.wrappedBuffer(body));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        // FullHttpRequest 由 HttpObjectAggregator 创建，必须释放引用计数
        req.release();
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
