
package com.stgd.voice.server;

import com.stgd.voice.config.ServerConfig;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.server.handler.text.TextHandler;
import com.stgd.voice.server.handler.voice.VoiceHandler;
import com.stgd.voice.server.handler.ws.ChatWsHandler;
import com.stgd.voice.server.handler.ws.ChatWsHandshaker;
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
import io.netty.util.CharsetUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.BindException;

@Component
public class NettyMain {
	@Autowired
	private ConnectManager connectManager;
	@Autowired
	private MessageStrategyFactory messageStrategyFactory;
	@Autowired
	private ServerConfig serverConfig;
	@Autowired
	private RoomMapper roomMapper;
	@Autowired
	private SystemLogPublisher logPublisher;

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
				if (e.getCause() instanceof BindException) {
					System.err.println("TCP端口绑定失败：" + serverConfig.getTcpPort() +
							"，可能已被占用或没有权限，程序会在5秒后关闭");
					Thread.sleep(5000);
					System.exit(1);
					return;
				}
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
							ch.pipeline().addLast(new HttpServerCodec());
							ch.pipeline().addLast(new HttpObjectAggregator(65536));
							// 1. 在协议升级之前先从 HTTP 请求中解析 query（wsId、loginUser），保存到 channel 属性中
							ChatWsHandler chatHandler = new ChatWsHandler(connectManager, roomMapper, logPublisher);
							ch.pipeline().addLast(new ChatWsHandshaker(chatHandler));
							// 2. WebSocket 协议处理，路径：/ws/chat（与旧版一致）
							ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/chat", true));
							// 3. 业务 handler：只接收 WebSocketFrame（握手完成后由 HandshakeComplete 推送房间列表）
							ch.pipeline().addLast(chatHandler);
						}
					});
				try {
					wsBootstrap.bind(wsPort).sync();
					System.out.println("WebSocket服务启动成功，端口：" + wsPort + "（路径 /ws/chat）");
				} catch (Exception e) {
					if (e.getCause() instanceof BindException) {
						System.err.println("WebSocket端口绑定失败：" + wsPort +
								"，可能已被占用或没有权限，程序会在5秒后关闭");
						Thread.sleep(5000);
						System.exit(1);
						return;
					}
					throw e;
				}
			} else {
				System.out.println("未配置 server.ws-port，WebSocket 服务未启动");
			}

			// ============ UDP 服务器 ============
			try {
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
					if (e.getCause() instanceof BindException) {
						System.err.println("UDP端口绑定失败：" + serverConfig.getUdpPort() +
								"，可能已被占用或没有权限，程序会在5秒后关闭");
						Thread.sleep(5000);
						System.exit(1);
						return;
					}
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
			} finally {
				udpGroup.shutdownGracefully();
			}
		} catch (InterruptedException e) {
			System.err.println("服务被中断：" + e.getMessage());
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			System.err.println("服务启动异常：" + e.getMessage());
			e.printStackTrace();
		} finally {
			bossGroup.shutdownGracefully();
			tcpGroup.shutdownGracefully();
			wsGroup.shutdownGracefully();
			udpGroup.shutdownGracefully();
		}
	}
}