package com.example.voice_assistant.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.voice_assistant.dto.ErrorResponse;
import com.example.voice_assistant.dto.QuestionDto;
import com.example.voice_assistant.dto.SessionResponse;
import com.example.voice_assistant.model.Question;
import com.example.voice_assistant.model.Session;
import com.example.voice_assistant.service.SessionNotFoundException;
import com.example.voice_assistant.service.SessionService;

import lombok.RequiredArgsConstructor;

/**
 * Thin REST layer for reading back a voice questionnaire session collected via the /voice
 * WebSocket.
 */
@RestController
@RequiredArgsConstructor
public class SessionController {

	private final SessionService sessionService;

	@GetMapping("/session/{sessionId}")
	public SessionResponse getSession(@PathVariable String sessionId) {
		Session session = sessionService.getSession(sessionId);
		Question current = sessionService.getCurrentQuestion(session);
		return new SessionResponse(session.getSessionId(), session.isCompleted(), session.getCollectedAnswers(),
				current == null ? null : QuestionDto.from(current));
	}

	@ExceptionHandler(SessionNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
	}

}
