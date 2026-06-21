package com.stgd.voice.server.handler.ws;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.server.component.IpBlacklistManager;
import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * Netty WebSocket 聊天业务处理器（仅处理聊天、私聊、WebRTC信令，房间管理通过 HTTP REST 接口完成）。
 *
 * 工作流程：
 *   1. 客户端通过 /ws/chat?wsId=yyy 发起 WebSocket 握手（已移除 ?loginUser=，该参数不可信）
 *   2. ChatWsHandshaker 在协议升级前解析 query，调用 setWsInfo 注册当前 Channel
 *   3. WebSocketServerProtocolHandler 完成握手后触发 HandshakeComplete 事件，本 handler 推送一次房间列表
 *   4. 后续文本消息按 Message.type 分发处理
 *   5. 注意：房间管理（添加/删除/更新房间）仅能通过 HTTP REST 接口（需登录）完成，已从 WebSocket 移除
 */
public class ChatWsHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final ConnectManager connectManager;

    @Autowired(required = false)
    private IpBlacklistManager blacklistManager;

    public void setBlacklistManager(IpBlacklistManager m) {
        this.blacklistManager = m;
    }

    private String wsId;

    public ChatWsHandler(ConnectManager connectManager) {
        this.connectManager = connectManager;
    }

    /** 由 ChatWsHandshaker 在协议升级之前调用，完成 wsId 注册。 */
    public void setWsInfo(Channel ch, String wsId) {
        if (blacklistManager != null && blacklistManager.isBlack(ch)) {
            String ip = IpBlacklistManager.getChannelIp(ch);
            Map<String, String> data = new HashMap<>();
            data.put("type", "system");
            data.put("payload", "IP " + (ip == null ? "未知IP" : ip) + " 已被加入黑名单");
            String msg = JsonUtil.toJson(data);
            ch.writeAndFlush(new TextWebSocketFrame(msg))
                    .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }
        this.wsId = wsId;
        connectManager.addWsChannel(wsId, ch);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (this.wsId != null) {
            connectManager.removeWsChannel(this.wsId);
            connectManager.broadcastAllRoomUsers();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (this.wsId != null) {
            connectManager.removeWsChannel(this.wsId);
        }
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            connectManager.broadcastRoomList();
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            Message message = JsonUtil.parse(text, Message.class);
            if (message == null || message.getType() == null) {
                sendSystem(ctx, "消息格式错误");
                return;
            }
            int type = message.getType();
            switch (type) {
                case 1:  handleLogin(ctx, message);   break;
                case 2:  handleIdle(ctx, message);    break;
                case 3:  handleRoomMessage(ctx, message); break;
                case 4:  handlePrivateMessage(ctx, message); break;
                case 5:  handleBroadcast(ctx, message); break;
                case 6:  handleJoinRoom(ctx, message); break;
                case 7:  handleLogout(ctx); break;
                case 8:  handleGetAllRoomUsers(ctx); break;
                case 9:  handleGetRoomList(ctx); break;
                // type=10/11/12 已从 WebSocket 移除，房间管理请通过 HTTP REST 接口（需登录）
                case 13: handleWebRtcSignal(ctx, message); break;
                default: sendSystem(ctx, "未知消息类型");
            }
        } catch (Exception e) {
            sendSystem(ctx, "消息解析失败");
            e.printStackTrace();
        }
    }

    private void handleLogin(ChannelHandlerContext ctx, Message message) {
        String userName = message.getUserName();
        if (userName == null || userName.trim().isEmpty()) {
            sendSystem(ctx, "用户名不能为空");
            return;
        }
        String actualName = connectManager.registerWsUser(this.wsId, userName);
        Map<String, Object> resp = JsonUtil.newMap();
        resp.put("type", "login");
        resp.put("userId", this.wsId);
        resp.put("userName", actualName);
        sendJson(ctx, resp);
    }

    private void handleIdle(ChannelHandlerContext ctx, Message message) {
        Map<String, Object> resp = JsonUtil.newMap();
        resp.put("type", "idle");
        resp.put("timestamp", System.currentTimeMillis());
        sendJson(ctx, resp);
    }

    private void handleJoinRoom(ChannelHandlerContext ctx, Message message) {
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        Integer targetRoomId = message.getTargetRoomId();
        if (targetRoomId == null) { sendSystem(ctx, "目标房间ID不能为空"); return; }
        Room room = connectManager.getWsRoomById(targetRoomId);
        if (room == null) { sendSystem(ctx, "房间不存在"); return; }
        String roomPwd = room.getPassword();
        String inputPwd = message.getPassword();
        if (roomPwd != null && !roomPwd.trim().isEmpty()) {
            if (inputPwd == null || !inputPwd.equals(roomPwd)) {
                sendSystem(ctx, "房间密码错误");
                return;
            }
        }
        connectManager.joinWsRoom(this.wsId, targetRoomId);
        Map<String, Object> resp = JsonUtil.newMap();
        resp.put("type", "join");
        resp.put("roomId", targetRoomId);
        resp.put("roomName", room.getName());
        resp.put("userNum", room.getUserNum());
        sendJson(ctx, resp);
        connectManager.broadcastAllRoomUsers();
    }

    private void handleRoomMessage(ChannelHandlerContext ctx, Message message) {
        String userName = connectManager.getWsUserName(this.wsId);
        Integer roomId = connectManager.getWsRoomId(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        if (roomId == null)   { sendSystem(ctx, "请先加入房间"); return; }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        connectManager.publishRoomMessageWs(roomId, userName, payload, this.wsId);
    }

    private void handleWebRtcSignal(ChannelHandlerContext ctx, Message message) {
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        Integer roomId = connectManager.getWsRoomId(this.wsId);
        if (roomId == null)   { sendSystem(ctx, "请先加入房间"); return; }
        String targetUserId = message.getTargetUserId();
        String payload = message.getPayload();
        if (payload == null) payload = "";
        Map<String, Object> forward = JsonUtil.newMap();
        forward.put("type", "webrtc");
        forward.put("roomId", roomId);
        forward.put("fromUserId", this.wsId);
        forward.put("fromUserName", userName);
        forward.put("targetUserId", targetUserId);
        forward.put("payload", payload);
        connectManager.publishRoomWebRtcWs(roomId, this.wsId, targetUserId, JsonUtil.toJson(forward));
    }

    private void handlePrivateMessage(ChannelHandlerContext ctx, Message message) {
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        String targetUserId = message.getTargetUserId();
        if (targetUserId == null || targetUserId.isEmpty()) { sendSystem(ctx, "目标用户ID不能为空"); return; }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        connectManager.sendWsPrivate(this.wsId, targetUserId, payload);
    }

    private void handleBroadcast(ChannelHandlerContext ctx, Message message) {
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        String payload = message.getPayload();
        if (payload == null) payload = "";
        Map<String, Object> msg = JsonUtil.newMap();
        msg.put("type", "broadcast");
        msg.put("userName", userName);
        msg.put("payload", payload);
        msg.put("timestamp", System.currentTimeMillis());
        String text = JsonUtil.toJson(msg);
        for (Channel ch : connectManager.getAllWsChannels()) {
            if (ch != null && ch.isActive()) {
                ConnectManager.sendToWs(ch, text);
            }
        }
    }

    private void handleLogout(ChannelHandlerContext ctx) {
        if (this.wsId != null) {
            connectManager.removeWsChannel(this.wsId);
            connectManager.broadcastAllRoomUsers();
        }
    }

    private void handleGetAllRoomUsers(ChannelHandlerContext ctx) {
        connectManager.broadcastAllRoomUsers();
    }

    private void handleGetRoomList(ChannelHandlerContext ctx) {
        connectManager.broadcastRoomList();
    }

    private void sendSystem(ChannelHandlerContext ctx, String text) {
        Map<String, Object> msg = JsonUtil.newMap();
        msg.put("type", "system");
        msg.put("payload", text);
        ConnectManager.sendToWs(ctx.channel(), JsonUtil.toJson(msg));
    }

    private void sendJson(ChannelHandlerContext ctx, Map<String, Object> json) {
        if (json == null) return;
        ConnectManager.sendToWs(ctx.channel(), JsonUtil.toJson(json));
    }
}