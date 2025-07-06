package com.stgd.voice.stgdvoice.server;

import com.stgd.voice.stgdvoice.server.component.ConnectManager;
import com.stgd.voice.stgdvoice.server.handler.text.TextHandler;
import com.stgd.voice.stgdvoice.server.handler.voice.VoiceHandler;
import com.stgd.voice.stgdvoice.service.publish.impl.strategy.factory.MessageStrategyFactory;
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

/**
 * @author Hzzz
 */
@Component
public class NettyMain {

	@Autowired
	private ConnectManager connectManager;

	@Autowired
	private MessageStrategyFactory messageStrategyFactory;

	public void start() {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap tcpBootstrap = new ServerBootstrap();
			tcpBootstrap.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
							ch.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
							ch.pipeline().addLast(new TextHandler(connectManager, messageStrategyFactory));
						}
					});
			ChannelFuture tcpFuture = tcpBootstrap.bind(6324).sync();
			System.out.println("TCP 服务器已启动，监听端口 6324");

			// UDP 服务器设置
			EventLoopGroup udpGroup = new NioEventLoopGroup();
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
				ChannelFuture udpFuture = udpBootstrap.bind(6324).sync();
				System.out.println("UDP 服务器已启动，监听端口 6324");

				// 等待两个服务器关闭
				tcpFuture.channel().closeFuture().sync();
				udpFuture.channel().closeFuture().sync();
			} finally {
				udpGroup.shutdownGracefully();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}
}
