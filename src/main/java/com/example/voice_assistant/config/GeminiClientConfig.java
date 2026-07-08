package com.example.voice_assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

import lombok.RequiredArgsConstructor;

/**
 * The raw google-genai Java client used for Gemini's Live API (speech input) and one-shot
 * audio generation (speech output) - capabilities the Spring AI starter does not expose.
 * The v1beta API version is required for the Live API, matching the Python Live API
 * quickstart this service is modeled on.
 */
@Configuration
@RequiredArgsConstructor
public class GeminiClientConfig {

	private final AppProperties appProperties;

	@Bean(destroyMethod = "close")
	public Client googleGenAiClient() {
		return Client.builder()
			.apiKey(appProperties.voice().apiKey())
			.httpOptions(HttpOptions.builder().apiVersion("v1beta").build())
			.build();
	}

}
