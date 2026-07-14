package com.example.voice_assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed application configuration bound from the "app.*" prefix in application.properties.
 * {@code questionnaire} points at the JSON that defines the whole question flow;
 * {@code voice} configures the single Gemini Live session that speaks every question,
 * listens to the spoken answer and returns the matched option as a function call - see
 * GeminiSpeechService.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Questionnaire questionnaire, Voice voice) {

	public record Questionnaire(String resource) {
	}

	public record Voice(String apiKey, String liveModel, String voiceName) {
	}

}
