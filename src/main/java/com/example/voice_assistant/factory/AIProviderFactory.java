package com.example.voice_assistant.factory;

import org.springframework.stereotype.Component;

import com.example.voice_assistant.config.AppProperties;
import com.example.voice_assistant.provider.AIProvider;
import com.example.voice_assistant.provider.GeminiProvider;
import com.example.voice_assistant.provider.OpenRouterProvider;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the active AIProvider from a single configuration property (app.ai.provider).
 * Switching providers never requires touching any other code or property.
 */
@Component
@RequiredArgsConstructor
public class AIProviderFactory {

	private final GeminiProvider geminiProvider;

	private final OpenRouterProvider openRouterProvider;

	private final AppProperties appProperties;

	public AIProvider getProvider() {
		String provider = appProperties.ai().provider();
		return switch (provider.toLowerCase()) {
			case "gemini" -> geminiProvider;
			case "openrouter" -> openRouterProvider;
			default -> throw new IllegalStateException(
					"Unknown app.ai.provider '" + provider + "'. Supported values: gemini, openrouter.");
		};
	}

}
