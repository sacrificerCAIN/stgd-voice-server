package com.stgd.voice.server.component;

import com.stgd.voice.config.ServerConfig;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.BindException;

@Component
public class PortCheckListener implements ApplicationListener<ApplicationFailedEvent> {

	@Autowired
	private ServerConfig serverConfig;

	@SneakyThrows
	@Override
	public void onApplicationEvent(ApplicationFailedEvent event) {
		Throwable ex = event.getException();
		while (ex != null) {
			if (ex instanceof BindException) {
				System.out.println("端口" + serverConfig.getPort() + "已被占用，程序会在5秒后关闭");
				Thread.sleep(5000);
				System.exit(1);
				break;
			}
			ex = ex.getCause();
		}
	}
}

