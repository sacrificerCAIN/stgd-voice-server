
package com.stgd.voice.server;

import com.stgd.voice.config.ServerConfig;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.server.handler.system.IdleEventHandler;
import com.stgd.voice.server.handler.text.TextHandler;
import com.stgd.voice.server.handler.voice.VoiceHandler;
import com.stgd.voice.server.handler.ws.ChatWsHandler;
import com.stgd.voice.server.handler.ws.ChatWsHandshaker;
import com.stgd.voice.server.handler.ws.SystemLogWsHandler;
import com.stgd.voice.server.handler.ws.WebSocketPathRouter;
import com.stgd.voice.service.publish.impl.strategy.factory.MessageStrategyFactory;
import com.stgd.voice.ws.SystemLogPublisher;
import com.stgd.voice.mapper.RoomMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.BindException;

@Component
public class NettyMain {
	@Resource
	private ConnectManager connectManager;
	@Resource
	private MessageStrategyFactory messageStrategyFactory;
	@Resource
	private ServerConfig serverConfig;
	@Resource
	private RoomMapper roomMapper;
	@Resource
	private SystemLogPublisher logPublisher;

	private static int getIdleTimeoutSeconds(ServerConfig serverConfig) {
		Integer v = serverConfig == null ? null : serverConfig.getIdleTimeoutSeconds();
		return (v == null || v <= 0) ? 0 : v;
	}
	
	public void start() {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup tcpGroup = new NioEventLoopGroup();
		EventLoopGroup wsGroup = new NioEventLoopGroup();
		EventLoopGroup udpGroup = new NioEventLoopGroup();

		try {
			// ============ TCP 服务器 ============
			ServerBootstrap tcpBootstrap = new ServerBootstrap();
			tcpBootstrap.group(bossGroup, tcpGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch) {
							// 读空闲超时：在规定时间内没有收到客户端数据，则由 IdleStateHandler 派发 IdleStateEvent
							int idleSecs = getIdleTimeoutSeconds(serverConfig);
							if (idleSecs > 0) {
								ch.pipeline().addLast("idleState", new IdleStateHandler(idleSecs, 0, 0, TimeUnit.SECONDS));
								ch.pipeline().addLast("idleHandler", new IdleEventHandler("TCP"));
							}
							ch.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
							ch.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
							ch.pipeline().addLast(new TextHandler(connectManager, messageStrategyFactory));
						}
					});

			ChannelFuture tcpFuture = null;
			try {
				tcpFuture = tcpBootstrap.bind(serverConfig.getTcpPort()).sync();
				System.out.println("TCP服务启动成功，端口：" + serverConfig.getTcpPort());
			} catch (Exception e) {
				handleBindException(e, "TCP", serverConfig.getTcpPort());
				throw e;
			}

			// ============ WebSocket 服务器（Netty 实现，取代原来的 javax.websocket） ============
			Integer wsPort = serverConfig.getWsPort();
			if (wsPort != null && wsPort > 0) {
				ServerBootstrap wsBootstrap = new ServerBootstrap();
				wsBootstrap.group(bossGroup, wsGroup)
						.channel(NioServerSocketChannel.class)
						.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
					public void initChannel(SocketChannel ch) {
						// 读空闲超时：在规定时间内没有收到客户端数据，则由 IdleStateHandler 派发 IdleStateEvent
						int idleSecs = getIdleTimeoutSeconds(serverConfig);
						if (idleSecs > 0) {
							ch.pipeline().addLast("idleState", new IdleStateHandler(idleSecs, 0, 0, TimeUnit.SECONDS));
							ch.pipeline().addLast("idleHandler", new IdleEventHandler("WS"));
						}
						ch.pipeline().addLast(new HttpServerCodec());
						ch.pipeline().addLast(new HttpObjectAggregator(65536));

						// 根据第一次 HTTP 升级请求的 URI 路径，动态决定后续安装哪一套 WebSocket 子流程。
						// - /ws/chat       -> ChatWsHandshaker + WebSocketServerProtocolHandler(/ws/chat) + ChatWsHandler
						// - /ws/system-log -> WebSocketServerProtocolHandler(/ws/system-log) + SystemLogWsHandler
						final ChatWsHandler chatHandler = new ChatWsHandler(connectManager, roomMapper, logPublisher);
						final SystemLogWsHandler logHandler = new SystemLogWsHandler();

						Runnable installChat = () -> {
							ch.pipeline().addLast(new ChatWsHandshaker(chatHandler));
							ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/chat", true));
							ch.pipeline().addLast(chatHandler);
						};
						Runnable installLog = () -> {
							ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/system-log", true));
							ch.pipeline().addLast(logHandler);
						};

						ch.pipeline().addLast(new WebSocketPathRouter(chatHandler, logHandler, installChat, installLog));
						}
					});
				try {
					wsBootstrap.bind(wsPort).sync();
					System.out.println("WebSocket服务启动成功，端口：" + wsPort + "（路径 /ws/chat、/ws/system-log）");
				} catch (Exception e) {
					handleBindException(e, "WebSocket", wsPort);
					throw e;
				}
			} else {
				System.out.println("未配置 server.ws-port，WebSocket 服务未启动");
			}

			// ============ UDP 服务器 ============
			Bootstrap udpBootstrap = new Bootstrap();
			udpBootstrap.group(udpGroup)
				.channel(NioDatagramChannel.class)
				.handler(new ChannelInitializer<DatagramChannel>() {
					@Override
					public void initChannel(DatagramChannel ch) {
						ch.pipeline().addLast(new VoiceHandler());
					}
				});

			ChannelFuture udpFuture;
			try {
				udpFuture = udpBootstrap.bind(serverConfig.getUdpPort()).sync();
				System.out.println("UDP服务启动成功，端口：" + serverConfig.getUdpPort());
			} catch (Exception e) {
				handleBindException(e, "UDP", serverConfig.getUdpPort());
				throw e;
			}

			connectManager.init();
			System.out.println("项目启动成功");
			String host = (serverConfig.getHost() != null && !serverConfig.getHost().trim().isEmpty())
					? serverConfig.getHost().trim()
					: "localhost";
			System.out.println("控制台 http://" + host + ":" + serverConfig.getPort());
			System.out.println("聊天室 http://" + host + ":" + serverConfig.getPort() + "/chat.html");

			// 等待主 TCP 服务器关闭
			if (tcpFuture != null) {
				tcpFuture.channel().closeFuture().sync();
			}

		} catch (InterruptedException e) {
			System.err.println("服务被中断：" + e.getMessage());
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			System.err.println("服务启动异常：" + e.getMessage());
		} finally {
			bossGroup.shutdownGracefully();
			tcpGroup.shutdownGracefully();
			wsGroup.shutdownGracefully();
			udpGroup.shutdownGracefully();
		}
	}

	private static void handleBindException(Exception e, String serviceName, Integer port) throws Exception {
		if (e.getCause() instanceof BindException) {
			System.err.println(serviceName + "端口绑定失败：" + port + "，可能已被占用或没有权限，程序会在5秒后关闭");
			Thread.sleep(5000);
			System.exit(1);
		}
		throw e;
	}
}