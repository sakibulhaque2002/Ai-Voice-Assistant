package com.example.voice_assistant.voice;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;

/**
 * Registers the single /voice WebSocket endpoint that carries the entire voice
 * questionnaire protocol (see VoiceMessages for the control frame shapes).
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class VoiceWebSocketConfig implements WebSocketConfigurer {

	private final VoiceSessionHandler voiceSessionHandler;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(voiceSessionHandler, "/voice");
	}

}
