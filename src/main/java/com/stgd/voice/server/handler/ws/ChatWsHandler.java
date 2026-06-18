package com.stgd.voice.server.handler.ws;
import com.stgd.voice.entity.Message;
import com.stgd.voice.entity.Room;
import com.stgd.voice.mapper.RoomMapper;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Map;

/**
 * Netty WebSocket 聊天业务处理器。
 *
 * 工作流程：
 *   1. 客户端通过 /ws/chat?loginUser=xxx&wsId=yyy 发起 WebSocket 握手
 *   2. ChatWsHandshaker 在协议升级前解析 query，调用 setWsInfo 注册当前 Channel
 *   3. WebSocketServerProtocolHandler 完成握手后触发 HandshakeComplete 事件，本 handler 推送一次房间列表
 *   4. 后续文本消息按 Message.type 分发处理（与旧版 ChatEndpoint 一致）
 */
public class ChatWsHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final ConnectManager connectManager;
    private final RoomMapper roomMapper;
    private final SystemLogPublisher logPublisher;

    private String wsId;
    private String loginUser;

    public ChatWsHandler(ConnectManager connectManager, RoomMapper roomMapper, SystemLogPublisher logPublisher) {
        this.connectManager = connectManager;
        this.roomMapper = roomMapper;
        this.logPublisher = logPublisher;
    }

    /** 由 ChatWsHandshaker 在协议升级之前调用，完成登录信息与 wsId 注册。 */
    public void setWsInfo(Channel ch, String wsId, String loginUser) {
        this.wsId = wsId;
        this.loginUser = loginUser;
        connectManager.addWsChannel(wsId, ch);
        if (loginUser != null) {
            connectManager.setWsLoginUser(wsId, loginUser);
        }
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
                case 10: handleInsertRoom(ctx, message); break;
                case 11: handleRemoveRoom(ctx, message); break;
                case 12: handleUpdateRoom(ctx, message); break;
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

    private void handleInsertRoom(ChannelHandlerContext ctx, Message message) {
        String loginUserName = connectManager.getWsLoginUser(this.wsId);
        if (loginUserName == null) { sendSystem(ctx, "登录信息丢失，请刷新页面"); return; }
        if (!"super".equals(loginUserName)) { sendSystem(ctx, "没有权限添加房间"); return; }
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        String roomName = message.getRoomName();
        if (roomName == null || roomName.trim().isEmpty()) { sendSystem(ctx, "房间名称不能为空"); return; }
        Room room = new Room();
        room.setName(roomName.trim());
        String pwd = message.getPassword();
        if (pwd != null && !pwd.trim().isEmpty()) room.setPassword(pwd.trim());
        int dbResult = roomMapper.insert(room);
        connectManager.addRoom(room);
        if (dbResult > 0) {
            sendSystem(ctx, "房间 [" + room.getName() + "] 添加成功");
            if (logPublisher != null) {
                logPublisher.publish("room", null, room.getName(),
                        userName + " 通过 WebSocket 添加了房间 [" + room.getName() + "]");
            }
        } else {
            sendSystem(ctx, "房间添加失败");
        }
    }

    private void handleRemoveRoom(ChannelHandlerContext ctx, Message message) {
        String loginUserName = connectManager.getWsLoginUser(this.wsId);
        if (loginUserName == null) { sendSystem(ctx, "登录信息丢失，请刷新页面"); return; }
        if (!"super".equals(loginUserName)) { sendSystem(ctx, "没有权限删除房间"); return; }
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        Integer roomId = message.getRoomId();
        if (roomId == null) { sendSystem(ctx, "房间ID不能为空"); return; }
        Room room = connectManager.findRoomById(roomId);
        String removedName = room != null ? room.getName() : ("房间#" + roomId);
        connectManager.removeRoom(roomId);
        int dbResult = roomMapper.deleteById(roomId);
        if (dbResult > 0) {
            sendSystem(ctx, "房间 [" + removedName + "] 已删除");
            if (logPublisher != null) {
                logPublisher.publish("room", null, removedName,
                        userName + " 通过 WebSocket 删除了房间 [" + removedName + "]");
            }
        } else {
            sendSystem(ctx, "房间删除失败（数据库中可能不存在）");
        }
    }

    private void handleUpdateRoom(ChannelHandlerContext ctx, Message message) {
        String loginUserName = connectManager.getWsLoginUser(this.wsId);
        if (loginUserName == null) { sendSystem(ctx, "登录信息丢失，请刷新页面"); return; }
        if (!"super".equals(loginUserName)) { sendSystem(ctx, "没有权限更新房间"); return; }
        String userName = connectManager.getWsUserName(this.wsId);
        if (userName == null) { sendSystem(ctx, "请先设置昵称"); return; }
        Integer roomId = message.getRoomId();
        if (roomId == null) { sendSystem(ctx, "房间ID不能为空"); return; }
        Room existRoom = connectManager.findRoomById(roomId);
        if (existRoom == null) { sendSystem(ctx, "房间不存在"); return; }
        Room updateRoom = new Room();
        updateRoom.setId(roomId);
        String newName = message.getRoomName();
        if (newName != null && !newName.trim().isEmpty()) updateRoom.setName(newName.trim());
        else updateRoom.setName(existRoom.getName());
        String pwd = message.getPassword();
        if (pwd != null) updateRoom.setPassword(pwd.trim());
        else updateRoom.setPassword(existRoom.getPassword());
        int dbResult = roomMapper.updateById(updateRoom);
        updateRoom.setUserNum(existRoom.getUserNum());
        connectManager.addRoom(updateRoom);
        if (dbResult > 0) {
            sendSystem(ctx, "房间 [" + updateRoom.getName() + "] 更新成功");
            if (logPublisher != null) {
                logPublisher.publish("room", null, updateRoom.getName(),
                        userName + " 通过 WebSocket 更新了房间 [" + updateRoom.getName() + "]");
            }
        } else {
            sendSystem(ctx, "房间更新失败");
        }
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