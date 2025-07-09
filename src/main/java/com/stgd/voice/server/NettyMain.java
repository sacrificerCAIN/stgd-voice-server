
package com.stgd.voice.server;

import com.stgd.voice.config.ServerConfig;
import com.stgd.voice.server.component.ConnectManager;
import com.stgd.voice.server.handler.text.TextHandler;
import com.stgd.voice.server.handler.voice.VoiceHandler;
import com.stgd.voice.service.publish.impl.strategy.factory.MessageStrategyFactory;
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

	public void start() {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup tcpGroup = new NioEventLoopGroup();
		EventLoopGroup udpGroup = new NioEventLoopGroup();

		try {
			// TCP服务器设置
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

			// UDP服务器设置
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

				ChannelFuture udpFuture = null;
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
				System.out.println("项目启动成功,请访问控制台 http://localhost:" + serverConfig.getPort());

				// 等待两个服务器关闭
				tcpFuture.channel().closeFuture().sync();
				udpFuture.channel().closeFuture().sync();
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
			udpGroup.shutdownGracefully();
		}
	}
}
