package com.example.voice_assistant.voice;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.example.voice_assistant.dto.QuestionDto;
import com.example.voice_assistant.model.Question;
import com.example.voice_assistant.model.Session;
import com.example.voice_assistant.provider.AIProviderException;
import com.example.voice_assistant.service.AnswerNotUnderstoodException;
import com.example.voice_assistant.service.QuestionnaireCompletedException;
import com.example.voice_assistant.service.SessionNotFoundException;
import com.example.voice_assistant.service.SessionService;
import com.example.voice_assistant.speech.GeminiSpeechService;
import com.example.voice_assistant.speech.LiveTranscriptionSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-connection orchestrator that drives the questionnaire entirely by voice, hands-free after
 * a single "start" click. The questionnaire flow itself (branching, exact-match short-circuit,
 * AI classification, confidence threshold) is untouched from the text-based ai-questionnaire
 * app - this class only adds a spoken front end: each question is spoken aloud by Gemini, the
 * user's spoken reply is captured and fed into the same SessionService.submitAnswer used by the
 * text app, and an answer that isn't understood triggers a spoken retry prompt instead of a
 * click-to-retry - looping until either a valid answer arrives or every field has been
 * collected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceSessionHandler extends AbstractWebSocketHandler {

	private static final String STATE_KEY = "voiceState";

	private static final int SEND_TIME_LIMIT_MS = 30_000;

	private static final int SEND_BUFFER_SIZE_BYTES = 512 * 1024;

	private static final String RETRY_NO_SPEECH_MESSAGE = "Sorry, I didn't hear anything.";

	private static final String RETRY_TIMEOUT_MESSAGE = "Sorry, I didn't catch that.";

	private static final String RETRY_NOT_UNDERSTOOD_MESSAGE = "Sorry, I didn't get your answer.";

	private static final String CLOSING_MESSAGE = "Thank you. That's everything I needed - the questionnaire is now complete.";

	private final SessionService sessionService;

	private final GeminiSpeechService speechService;

	private final ObjectMapper objectMapper;

	/**
	 * Answer classification is a blocking LLM HTTP call, and a captured answer resolves on the
	 * Gemini Live session's websocket read thread. Running the classification there would stall
	 * that connection's inbound message processing for the duration of the call, so the answer
	 * handling is offloaded here. Cached + daemon: threads are I/O-bound (mostly waiting on the
	 * LLM) and must never keep the JVM alive on shutdown.
	 */
	private final ExecutorService answerProcessingExecutor = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r, "voice-answer-processing");
		thread.setDaemon(true);
		return thread;
	});

	@PreDestroy
	void shutdown() {
		answerProcessingExecutor.shutdownNow();
	}

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
		LiveTranscriptionSession liveSession = state(wsSession).liveSession;
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
		// The questionnaire session is intentionally left in SessionService after disconnect
		// (rather than removed) so its collected answers stay readable via
		// GET /session/{sessionId} once the conversation has ended.
	}

	private void handleStart(ConnectionState state) {
		if (state.questionnaireSession != null) {
			return;
		}
		state.questionnaireSession = sessionService.createSession();
		sendJson(state, new VoiceMessages.Started(state.questionnaireSession.getSessionId()));
		speechService.openLiveTranscriptionSession(chunk -> sendAudioChunk(state, chunk)).thenAccept(liveSession -> {
			state.liveSession = liveSession;
			askCurrentQuestion(state);
		}).exceptionally(ex -> {
			sendError(state, "Could not start voice session: " + describeError(ex));
			return null;
		});
	}

	/**
	 * Speaks the current question (or, once every field has been collected, the closing
	 * message) and then either arms the next answer capture or ends the conversation. This is
	 * the single loop that keeps the conversation going without the browser ever needing to
	 * click anything again.
	 */
	private void askCurrentQuestion(ConnectionState state) {
		Question current = sessionService.getCurrentQuestion(state.questionnaireSession);
		if (current == null) {
			speak(state, CLOSING_MESSAGE, () -> sendJson(state, new VoiceMessages.Completed()));
			return;
		}
		sendJson(state, new VoiceMessages.Question(QuestionDto.from(current)));
		speak(state, current.getQuestion(), () -> beginListening(state));
	}

	private void speak(ConnectionState state, String text, Runnable onSpoken) {
		sendJson(state, new VoiceMessages.Speaking());
		state.liveSession.speak(text).whenComplete((ignored, ex) -> {
			if (ex != null) {
				sendError(state, "Could not speak the next line: " + describeError(ex));
				return;
			}
			onSpoken.run();
		});
	}

	private void beginListening(ConnectionState state) {
		sendJson(state, new VoiceMessages.Listening());
		// whenCompleteAsync: onAnswerTranscribed runs a blocking LLM classification, and the
		// capture future resolves on the Gemini Live read thread - keep that work off it.
		state.liveSession.captureNextAnswer().whenCompleteAsync((transcript, ex) -> {
			if (ex != null) {
				retry(state, RETRY_TIMEOUT_MESSAGE);
				return;
			}
			onAnswerTranscribed(state, transcript);
		}, answerProcessingExecutor);
	}

	private void onAnswerTranscribed(ConnectionState state, String transcript) {
		sendJson(state, new VoiceMessages.Transcript(transcript));
		if (transcript == null || transcript.isBlank()) {
			retry(state, RETRY_NO_SPEECH_MESSAGE);
			return;
		}
		try {
			Session updated = sessionService.submitAnswer(state.questionnaireSession.getSessionId(), transcript);
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
		catch (AIProviderException ex) {
			sendError(state, "The AI provider call failed: " + ex.getMessage());
		}
	}

	/**
	 * Speaks a short apology and then re-asks the current question verbatim before listening
	 * again, e.g. "Sorry, I didn't get your answer." followed by "Where are you from?" - never
	 * a single line combining the two, and never a rephrasing of the question.
	 */
	private void retry(ConnectionState state, String apology) {
		sendJson(state, new VoiceMessages.NotUnderstood(apology));
		Question current = sessionService.getCurrentQuestion(state.questionnaireSession);
		speak(state, apology, () -> speak(state, current.getQuestion(), () -> beginListening(state)));
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

		private volatile LiveTranscriptionSession liveSession;

		private ConnectionState(WebSocketSession session) {
			this.session = session;
		}

	}

}
