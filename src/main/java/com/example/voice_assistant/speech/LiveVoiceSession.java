package com.example.voice_assistant.speech;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.genai.AsyncSession;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps a single bidirectional Gemini Live session used for the whole voice questionnaire. The
 * same session does three jobs, one question at a time, with no other model involved:
 * <ol>
 * <li>{@link #askQuestion} sends the model a script that makes it speak one question aloud and,
 * after hearing the user's spoken reply, call the {@code record_answer} function with the option
 * label that matches - so the answer arrives already classified.</li>
 * <li>{@link #captureAnswer} streams the user's mic audio and completes with that function call's
 * chosen label (plus a best-effort transcript, purely for display).</li>
 * <li>{@link #speakLine} recites a plain line (a retry apology or the closing message).</li>
 * </ol>
 * The three operations always run one at a time in the order ask &rarr; capture &rarr; ask
 * again, so only one is ever pending on the underlying session at once.
 */
@Slf4j
public class LiveVoiceSession {

	/** Name of the function the model calls to hand back the classified answer. */
	static final String RECORD_ANSWER = "record_answer";

	static final String LABEL_ARG = "label";

	/** Label the model returns when the reply matches none of the options. */
	public static final String UNCLEAR = "unclear";

	private static final String AUDIO_MIME_TYPE = "audio/pcm;rate=16000";

	/**
	 * Absolute safety ceiling for a single answer capture. Normal completion happens well
	 * before this, when the model emits the record_answer function call once the user stops
	 * talking. This only bounds pathological cases (the user talking nonstop, or the function
	 * call never arriving) so a capture can never hang forever.
	 */
	private static final long CAPTURE_TIMEOUT_SECONDS = 45;

	/**
	 * How long to wait, after arming a capture, for the user to start saying anything before
	 * giving up and prompting them to answer again. Only a genuine stretch of nobody-speaking
	 * triggers a retry, so the assistant actually waits for the user instead of retrying the
	 * instant the question finishes.
	 */
	private static final long NO_SPEECH_TIMEOUT_MS = 12_000;

	/**
	 * Safety ceiling for a single spoken line: if the model never signals it has finished
	 * generating audio, don't hang the conversation forever.
	 */
	private static final long SPEAK_TIMEOUT_SECONDS = 30;

	/**
	 * This preview model sometimes ends a burst with audio (or a transcript) already delivered
	 * but never sends turnComplete/generationComplete afterwards - the session just goes quiet.
	 * If no further server message arrives for this long after we've seen activity, treat the
	 * spoken line as finished rather than riding out the full hard timeout.
	 */
	private static final long SILENCE_TIMEOUT_MS = 1500;

	private static final long SILENCE_CHECK_INTERVAL_MS = 250;

	/** The classified answer plus a best-effort transcript of what the user said (for display). */
	public record AnswerResult(String label, String transcript) {
	}

	private final AsyncSession session;

	private final Consumer<byte[]> outputAudioListener;

	private final Consumer<String> interimTranscriptListener;

	private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "voice-session-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	private final AtomicReference<StringBuilder> transcriptBuffer = new AtomicReference<>();

	private final AtomicReference<CompletableFuture<AnswerResult>> pendingCapture = new AtomicReference<>();

	private final AtomicReference<CompletableFuture<Void>> pendingSpeak = new AtomicReference<>();

	/**
	 * A record_answer call that arrived before {@link #captureAnswer} was armed (the model
	 * jumped straight from speaking to answering). Held here and delivered the moment the
	 * capture arms, so it is never lost.
	 */
	private final AtomicReference<FunctionCall> stashedAnswerCall = new AtomicReference<>();

	private final AtomicBoolean speakAudioReceived = new AtomicBoolean();

	private final AtomicBoolean speechDetected = new AtomicBoolean();

	private final AtomicReference<ScheduledFuture<?>> silenceCheck = new AtomicReference<>();

	private final AtomicInteger chunksSent = new AtomicInteger();

	private final AtomicLong bytesSent = new AtomicLong();

	private volatile long captureStartedAtMs;

	private volatile long lastActivityAtMs;

	public LiveVoiceSession(AsyncSession session, Consumer<byte[]> outputAudioListener,
			Consumer<String> interimTranscriptListener) {
		this.session = session;
		this.outputAudioListener = outputAudioListener;
		this.interimTranscriptListener = interimTranscriptListener;
		session.receive(this::onMessage);
		log.info("Live voice session opened");
	}

	/**
	 * Sends the model a script that makes it recite {@code questionText} aloud verbatim and then,
	 * once it has heard the user's reply, call record_answer with whichever of {@code optionLabels}
	 * matches (or {@link #UNCLEAR}). The returned future completes once the question has been
	 * spoken; the caller then arms {@link #captureAnswer} to receive the function call.
	 */
	public CompletableFuture<Void> askQuestion(String questionText, List<String> optionLabels) {
		return sendAndSpeak(questionScript(questionText, optionLabels));
	}

	/**
	 * Recites {@code text} aloud verbatim with no function-calling involved - used for the retry
	 * apology and the closing message. Completes once the line has been spoken.
	 */
	public CompletableFuture<Void> speakLine(String text) {
		return sendAndSpeak(reciteScript(text));
	}

	private CompletableFuture<Void> sendAndSpeak(String instruction) {
		speakAudioReceived.set(false);
		lastActivityAtMs = System.currentTimeMillis();
		CompletableFuture<Void> future = new CompletableFuture<>();
		pendingSpeak.set(future);
		ScheduledFuture<?> task = armWatchdog(() -> checkSpeakSilence(future));
		future.orTimeout(SPEAK_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, ex) -> {
			pendingSpeak.compareAndSet(future, null);
			disarmWatchdog(task);
			if (ex != null) {
				log.warn("Speaking a line failed or timed out (audioReceived={})", speakAudioReceived.get(), ex);
			}
		});
		session
			.sendClientContent(LiveSendClientContentParameters.builder()
				.turns(Content.fromParts(Part.fromText(instruction)))
				.turnComplete(true)
				.build())
			.exceptionally(ex -> {
				failSpeak(ex);
				return null;
			});
		return future;
	}

	/**
	 * The question script: this preview model is a conversational dialogue model, not a
	 * text-to-speech engine, so a bare turn like "Where are you from?" gets answered
	 * conversationally instead of recited. Framing every turn as an explicit script - speak this
	 * exact line, then wait, then classify the reply via record_answer - keeps it voicing the
	 * question rather than responding to it, and makes the answer come back already mapped to an
	 * option instead of as free-form audio we would then have to classify ourselves.
	 */
	private static String questionScript(String questionText, List<String> optionLabels) {
		return """
				You are voicing ONE question of an automated questionnaire. Follow these steps exactly:

				STEP 1 - Say this line aloud in English, word for word, then stop and wait. It is a \
				script for the human user to hear, not a question directed at you:
				"%s"

				STEP 2 - Listen to the user's spoken reply. It may be in English or Bangla.

				STEP 3 - Decide which ONE of these options best matches their reply:
				%s
				Then call the %s function with `%s` set to that option's exact text. If the reply \
				does not clearly match any option, call %s with `%s` set to "%s".

				Never say anything except the line in STEP 1. Never answer the question yourself. \
				Do not call %s until you have actually heard the user's reply.""".formatted(questionText,
				String.join("\n", optionLabels.stream().map(l -> "- " + l).toList()), RECORD_ANSWER, LABEL_ARG,
				RECORD_ANSWER, LABEL_ARG, UNCLEAR, RECORD_ANSWER);
	}

	private static String reciteScript(String text) {
		return "Recite the following line aloud, word-for-word, then stop. It is not a question "
				+ "or message for you to respond to - it is a script for the human user to hear:\n\n" + text;
	}

	/**
	 * Arms capture of the next answer. Call this once the question has been spoken, then stream
	 * mic audio via {@link #sendAudioChunk}. The returned future completes with the label the
	 * model chose via record_answer, or fails if the user says nothing within the no-speech
	 * timeout. If the model already emitted the call while speaking, it is delivered immediately.
	 */
	public CompletableFuture<AnswerResult> captureAnswer() {
		transcriptBuffer.set(new StringBuilder());
		speechDetected.set(false);
		chunksSent.set(0);
		bytesSent.set(0);
		captureStartedAtMs = System.currentTimeMillis();
		lastActivityAtMs = captureStartedAtMs;
		CompletableFuture<AnswerResult> future = new CompletableFuture<>();
		pendingCapture.set(future);
		log.info("Answer capture armed (no-speech {}ms, hard timeout {}s)", NO_SPEECH_TIMEOUT_MS,
				CAPTURE_TIMEOUT_SECONDS);
		ScheduledFuture<?> task = armWatchdog(() -> checkCaptureSilence(future));
		future.orTimeout(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, ex) -> {
			transcriptBuffer.set(null);
			pendingCapture.compareAndSet(future, null);
			disarmWatchdog(task);
			logCaptureOutcome(result, ex);
		});
		FunctionCall stashed = stashedAnswerCall.getAndSet(null);
		if (stashed != null) {
			log.info("Delivering record_answer that arrived before capture was armed");
			completeCapture(stashed);
		}
		return future;
	}

	public void sendAudioChunk(byte[] pcm16Mono16k) {
		chunksSent.incrementAndGet();
		bytesSent.addAndGet(pcm16Mono16k.length);
		Blob audio = Blob.builder().data(pcm16Mono16k).mimeType(AUDIO_MIME_TYPE).build();
		session.sendRealtimeInput(LiveSendRealtimeInputParameters.builder().audio(audio).build()).exceptionally(ex -> {
			log.warn("Failed to send audio chunk to Gemini Live", ex);
			failCapture(ex);
			return null;
		});
	}

	public void close() {
		session.close();
		watchdog.shutdownNow();
	}

	private ScheduledFuture<?> armWatchdog(Runnable check) {
		ScheduledFuture<?> task = watchdog.scheduleAtFixedRate(check, SILENCE_TIMEOUT_MS, SILENCE_CHECK_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
		ScheduledFuture<?> previous = silenceCheck.getAndSet(task);
		if (previous != null) {
			previous.cancel(false);
		}
		return task;
	}

	private void disarmWatchdog(ScheduledFuture<?> task) {
		task.cancel(false);
		silenceCheck.compareAndSet(task, null);
	}

	/**
	 * The capture watchdog: if nobody has said anything transcribable within
	 * {@link #NO_SPEECH_TIMEOUT_MS} of arming, give up so the caller can prompt the user again.
	 * Once speech is detected we wait for the record_answer call (bounded by the hard timeout).
	 */
	private void checkCaptureSilence(CompletableFuture<AnswerResult> future) {
		if (pendingCapture.get() != future) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!speechDetected.get() && now - captureStartedAtMs >= NO_SPEECH_TIMEOUT_MS) {
			log.info("No speech within {}ms of arming the capture - prompting the user to answer again",
					now - captureStartedAtMs);
			failCapture(new TimeoutException("no speech detected"));
		}
	}

	/**
	 * Mirrors the transcript silence guard for the speaking side: if we have received at least
	 * one chunk of output audio but the model has gone quiet without flagging the turn complete,
	 * treat the line as fully spoken rather than waiting out the full timeout.
	 */
	private void checkSpeakSilence(CompletableFuture<Void> future) {
		if (pendingSpeak.get() != future || !speakAudioReceived.get()) {
			return;
		}
		long idleMs = System.currentTimeMillis() - lastActivityAtMs;
		if (idleMs >= SILENCE_TIMEOUT_MS) {
			log.info("Speaking finalized after {}ms of silence with no completion signal from Gemini", idleMs);
			completeSpeak();
		}
	}

	private void onMessage(LiveServerMessage message) {
		if (message.setupComplete().isPresent()) {
			log.info("Live session setup complete");
		}
		message.goAway().ifPresent(goAway -> log.warn("Live session received goAway: {}", goAway));
		message.toolCall().ifPresent(this::handleToolCall);
		message.serverContent().ifPresent(content -> {
			if (pendingSpeak.get() != null) {
				handleSpeakContent(content);
			}
			else {
				handleListeningContent(content);
			}
		});
	}

	private void handleToolCall(LiveServerToolCall toolCall) {
		FunctionCall answerCall = toolCall.functionCalls()
			.orElse(List.of())
			.stream()
			.filter(call -> RECORD_ANSWER.equals(call.name().orElse("")))
			.findFirst()
			.orElse(null);
		if (answerCall == null) {
			log.warn("Ignoring unexpected tool call: {}", toolCall);
			return;
		}
		acknowledgeToolCall(answerCall);
		if (pendingCapture.get() != null) {
			completeCapture(answerCall);
		}
		else {
			// The model classified the answer before we armed the capture. Hold it so the very
			// next captureAnswer() delivers it instead of dropping it on the floor.
			log.info("record_answer arrived with no capture armed yet - stashing it");
			stashedAnswerCall.set(answerCall);
		}
	}

	/**
	 * Every function call must be answered so the model's turn resolves cleanly and it doesn't
	 * re-emit or hang. We drive the flow ourselves, so the response is a bare acknowledgement
	 * with willContinue=false to tell the model not to keep talking.
	 */
	private void acknowledgeToolCall(FunctionCall call) {
		FunctionResponse response = FunctionResponse.builder()
			.id(call.id().orElse(""))
			.name(RECORD_ANSWER)
			.response(Map.of("status", "recorded"))
			.willContinue(false)
			.build();
		session
			.sendToolResponse(LiveSendToolResponseParameters.builder().functionResponses(response).build())
			.exceptionally(ex -> {
				log.warn("Failed to send tool response", ex);
				return null;
			});
	}

	private void handleSpeakContent(LiveServerContent content) {
		lastActivityAtMs = System.currentTimeMillis();
		content.modelTurn().flatMap(Content::parts).ifPresent(parts -> {
			for (Part part : parts) {
				part.inlineData().flatMap(Blob::data).ifPresent(bytes -> {
					speakAudioReceived.set(true);
					outputAudioListener.accept(bytes);
				});
			}
		});
		boolean turnComplete = content.turnComplete().orElse(false);
		boolean generationComplete = content.generationComplete().orElse(false);
		// Only treat a completion signal as "finished speaking this line" once audio has actually
		// arrived. An empty completion here is the trailing turnComplete of the previous turn
		// landing in the freshly-armed speak (same bidirectional session); completing on it would
		// resolve before a single audio chunk is generated and the line would never be voiced.
		if ((turnComplete || generationComplete) && speakAudioReceived.get()) {
			completeSpeak();
		}
	}

	/**
	 * While listening, we only mine the input transcription (for a live on-screen display and to
	 * know the user has started talking); the authoritative answer arrives separately as the
	 * record_answer tool call. Any model audio during this phase is deliberately ignored so a
	 * stray acknowledgement never reaches the user's speakers.
	 */
	private void handleListeningContent(LiveServerContent content) {
		StringBuilder buffer = transcriptBuffer.get();
		if (buffer == null) {
			return;
		}
		String delta = content.inputTranscription().flatMap(Transcription::text).orElse(null);
		String interim = content.interimInputTranscription().flatMap(Transcription::text).orElse(null);
		if (delta != null) {
			buffer.append(delta);
		}
		if (delta != null || interim != null) {
			speechDetected.set(true);
			lastActivityAtMs = System.currentTimeMillis();
			String shown = buffer.isEmpty() ? interim : buffer.toString();
			if (shown != null && !shown.isBlank()) {
				interimTranscriptListener.accept(shown.strip());
			}
		}
	}

	private void completeCapture(FunctionCall answerCall) {
		String label = answerCall.args()
			.map(args -> args.get(LABEL_ARG))
			.map(Object::toString)
			.map(String::strip)
			.orElse(UNCLEAR);
		StringBuilder buffer = transcriptBuffer.getAndSet(null);
		String transcript = buffer == null ? "" : buffer.toString().strip();
		CompletableFuture<AnswerResult> future = pendingCapture.getAndSet(null);
		if (future != null) {
			future.complete(new AnswerResult(label, transcript));
		}
	}

	private void failCapture(Throwable ex) {
		transcriptBuffer.set(null);
		CompletableFuture<AnswerResult> future = pendingCapture.getAndSet(null);
		if (future != null) {
			future.completeExceptionally(ex);
		}
	}

	private void completeSpeak() {
		CompletableFuture<Void> future = pendingSpeak.getAndSet(null);
		if (future != null) {
			future.complete(null);
		}
	}

	private void failSpeak(Throwable ex) {
		CompletableFuture<Void> future = pendingSpeak.getAndSet(null);
		if (future != null) {
			future.completeExceptionally(ex);
		}
	}

	private void logCaptureOutcome(AnswerResult result, Throwable ex) {
		long elapsedMs = System.currentTimeMillis() - captureStartedAtMs;
		int chunks = chunksSent.get();
		long bytes = bytesSent.get();
		if (ex == null) {
			log.info("Answer captured in {}ms: label=\"{}\", transcript=\"{}\", audioChunksSent={}, audioBytesSent={}",
					elapsedMs, result.label(), result.transcript(), chunks, bytes);
			return;
		}
		Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
		if (cause instanceof TimeoutException) {
			log.warn("Answer capture timed out after {}ms. audioChunksSent={}, audioBytesSent={}. {}", elapsedMs,
					chunks, bytes,
					chunks == 0 ? "No audio was ever sent - check the browser is actually streaming."
							: "Audio was sent but no record_answer call arrived in time.");
		}
		else {
			log.warn("Answer capture failed after {}ms: audioChunksSent={}, audioBytesSent={}", elapsedMs, chunks,
					bytes, cause);
		}
	}

}
