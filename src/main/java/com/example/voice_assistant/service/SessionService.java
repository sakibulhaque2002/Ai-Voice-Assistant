package com.example.voice_assistant.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.voice_assistant.exception.AnswerNotUnderstoodException;
import com.example.voice_assistant.exception.QuestionnaireCompletedException;
import com.example.voice_assistant.exception.SessionNotFoundException;
import com.example.voice_assistant.model.Option;
import com.example.voice_assistant.model.Question;
import com.example.voice_assistant.model.Session;

import lombok.RequiredArgsConstructor;

/**
 * Owns questionnaire session state and the answer flow. The Gemini Live model has already
 * mapped the spoken answer to one of the current question's option labels (via the
 * record_answer function call), so this service just resolves that label to its option,
 * stores the option's value under the question's field name, and moves to whichever
 * question nextQuestionId points to (or finishes if it's null). Sessions live only in
 * memory, as required for this proof of concept - no database.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final QuestionService questionService;

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

	/**
	 * Records the answer the Live model chose for the current question. {@code optionLabel} is
	 * the label it returned via the record_answer function call; it must match one of the
	 * current question's options exactly (case-insensitively) or an
	 * {@link AnswerNotUnderstoodException} is thrown so the caller can re-ask.
	 */
	public Session submitAnswer(String sessionId, String optionLabel) {
		Session session = getSession(sessionId);
		if (session.isCompleted()) {
			throw new QuestionnaireCompletedException(sessionId);
		}

		Question currentQuestion = questionService.getQuestionById(session.getCurrentQuestionId());
		Option chosenOption = questionService.findOptionByLabel(currentQuestion, optionLabel)
			.orElseThrow(() -> new AnswerNotUnderstoodException(currentQuestion.getQuestion()));

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

}
