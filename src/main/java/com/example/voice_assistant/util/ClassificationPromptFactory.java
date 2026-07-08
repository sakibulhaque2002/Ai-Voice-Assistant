package com.example.voice_assistant.util;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.example.voice_assistant.dto.ClassificationRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * Builds the two-message prompt sent to whichever AIProvider is active. Kept identical
 * across providers so GeminiProvider and OpenRouterProvider only differ in which
 * ChatModel they call.
 */
public final class ClassificationPromptFactory {

	private static final String SYSTEM_PROMPT = """
			You are a strict answer classifier for a questionnaire system.
			You will be given the current question, its allowed options, the answers already \
			collected so far, and the user's latest natural language reply.

			Decide which single allowed option the user's reply corresponds to.

			Rules:
			- Choose only from the allowed options. Never invent an option that is not listed.
			- Judge based on the meaning of the latest answer. Use the collected answers only as \
			background context, not as something to classify.
			- If you cannot confidently determine the intended option, set "valid" to false and \
			"selectedOption" to null.
			- Respond with ONLY one JSON object and nothing else: no markdown, no code fences, no \
			explanation.

			Required JSON shape:
			{"valid": true|false, "selectedOption": "<one of the allowed options>"|null, "confidence": 0.0-1.0}
			""";

	private ClassificationPromptFactory() {
	}

	public static List<Message> buildMessages(ObjectMapper objectMapper, ClassificationRequest request) {
		String payload = objectMapper.writeValueAsString(request);
		return List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(payload));
	}

}
