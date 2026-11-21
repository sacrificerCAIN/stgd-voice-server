
package com.stgd.voice.server;

import com.stgd.voice.config.ServerConfig;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.server.component.IpBlacklistManager;
import com.stgd.voice.server.component.SystemLogSessionRegistry;
import com.stgd.voice.server.handler.system.IdleEventHandler;
import com.stgd.voice.server.handler.text.TextHandler;
import com.stgd.voice.server.handler.voice.VoiceHandler;
import com.stgd.voice.server.handler.ws.ChatWsHandler;
import com.stgd.voice.server.handler.ws.ChatWsHandshaker;
import com.stgd.voice.server.handler.ws.SystemLogAuthHandler;
import com.stgd.voice.server.handler.ws.SystemLogWsHandler;
import com.stgd.voice.server.handler.ws.WebSocketPathRouter;
import com.stgd.voice.service.publish.impl.strategy.factory.MessageStrategyFactory;
import com.stgd.voice.ws.SystemLogEndpoint;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
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
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class NettyMain {
	@Resource
	private ConnectManager connectManager;
	@Resource
	private IpBlacklistManager blacklistManager;
	@Resource
	private MessageStrategyFactory messageStrategyFactory;
	@Resource
	private ServerConfig serverConfig;
	@Resource
	private SystemLogEndpoint systemLogEndpoint;
	@Resource
	private SystemLogSessionRegistry systemLogSessionRegistry;

	/** 服务端 Channel（TCP/WS/UDP 监听套接字），关停时先关闭它们停止接入新连接 */
	private final List<Channel> serverChannels = new ArrayList<>();
	/** Netty 线程池，关停时逐个 shutdownGracefully 并等待终止 */
	private final List<EventLoopGroup> eventLoopGroups = new ArrayList<>();

	/** 判断某个远程地址是否属于黑名单。返回 null 表示不拒绝，否则返回拒绝原因。 */
	private String rejectIfBlacklisted(io.netty.channel.Channel ch) {
		if (blacklistManager == null) return null;
		if (blacklistManager.isBlack(ch)) {
			String ip = IpBlacklistManager.getChannelIp(ch);
			return "IP " + (ip == null ? "" : ip) + " 已被加入黑名单，连接已被拒绝";
		}
		return null;
	}

	private static int getIdleTimeoutSeconds(ServerConfig serverConfig) {
		Integer v = serverConfig == null ? null : serverConfig.getIdleTimeoutSeconds();
		return (v == null || v <= 0) ? 0 : v;
	}
	
	public void start() {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup tcpGroup = new NioEventLoopGroup();
		EventLoopGroup wsGroup = new NioEventLoopGroup();
		EventLoopGroup udpGroup = new NioEventLoopGroup();

		eventLoopGroups.add(bossGroup);
		eventLoopGroups.add(tcpGroup);
		eventLoopGroups.add(wsGroup);
		eventLoopGroups.add(udpGroup);

		// true 表示正常关停（监听 Channel 关闭或被中断），false 表示启动过程中失败
		boolean normalShutdown = false;
		try {
			// ============ TCP 服务器 ============
			ServerBootstrap tcpBootstrap = new ServerBootstrap();
			tcpBootstrap.group(bossGroup, tcpGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					public void initChannel(SocketChannel ch) {
						// === 黑名单拦截 ===
						String reason = rejectIfBlacklisted(ch);
						if (reason != null) {
							ch.writeAndFlush(io.netty.buffer.Unpooled.copiedBuffer(
								(reason + "\n").getBytes(CharsetUtil.UTF_8))).addListener(
									io.netty.channel.ChannelFutureListener.CLOSE);
							return;
						}
						// 读空闲超时：在规定时间内没有收到客户端数据，则由 IdleStateHandler 派发 IdleStateEvent
						int idleSecs = getIdleTimeoutSeconds(serverConfig);
						if (idleSecs > 0) {
							ch.pipeline().addLast("idleState", new IdleStateHandler(idleSecs, 0, 0, TimeUnit.SECONDS));
							ch.pipeline().addLast("idleHandler", new IdleEventHandler("TCP"));
						}
						ch.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
						ch.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
						// 本类由 new 创建（不是 Spring Bean），黑名单必须通过构造器传入，
						// 否则 TextHandler 内部的 @Autowired 字段永远为 null
						ch.pipeline().addLast(new TextHandler(connectManager, messageStrategyFactory, blacklistManager));
					}
				});

			ChannelFuture tcpFuture;
			try {
				tcpFuture = tcpBootstrap.bind(serverConfig.getTcpPort()).sync();
				serverChannels.add(tcpFuture.channel());
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
						// === 黑名单拦截：直接返回 403 Forbidden 并关闭连接
						String reason = rejectIfBlacklisted(ch);
						if (reason != null) {
							String resp = "HTTP/1.1 403 Forbidden\r\nContent-Length: " + reason.length() + "\r\nConnection: close\r\n\r\n" + reason;
							ch.writeAndFlush(io.netty.buffer.Unpooled.copiedBuffer(
								resp.getBytes(CharsetUtil.UTF_8))).addListener(
									io.netty.channel.ChannelFutureListener.CLOSE);
							return;
						}
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
						final ChatWsHandler chatHandler = new ChatWsHandler(connectManager);
						if (blacklistManager != null) chatHandler.setBlacklistManager(blacklistManager);
						final SystemLogWsHandler logHandler = new SystemLogWsHandler(systemLogEndpoint);

						Runnable installChat = () -> {
							ch.pipeline().addLast(new ChatWsHandshaker(chatHandler));
							ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/chat", true));
							ch.pipeline().addLast(chatHandler);
						};
						Runnable installLog = () -> {
							// 系统日志仅允许已登录用户订阅：在协议升级前校验握手 Cookie 中的 JSESSIONID
							ch.pipeline().addLast(new SystemLogAuthHandler(systemLogSessionRegistry));
							ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/system-log", true));
							ch.pipeline().addLast(logHandler);
						};

						ch.pipeline().addLast(new WebSocketPathRouter(chatHandler, logHandler, installChat, installLog));
						}
					});
				try {
					serverChannels.add(wsBootstrap.bind(wsPort).sync().channel());
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
				serverChannels.add(udpFuture.channel());
				System.out.println("UDP服务启动成功，端口：" + serverConfig.getUdpPort());
			} catch (Exception e) {
				handleBindException(e, "UDP", serverConfig.getUdpPort());
				throw e;
			}

			if (blacklistManager != null) blacklistManager.init();
			System.out.println("项目启动成功");
			String host = (serverConfig.getHost() != null && !serverConfig.getHost().trim().isEmpty())
					? serverConfig.getHost().trim()
					: "localhost";
			System.out.println("控制台 http://" + host + ":" + serverConfig.getPort());
			System.out.println("聊天室 http://" + host + ":" + serverConfig.getPort() + "/chat.html");

			// 等待主 TCP 服务器关闭
            tcpFuture.channel().closeFuture().sync();
            normalShutdown = true;

        } catch (InterruptedException e) {
			System.err.println("服务被中断：" + e.getMessage());
			Thread.currentThread().interrupt();
			normalShutdown = true;
		} catch (Exception e) {
			System.err.println("服务启动异常：" + e.getMessage());
			e.printStackTrace();
		} finally {
			destroy();
			if (!normalShutdown) {
				// 启动失败时不应只留 Web 端口存活（页面可用、聊天全挂），直接结束进程便于发现和重启
				System.err.println("Netty 服务未能启动，进程退出");
				System.exit(1);
			}
		}
	}

	/**
	 * 优雅关停：先停止监听，再关闭已建立连接，最后逐个关停 EventLoopGroup 并等待终止，
	 * 避免进程退出时丢弃待写数据或残留线程。可重复调用（start() 的 finally 与 Spring 容器销毁可能同时触发）。
	 */
	@PreDestroy
	public synchronized void destroy() {
		for (Channel serverChannel : serverChannels) {
			if (serverChannel != null && serverChannel.isOpen()) {
				serverChannel.close().awaitUninterruptibly(3, TimeUnit.SECONDS);
			}
		}
		serverChannels.clear();
		if (connectManager != null) {
			connectManager.closeAllConnections();
		}
		List<Future<?>> futures = new ArrayList<>();
		for (EventLoopGroup group : eventLoopGroups) {
			futures.add(group.shutdownGracefully(0, 5, TimeUnit.SECONDS));
		}
		eventLoopGroups.clear();
		for (Future<?> future : futures) {
			future.awaitUninterruptibly(6, TimeUnit.SECONDS);
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