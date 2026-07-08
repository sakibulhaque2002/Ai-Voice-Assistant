package com.example.voice_assistant.provider;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.stereotype.Component;

import com.example.voice_assistant.dto.ClassificationRequest;
import com.example.voice_assistant.dto.ClassificationResult;
import com.example.voice_assistant.util.ClassificationPromptFactory;
import com.example.voice_assistant.util.ClassificationResponseParser;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifies answers using Google Gemini (via the Google GenAI Developer API). JSON
 * output is enforced at the model level through spring.ai.google.genai.chat.response-mime-type.
 */
@Component
@RequiredArgsConstructor
public class GeminiProvider implements AIProvider {

	private final GoogleGenAiChatModel chatModel;

	private final ObjectMapper objectMapper;

	@Override
	public ClassificationResult classify(ClassificationRequest request) {
		try {
			List<Message> messages = ClassificationPromptFactory.buildMessages(objectMapper, request);
			ChatResponse response = chatModel.call(new Prompt(messages));
			String text = response.getResult().getOutput().getText();
			return ClassificationResponseParser.parse(objectMapper, text);
		}
		catch (Exception ex) {
			throw new AIProviderException("gemini", ex);
		}
	}

}
