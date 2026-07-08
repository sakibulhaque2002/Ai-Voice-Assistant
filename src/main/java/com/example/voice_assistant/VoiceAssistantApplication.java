package com.example.voice_assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VoiceAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(VoiceAssistantApplication.class, args);
	}

}
