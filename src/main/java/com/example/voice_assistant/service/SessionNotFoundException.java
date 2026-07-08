package com.example.voice_assistant.service;

/**
 * Thrown when a sessionId doesn't match any known in-memory session.
 */
public class SessionNotFoundException extends RuntimeException {

	public SessionNotFoundException(String sessionId) {
		super("No session found with id '" + sessionId + "'");
	}

}
