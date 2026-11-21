package com.stgd.voice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

@Component
@ConfigurationProperties(prefix = "server")
@Data
public class ServerConfig {

	private Integer port;

	/** 对外展示的主机名/IP；为空时自动获取本机 IPv4 地址 */
	private String host;

	private Integer tcpPort;

	private Integer udpPort;

	private Integer wsPort;

	/**
	 * TCP/WebSocket 连接读空闲超时时间（秒）。超过此时间未收到客户端数据则关闭连接。
	 * 默认 60 秒；设置为 0 或负数表示不启用。
	 */
	private Integer idleTimeoutSeconds = 60;

	/**
	 * 未显式配置 server.host 时，动态获取本机 IP：
	 * 优先取默认路由出口地址，其次取第一个可用网卡的 IPv4，最后退回 localhost。
	 * 公网/NAT 环境（如云服务器）仍建议显式配置 server.host。
	 */
	public String getHost() {
		if (host != null && !host.trim().isEmpty()) {
			return host.trim();
		}
		String ip = routeIp();
		return ip != null ? ip : "localhost";
	}

	/** 通过 UDP connect 取得默认路由出口 IP（仅设置路由，不会真正发包） */
	private static String routeIp() {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(InetAddress.getByName("8.8.8.8"), 53);
			InetAddress local = socket.getLocalAddress();
			if (local != null && !local.isLoopbackAddress() && !local.isAnyLocalAddress()) {
				return local.getHostAddress();
			}
		} catch (Exception ignored) {
		}
		return interfaceIp();
	}

	/** 遍历可用网卡，取第一个非回环/非虚拟的 IPv4 地址 */
	private static String interfaceIp() {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface ni = interfaces.nextElement();
				if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
					continue;
				}
				Enumeration<InetAddress> addresses = ni.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress addr = addresses.nextElement();
					if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
						return addr.getHostAddress();
					}
				}
			}
		} catch (Exception ignored) {
		}
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (Exception ignored) {
			return null;
		}
	}
}