package com.example.voice_assistant.provider;

import com.example.voice_assistant.dto.ClassificationRequest;
import com.example.voice_assistant.dto.ClassificationResult;

/**
 * A single LLM backend capable of classifying a user's natural language answer into one
 * of a question's allowed options. Implementations only do classification - the
 * questionnaire flow itself is controlled entirely by the backend.
 */
public interface AIProvider {

	ClassificationResult classify(ClassificationRequest request);

}
