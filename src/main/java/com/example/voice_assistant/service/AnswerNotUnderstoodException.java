package com.example.voice_assistant.service;

/**
 * Thrown when neither the exact-match check nor the AI provider could confidently map
 * the user's answer to one of the current question's allowed options.
 */
public class AnswerNotUnderstoodException extends RuntimeException {

	public AnswerNotUnderstoodException(String question) {
		super("Could not understand your answer to: \"" + question + "\". Please rephrase and try again.");
	}

}
