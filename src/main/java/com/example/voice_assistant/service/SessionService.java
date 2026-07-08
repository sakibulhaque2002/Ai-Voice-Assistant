package com.example.voice_assistant.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.voice_assistant.model.Option;
import com.example.voice_assistant.model.Question;
import com.example.voice_assistant.model.Session;

import lombok.RequiredArgsConstructor;

/**
 * Owns questionnaire session state and the user-answer flow: validate/classify the
 * answer, store it under the question's field name, then move to whichever question
 * nextQuestionId points to (or finish if it's null). Sessions live only in memory, as
 * required for this proof of concept - no database.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final QuestionService questionService;

	private final AIService aiService;

	public Session createSession() {
		Question startQuestion = questionService.getStartQuestion();
		Session session = new Session(UUID.randomUUID().toString(), startQuestion.getId(), new LinkedHashMap<>(),
				false);
		sessions.put(session.getSessionId(), session);
		return session;
	}

	public Session getSession(String sessionId) {
		Session session = sessions.get(sessionId);
		if (session == null) {
			throw new SessionNotFoundException(sessionId);
		}
		return session;
	}

	public Session submitAnswer(String sessionId, String rawAnswer) {
		Session session = getSession(sessionId);
		if (session.isCompleted()) {
			throw new QuestionnaireCompletedException(sessionId);
		}

		Question currentQuestion = questionService.getQuestionById(session.getCurrentQuestionId());
		Option chosenOption = aiService.resolveAnswer(currentQuestion, session.getCollectedAnswers(), rawAnswer);

		session.getCollectedAnswers().put(currentQuestion.getField(), chosenOption.getValue());

		Question nextQuestion = questionService.resolveNextQuestion(chosenOption);
		if (nextQuestion == null) {
			session.setCompleted(true);
			session.setCurrentQuestionId(null);
		}
		else {
			session.setCurrentQuestionId(nextQuestion.getId());
		}

		return session;
	}

	/**
	 * @return the question the session is currently waiting on, or null if completed.
	 */
	public Question getCurrentQuestion(Session session) {
		if (session.isCompleted() || session.getCurrentQuestionId() == null) {
			return null;
		}
		return questionService.getQuestionById(session.getCurrentQuestionId());
	}

	/**
	 * Drops a session from memory, e.g. once its voice WebSocket connection closes.
	 */
	public void endSession(String sessionId) {
		sessions.remove(sessionId);
	}

}
