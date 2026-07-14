package com.example.voice_assistant.voice;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.example.voice_assistant.dto.QuestionDto;
import com.example.voice_assistant.exception.AnswerNotUnderstoodException;
import com.example.voice_assistant.exception.QuestionnaireCompletedException;
import com.example.voice_assistant.exception.SessionNotFoundException;
import com.example.voice_assistant.model.Question;
import com.example.voice_assistant.model.Session;
import com.example.voice_assistant.service.QuestionService;
import com.example.voice_assistant.service.SessionService;
import com.example.voice_assistant.speech.GeminiSpeechService;
import com.example.voice_assistant.speech.LiveVoiceSession;
import com.example.voice_assistant.speech.LiveVoiceSession.AnswerResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-connection orchestrator that runs the whole questionnaire by voice, hands-free after a
 * single "start" click. The branching flow itself is untouched from the text-based questionnaire
 * (SessionService walks nextQuestionId); this class only adds the spoken front end. For each
 * question it asks the single Gemini Live session to speak the question and, after hearing the
 * reply, hand the matched option back as a record_answer function call - which is fed straight
 * into SessionService with no separate classifier in between. An answer the model can't map to
 * an option (or a silent user) triggers a spoken retry, looping until either a valid answer
 * arrives or every field has been collected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceSessionHandler extends AbstractWebSocketHandler {

	private static final String STATE_KEY = "voiceState";

	private static final int SEND_TIME_LIMIT_MS = 30_000;

	private static final int SEND_BUFFER_SIZE_BYTES = 512 * 1024;

	private static final String RETRY_NO_SPEECH_MESSAGE = "Sorry, I didn't hear anything.";

	private static final String RETRY_NOT_UNDERSTOOD_MESSAGE = "Sorry, I didn't get your answer.";

	private static final String CLOSING_MESSAGE = "Thank you. That's everything I needed - the questionnaire is now complete.";

	private final SessionService sessionService;

	private final QuestionService questionService;

	private final GeminiSpeechService speechService;

	private final ObjectMapper objectMapper;

	@Override
	public void afterConnectionEstablished(WebSocketSession wsSession) {
		WebSocketSession threadSafeSession = new ConcurrentWebSocketSessionDecorator(wsSession, SEND_TIME_LIMIT_MS,
				SEND_BUFFER_SIZE_BYTES);
		wsSession.getAttributes().put(STATE_KEY, new ConnectionState(threadSafeSession));
	}

	@Override
	protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
		ConnectionState state = state(wsSession);
		VoiceMessages.Inbound inbound;
		try {
			inbound = objectMapper.readValue(message.getPayload(), VoiceMessages.Inbound.class);
		}
		catch (Exception ex) {
			sendError(state, "Malformed control message");
			return;
		}
		if ("start".equals(inbound.type())) {
			handleStart(state);
		}
		else {
			sendError(state, "Unknown control message type: " + inbound.type());
		}
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) {
		LiveVoiceSession liveSession = state(wsSession).liveSession;
		if (liveSession == null) {
			return;
		}
		byte[] chunk = new byte[message.getPayloadLength()];
		message.getPayload().get(chunk);
		liveSession.sendAudioChunk(chunk);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
		ConnectionState state = (ConnectionState) wsSession.getAttributes().get(STATE_KEY);
		if (state == null) {
			return;
		}
		if (state.liveSession != null) {
			state.liveSession.close();
		}
	}

	private void handleStart(ConnectionState state) {
		if (state.questionnaireSession != null) {
			return;
		}
		state.questionnaireSession = sessionService.createSession();
		sendJson(state, new VoiceMessages.Started(state.questionnaireSession.getSessionId()));
		speechService.openSession(chunk -> sendAudioChunk(state, chunk), text -> sendJson(state, new VoiceMessages.Transcript(text)))
			.thenAccept(liveSession -> {
				state.liveSession = liveSession;
				askCurrentQuestion(state);
			})
			.exceptionally(ex -> {
				sendError(state, "Could not start voice session: " + describeError(ex));
				return null;
			});
	}

	/**
	 * Speaks the current question (or, once every field has been collected, the closing message)
	 * and then either arms the next answer capture or ends the conversation. This is the single
	 * loop that keeps the conversation going without the browser ever clicking anything again.
	 */
	private void askCurrentQuestion(ConnectionState state) {
		Question current = sessionService.getCurrentQuestion(state.questionnaireSession);
		if (current == null) {
			sendJson(state, new VoiceMessages.Speaking());
			state.liveSession.speakLine(CLOSING_MESSAGE).whenComplete((ignored, ex) -> {
				if (ex != null) {
					log.warn("Failed to speak closing message", ex);
				}
				sendJson(state, new VoiceMessages.Completed());
			});
			return;
		}
		sendJson(state, new VoiceMessages.Question(QuestionDto.from(current)));
		sendJson(state, new VoiceMessages.Speaking());
		List<String> labels = questionService.getAllowedOptionLabels(current);
		state.liveSession.askQuestion(current.getQuestion(), labels).whenComplete((ignored, ex) -> {
			if (ex != null) {
				sendError(state, "Could not speak the next question: " + describeError(ex));
				return;
			}
			beginListening(state);
		});
	}

	private void beginListening(ConnectionState state) {
		sendJson(state, new VoiceMessages.Listening());
		state.liveSession.captureAnswer().whenComplete((result, ex) -> {
			if (ex != null) {
				retry(state, RETRY_NO_SPEECH_MESSAGE);
				return;
			}
			onAnswer(state, result);
		});
	}

	private void onAnswer(ConnectionState state, AnswerResult result) {
		if (!result.transcript().isBlank()) {
			sendJson(state, new VoiceMessages.Transcript(result.transcript()));
		}
		if (LiveVoiceSession.UNCLEAR.equalsIgnoreCase(result.label())) {
			retry(state, RETRY_NOT_UNDERSTOOD_MESSAGE);
			return;
		}
		try {
			Session updated = sessionService.submitAnswer(state.questionnaireSession.getSessionId(), result.label());
			state.questionnaireSession = updated;
			sendJson(state, new VoiceMessages.Answers(updated.isCompleted(), updated.getCollectedAnswers()));
			askCurrentQuestion(state);
		}
		catch (AnswerNotUnderstoodException ex) {
			retry(state, RETRY_NOT_UNDERSTOOD_MESSAGE);
		}
		catch (SessionNotFoundException | QuestionnaireCompletedException ex) {
			sendError(state, ex.getMessage());
		}
	}

	/**
	 * Speaks a short apology, then re-asks the current question (which re-arms the model to call
	 * record_answer for the next reply) before listening again - e.g. "Sorry, I didn't get your
	 * answer." followed by the question, never a single combined line.
	 */
	private void retry(ConnectionState state, String apology) {
		sendJson(state, new VoiceMessages.NotUnderstood(apology));
		sendJson(state, new VoiceMessages.Speaking());
		state.liveSession.speakLine(apology).whenComplete((ignored, ex) -> {
			if (ex != null) {
				sendError(state, "Could not speak the retry prompt: " + describeError(ex));
				return;
			}
			askCurrentQuestion(state);
		});
	}

	private void sendJson(ConnectionState state, Object payload) {
		try {
			state.session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
		}
		catch (IOException ex) {
			log.warn("Failed to send voice control message", ex);
		}
	}

	private void sendAudioChunk(ConnectionState state, byte[] chunk) {
		try {
			state.session.sendMessage(new BinaryMessage(chunk));
		}
		catch (IOException ex) {
			log.warn("Failed to send spoken audio chunk to browser", ex);
		}
	}

	private void sendError(ConnectionState state, String message) {
		sendJson(state, new VoiceMessages.Error(message));
	}

	private static String describeError(Throwable ex) {
		Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
		return cause.getMessage() != null ? cause.getMessage() : cause.toString();
	}

	private ConnectionState state(WebSocketSession wsSession) {
		return (ConnectionState) wsSession.getAttributes().get(STATE_KEY);
	}

	private static final class ConnectionState {

		private final WebSocketSession session;

		private volatile Session questionnaireSession;

		private volatile LiveVoiceSession liveSession;

		private ConnectionState(WebSocketSession session) {
			this.session = session;
		}

	}

}
