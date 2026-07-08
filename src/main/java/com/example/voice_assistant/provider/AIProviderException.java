package com.example.voice_assistant.provider;

/**
 * Thrown when the underlying LLM call itself fails (bad API key, network error, etc).
 * Kept distinct from "couldn't understand the answer" so misconfiguration surfaces
 * clearly instead of looking like a low-confidence classification.
 */
public class AIProviderException extends RuntimeException {

	public AIProviderException(String providerName, Throwable cause) {
		super("AI provider '" + providerName + "' call failed: " + cause.getMessage(), cause);
	}

}
