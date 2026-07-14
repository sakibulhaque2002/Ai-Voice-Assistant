package com.example.voice_assistant.exception;

/**
 * Thrown when an answer is submitted for a session that has already finished the
 * questionnaire.
 */
public class QuestionnaireCompletedException extends RuntimeException {

	public QuestionnaireCompletedException(String sessionId) {
		super("Session '" + sessionId + "' has already completed the questionnaire");
	}

}
