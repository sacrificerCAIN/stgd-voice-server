package com.stgd.voice;

import com.stgd.voice.server.NettyMain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StgdVoiceApplication implements CommandLineRunner {

	@Autowired
	private NettyMain nettyMain;

	public static void main(String[] args) {
		SpringApplication.run(StgdVoiceApplication.class, args);
	}
	@Override
	public void run(String... args){
		nettyMain.start();
	}
}
