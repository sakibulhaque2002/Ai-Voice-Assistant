package com.example.voice_assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed application configuration bound from the "app.*" prefix in application.properties.
 * {@code ai.provider} is the single switch that selects which {@code AIProvider} handles
 * answer classification - see AIProviderFactory. {@code voice} configures the Gemini Live
 * API session used to listen to and transcribe spoken answers - see GeminiSpeechService.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Ai ai, Questionnaire questionnaire, Voice voice) {

	public record Ai(String provider) {
	}

	public record Questionnaire(String resource) {
	}

	public record Voice(String apiKey, String liveModel) {
	}

}
