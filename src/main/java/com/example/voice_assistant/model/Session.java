package com.example.voice_assistant.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * In-memory questionnaire session state. Never serialized directly to clients -
 * controllers map it to response DTOs so the API shape stays independent of internal state.
 */
@Data
@AllArgsConstructor
public class Session {

	private String sessionId;

	private Integer currentQuestionId;

	private Map<String, Object> collectedAnswers;

	private boolean completed;

}
