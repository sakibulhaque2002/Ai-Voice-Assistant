package com.example.voice_assistant.dto;

/**
 * The structured JSON the LLM must return for a classification call. Deserialized
 * directly from the model's raw text output - never parsed as free-form text.
 */
public record ClassificationResult(boolean valid, String selectedOption, double confidence) {

	public static ClassificationResult unresolved() {
		return new ClassificationResult(false, null, 0.0);
	}

}
