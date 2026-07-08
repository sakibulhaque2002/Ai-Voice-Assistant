package com.example.voice_assistant.util;

import com.example.voice_assistant.dto.ClassificationResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Deserializes the LLM's raw text output into a ClassificationResult. Models are
 * instructed to return raw JSON only, but some occasionally wrap it in a markdown code
 * fence anyway - this strips that incidental formatting before handing off to Jackson.
 * Plain text answers are never parsed; anything that isn't valid JSON becomes
 * "unresolved" so the caller falls back to asking the user again.
 */
public final class ClassificationResponseParser {

	private ClassificationResponseParser() {
	}

	public static ClassificationResult parse(ObjectMapper objectMapper, String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return ClassificationResult.unresolved();
		}
		String json = stripMarkdownFence(rawText.strip());
		try {
			return objectMapper.readValue(json, ClassificationResult.class);
		}
		catch (JacksonException ex) {
			return ClassificationResult.unresolved();
		}
	}

	private static String stripMarkdownFence(String text) {
		if (!text.startsWith("```")) {
			return text;
		}
		int firstNewline = text.indexOf('\n');
		int lastFence = text.lastIndexOf("```");
		if (firstNewline == -1 || lastFence <= firstNewline) {
			return text;
		}
		return text.substring(firstNewline + 1, lastFence).strip();
	}

}
