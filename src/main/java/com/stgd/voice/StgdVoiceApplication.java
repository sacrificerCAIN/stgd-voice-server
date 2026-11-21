package com.stgd.voice;

import com.stgd.voice.server.NettyMain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.Resource;

@SpringBootApplication
public class StgdVoiceApplication implements CommandLineRunner {

	@Resource
	private NettyMain nettyMain;

	public static void main(String[] args) {
		SpringApplication.run(StgdVoiceApplication.class, args);
	}
	@Override
	public void run(String... args){
		// start() 会阻塞在 Netty 监听 Channel 的 closeFuture 上，必须放在独立线程执行，
		// 否则 SpringApplication.run() 永不返回、ApplicationReadyEvent 永不发布。
		new Thread(nettyMain::start, "netty-main").start();
	}
}
