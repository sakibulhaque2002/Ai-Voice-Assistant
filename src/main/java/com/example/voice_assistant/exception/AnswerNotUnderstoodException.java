package com.example.voice_assistant.exception;

/**
 * Thrown when the spoken answer's record_answer label doesn't match any of the current
 * question's allowed options.
 */
public class AnswerNotUnderstoodException extends RuntimeException {

	public AnswerNotUnderstoodException(String question) {
		super("Could not understand your answer to: \"" + question + "\". Please rephrase and try again.");
	}

}
