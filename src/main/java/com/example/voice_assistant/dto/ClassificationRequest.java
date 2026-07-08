package com.example.voice_assistant.dto;

import java.util.List;
import java.util.Map;

/**
 * Everything sent to the LLM for a single classification call. Deliberately minimal -
 * only the current question, its allowed options, answers collected so far, and the
 * latest user answer. No conversation history is ever sent.
 */
public record ClassificationRequest(Map<String, Object> answers, String question, List<String> allowedOptions,
		String userAnswer) {
}
