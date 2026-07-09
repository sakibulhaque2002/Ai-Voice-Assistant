package com.example.voice_assistant.speech;

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
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps a single bidirectional Gemini Live AsyncSession used for the whole voice questionnaire
 * conversation: {@link #speak} sends a line of text and streams back the model's spoken audio,
 * while {@link #captureNextAnswer} streams the user's mic audio and returns its transcript. One
 * instance is held for the lifetime of a browser connection and the two operations always
 * alternate one at a time - speak, then listen, then speak again - so only one of them is ever
 * "pending" on the underlying session at once.
 */
@Slf4j
public class LiveTranscriptionSession {

	private static final String AUDIO_MIME_TYPE = "audio/pcm;rate=16000";

	/**
	 * Absolute safety ceiling for a single answer capture. Normal completion happens well
	 * before this - either when Gemini finalizes the turn with a transcript, or via the
	 * silence watchdog once the user stops talking. This only bounds pathological cases (the
	 * user talking nonstop, or completion signals never arriving at all) so a capture can
	 * never hang forever.
	 */
	private static final long CAPTURE_TIMEOUT_SECONDS = 45;

	/**
	 * How long to wait, after arming a capture, for the user to start saying anything
	 * transcribable before giving up and prompting them to answer again. This is what makes
	 * the assistant actually wait for the user instead of retrying the instant the question
	 * finishes: only a genuine stretch of nobody-speaking triggers a retry.
	 */
	private static final long NO_SPEECH_TIMEOUT_MS = 12_000;

	/**
	 * Safety ceiling for a single spoken line (question/retry/closing message): if the model
	 * never signals it has finished generating audio, don't hang the conversation forever.
	 */
	private static final long SPEAK_TIMEOUT_SECONDS = 30;

	/**
	 * Server messages arrive in tight sub-millisecond bursts while Gemini is actively
	 * transcribing or generating speech. Observed in production for the transcription side:
	 * sometimes a burst ends with a full transcript already in the buffer but neither
	 * turnComplete nor generationComplete ever arrives afterward - the session just goes
	 * quiet. Waiting for those flags in that case means riding out the full hard timeout and
	 * throwing away a transcript (or a finished spoken line) we already have. If no further
	 * server message shows up for this long after we've captured some text or audio, treat the
	 * turn as finished with whatever was accumulated.
	 */
	private static final long SILENCE_TIMEOUT_MS = 1500;

	private static final long SILENCE_CHECK_INTERVAL_MS = 250;

	private final AsyncSession session;

	private final Consumer<byte[]> outputAudioListener;

	private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "capture-silence-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	private final AtomicReference<StringBuilder> captureBuffer = new AtomicReference<>();

	private final AtomicReference<CompletableFuture<String>> pendingCapture = new AtomicReference<>();

	private final AtomicReference<CompletableFuture<Void>> pendingSpeak = new AtomicReference<>();

	private final AtomicBoolean speakAudioReceived = new AtomicBoolean();

	private final AtomicBoolean speechDetected = new AtomicBoolean();

	private final AtomicReference<ScheduledFuture<?>> silenceCheck = new AtomicReference<>();

	private final AtomicInteger chunksSent = new AtomicInteger();

	private final AtomicLong bytesSent = new AtomicLong();

	private volatile long captureStartedAtMs;

	private volatile long lastActivityAtMs;

	public LiveTranscriptionSession(AsyncSession session, Consumer<byte[]> outputAudioListener) {
		this.session = session;
		this.outputAudioListener = outputAudioListener;
		session.receive(this::onMessage);
		log.info("Live transcription session opened");
	}

	/**
	 * Sends {@code text} to the model as a complete turn and waits for it to finish speaking it
	 * aloud. Every chunk of output audio is streamed to the {@code outputAudioListener} passed
	 * to the constructor as it arrives, so the caller does not need to buffer anything itself.
	 */
	public CompletableFuture<Void> speak(String text) {
		speakAudioReceived.set(false);
		lastActivityAtMs = System.currentTimeMillis();
		CompletableFuture<Void> future = new CompletableFuture<>();
		pendingSpeak.set(future);
		ScheduledFuture<?> task = armWatchdog(() -> checkSpeakSilence(future));
		future.orTimeout(SPEAK_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, ex) -> {
			pendingSpeak.compareAndSet(future, null);
			disarmWatchdog(task);
			if (ex != null) {
				log.warn("speak(\"{}\") failed or timed out (audioReceived={})", text, speakAudioReceived.get(), ex);
			}
		});
		session
			.sendClientContent(LiveSendClientContentParameters.builder()
				.turns(Content.fromParts(Part.fromText(scriptToRecite(text))))
				.turnComplete(true)
				.build())
			.exceptionally(ex -> {
				failSpeak(ex);
				return null;
			});
		return future;
	}

	/**
	 * Wraps a line of text before sending it to the model. Without this, this preview model
	 * (a conversational dialogue model, not a text-to-speech engine) treats a bare turn like
	 * "Where are you from?" as a question addressed to itself and answers it conversationally
	 * instead of reciting it - observed in practice as the model rambling indefinitely instead
	 * of reading the line and stopping. Framing every turn explicitly as a script to recite
	 * verbatim keeps it from ever responding to the content instead of just voicing it.
	 */
	private static String scriptToRecite(String text) {
		return "Recite the following line aloud, word-for-word, then stop. It is not a question "
				+ "or message for you to respond to - it is a script for the human user to hear:\n\n" + text;
	}

	/**
	 * Arms transcript capture for the next spoken answer and starts a timeout. Call this
	 * once, then stream audio chunks via {@link #sendAudioChunk} as they arrive; the
	 * returned future completes with the transcript once Gemini's own end-of-speech
	 * detection finalizes the turn, or fails if nothing is detected within the timeout.
	 */
	public CompletableFuture<String> captureNextAnswer() {
		captureBuffer.set(new StringBuilder());
		speechDetected.set(false);
		chunksSent.set(0);
		bytesSent.set(0);
		captureStartedAtMs = System.currentTimeMillis();
		lastActivityAtMs = captureStartedAtMs;
		CompletableFuture<String> future = new CompletableFuture<>();
		pendingCapture.set(future);
		log.info("Capture armed (timeout {}s)", CAPTURE_TIMEOUT_SECONDS);
		ScheduledFuture<?> task = armWatchdog(() -> checkCaptureSilence(future));
		future.orTimeout(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, ex) -> {
			captureBuffer.set(null);
			pendingCapture.compareAndSet(future, null);
			disarmWatchdog(task);
			logOutcome(result, ex);
		});
		return future;
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
	 * The capture watchdog, covering the two cases the completion signals don't:
	 * <ul>
	 * <li>The user answered and stopped, but Gemini went quiet without ever sending
	 * turnComplete/generationComplete - finalize with whatever transcript we have once it's
	 * been idle for {@link #SILENCE_TIMEOUT_MS}.</li>
	 * <li>Nobody has said anything transcribable at all within {@link #NO_SPEECH_TIMEOUT_MS}
	 * of arming - give up and let the caller prompt the user to answer again. This is what
	 * makes the assistant wait for the user rather than retrying the instant the question
	 * finishes.</li>
	 * </ul>
	 */
	private void checkCaptureSilence(CompletableFuture<String> future) {
		if (pendingCapture.get() != future) {
			return;
		}
		StringBuilder buffer = captureBuffer.get();
		if (buffer == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!buffer.isEmpty()) {
			if (now - lastActivityAtMs >= SILENCE_TIMEOUT_MS) {
				log.info("Capture finalized after {}ms of silence with no turnComplete/generationComplete from Gemini",
						now - lastActivityAtMs);
				completeCapture(buffer.toString());
			}
			return;
		}
		if (!speechDetected.get() && now - captureStartedAtMs >= NO_SPEECH_TIMEOUT_MS) {
			log.info("No speech detected within {}ms of arming the capture - prompting the user to answer again",
					now - captureStartedAtMs);
			failCapture(new TimeoutException("no speech detected"));
		}
	}

	/**
	 * Mirrors {@link #checkCaptureSilence} for the speaking side: if we've already received at
	 * least one chunk of output audio but Gemini has gone quiet without ever flagging the turn
	 * complete, treat the line as fully spoken rather than waiting out the full timeout.
	 */
	private void checkSpeakSilence(CompletableFuture<Void> future) {
		if (pendingSpeak.get() != future || !speakAudioReceived.get()) {
			return;
		}
		long idleMs = System.currentTimeMillis() - lastActivityAtMs;
		if (idleMs >= SILENCE_TIMEOUT_MS) {
			log.info("Speak finalized after {}ms of silence with no turnComplete/generationComplete from Gemini",
					idleMs);
			completeSpeak();
		}
	}

	public void sendAudioChunk(byte[] pcm16Mono16k) {
		chunksSent.incrementAndGet();
		bytesSent.addAndGet(pcm16Mono16k.length);
		Blob audio = Blob.builder().data(pcm16Mono16k).mimeType(AUDIO_MIME_TYPE).build();
		session.sendRealtimeInput(LiveSendRealtimeInputParameters.builder().audio(audio).build())
			.exceptionally(ex -> {
				log.warn("Failed to send audio chunk to Gemini Live", ex);
				return failCapture(ex);
			});
	}

	public void close() {
		session.close();
		watchdog.shutdownNow();
	}

	private void onMessage(LiveServerMessage message) {
		if (message.setupComplete().isPresent()) {
			log.info("Live session setup complete");
		}
		message.goAway().ifPresent(goAway -> log.warn("Live session received goAway: {}", goAway));

		message.serverContent().ifPresent(content -> {
			if (pendingSpeak.get() != null) {
				handleSpeakContent(content);
			}
			else {
				handleCaptureContent(content);
			}
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
		// Only treat a completion signal as "finished speaking this line" once we have actually
		// received audio for it. An empty completion here is NOT the end of this line: it is the
		// trailing turnComplete from the previous answer-capture turn landing in the freshly-armed
		// speak (same bidirectional session). Completing on it would resolve speak() before a
		// single audio chunk is generated, so the handler would skip straight to listening and the
		// question would never be voiced. Ignore it and wait for the real spoken audio; the speak
		// timeout is the safety net if audio genuinely never comes.
		if ((turnComplete || generationComplete) && speakAudioReceived.get()) {
			completeSpeak();
		}
	}

	private void handleCaptureContent(LiveServerContent content) {
		StringBuilder buffer = captureBuffer.get();
		if (buffer == null) {
			log.debug("serverContent received with no active capture (ignored): turnComplete={}",
					content.turnComplete().orElse(false));
			return;
		}
		lastActivityAtMs = System.currentTimeMillis();
		String delta = content.inputTranscription().flatMap(Transcription::text).orElse(null);
		String interimDelta = content.interimInputTranscription().flatMap(Transcription::text).orElse(null);
		boolean turnComplete = content.turnComplete().orElse(false);
		boolean generationComplete = content.generationComplete().orElse(false);
		log.info(
				"serverContent during capture: inputTranscription={}, interimInputTranscription={}, "
						+ "turnComplete={}, turnCompleteReason={}, interrupted={}, generationComplete={}",
				delta, interimDelta, turnComplete, content.turnCompleteReason().orElse(null),
				content.interrupted().orElse(false), generationComplete);
		if (delta != null) {
			buffer.append(delta);
		}
		if (delta != null || interimDelta != null) {
			speechDetected.set(true);
		}
		// turnComplete is the documented "this answer is finished" signal, but this
		// preview model sometimes never sends it even though it has clearly finished
		// transcribing (confirmed via logging: generationComplete=true reliably arrives
		// with the full transcript already in hand, and turnComplete would then either
		// follow immediately or never come at all, hanging the capture until timeout and
		// leaving the turn open on Gemini's side, which corrupted the *next* answer's
		// transcript). Treating generationComplete as an equally valid completion signal
		// fixes both: it never fires before the transcript is fully accumulated, so
		// nothing gets cut off early.
		//
		// Crucially, only complete the capture if we actually have transcript text. An empty
		// buffer at this point means the completion signal is NOT the user finishing their
		// answer: it is the trailing turnComplete from the question we just finished speaking
		// (same bidirectional session) landing in the freshly-armed capture. Completing on it
		// would resolve with transcript="" and fire an instant, bogus "didn't hear anything"
		// retry before the user ever gets to speak. Ignore it and keep listening; a genuine
		// no-answer is handled by the no-speech watchdog instead.
		if ((turnComplete || generationComplete) && !buffer.isEmpty()) {
			completeCapture(buffer.toString());
		}
	}

	private void completeCapture(String transcript) {
		captureBuffer.set(null);
		CompletableFuture<String> future = pendingCapture.getAndSet(null);
		if (future != null) {
			future.complete(transcript.strip());
		}
	}

	private Void failCapture(Throwable ex) {
		captureBuffer.set(null);
		CompletableFuture<String> future = pendingCapture.getAndSet(null);
		if (future != null) {
			future.completeExceptionally(ex);
		}
		return null;
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

	private void logOutcome(String transcript, Throwable ex) {
		long elapsedMs = System.currentTimeMillis() - captureStartedAtMs;
		int chunks = chunksSent.get();
		long bytes = bytesSent.get();
		if (ex == null) {
			log.info("Capture completed in {}ms: transcript=\"{}\", audioChunksSent={}, audioBytesSent={}", elapsedMs,
					transcript, chunks, bytes);
			return;
		}
		Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
		if (cause instanceof TimeoutException) {
			log.warn(
					"Capture TIMED OUT after {}ms with no turnComplete from Gemini. audioChunksSent={}, "
							+ "audioBytesSent={}. {}",
					elapsedMs, chunks, bytes,
					chunks == 0 ? "No audio was ever sent - check the browser is actually streaming."
							: "Audio was sent but Gemini never finalized the turn in time.");
		}
		else {
			log.warn("Capture FAILED after {}ms: audioChunksSent={}, audioBytesSent={}", elapsedMs, chunks, bytes,
					cause);
		}
	}

}
