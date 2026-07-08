package com.example.voice_assistant.speech;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.genai.AsyncSession;
import com.google.genai.types.Blob;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.Transcription;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps a single Gemini Live AsyncSession used purely as a continuous speech-to-text tap:
 * the model's own spoken replies are discarded, only its transcription of the user's mic
 * audio is surfaced. One instance is held for the lifetime of a browser connection and
 * reused across every question in the questionnaire, one captured answer at a time.
 */
@Slf4j
public class LiveTranscriptionSession {

	private static final String AUDIO_MIME_TYPE = "audio/pcm;rate=16000";

	/**
	 * Gemini's own end-of-speech detection decides when an answer is finished, which is a
	 * heuristic rather than a guarantee - it can occasionally fail to fire for a given
	 * utterance. Without a hard ceiling a missed detection would leave the caller waiting
	 * forever with no feedback, so any capture that hasn't resolved within this long is
	 * treated as "didn't catch that" instead.
	 */
	private static final long CAPTURE_TIMEOUT_SECONDS = 10;

	private final AsyncSession session;

	private final AtomicReference<StringBuilder> captureBuffer = new AtomicReference<>();

	private final AtomicReference<CompletableFuture<String>> pendingCapture = new AtomicReference<>();

	private final AtomicInteger chunksSent = new AtomicInteger();

	private final AtomicLong bytesSent = new AtomicLong();

	private volatile long captureStartedAtMs;

	public LiveTranscriptionSession(AsyncSession session) {
		this.session = session;
		session.receive(this::onMessage);
		log.info("Live transcription session opened");
	}

	/**
	 * Arms transcript capture for the next spoken answer and starts a timeout. Call this
	 * once, then stream audio chunks via {@link #sendAudioChunk} as they arrive; the
	 * returned future completes with the transcript once Gemini's own end-of-speech
	 * detection finalizes the turn, or fails if nothing is detected within the timeout.
	 */
	public CompletableFuture<String> captureNextAnswer() {
		captureBuffer.set(new StringBuilder());
		chunksSent.set(0);
		bytesSent.set(0);
		captureStartedAtMs = System.currentTimeMillis();
		CompletableFuture<String> future = new CompletableFuture<>();
		pendingCapture.set(future);
		log.info("Capture armed (timeout {}s)", CAPTURE_TIMEOUT_SECONDS);
		future.orTimeout(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, ex) -> {
			captureBuffer.set(null);
			pendingCapture.compareAndSet(future, null);
			logOutcome(result, ex);
		});
		return future;
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
	}

	private void onMessage(LiveServerMessage message) {
		if (message.setupComplete().isPresent()) {
			log.info("Live session setup complete");
		}
		message.goAway().ifPresent(goAway -> log.warn("Live session received goAway: {}", goAway));

		StringBuilder buffer = captureBuffer.get();
		message.serverContent().ifPresent(content -> {
			if (buffer == null) {
				log.debug("serverContent received with no active capture (ignored): turnComplete={}",
						content.turnComplete().orElse(false));
				return;
			}
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
			// turnComplete is the documented "this answer is finished" signal, but this
			// preview model sometimes never sends it even though it has clearly finished
			// transcribing (confirmed via logging: generationComplete=true reliably arrives
			// with the full transcript already in hand, and turnComplete would then either
			// follow immediately or never come at all, hanging the capture until timeout and
			// leaving the turn open on Gemini's side, which corrupted the *next* answer's
			// transcript). Treating generationComplete as an equally valid completion signal
			// fixes both: it never fires before the transcript is fully accumulated, so
			// nothing gets cut off early.
			if (turnComplete || generationComplete) {
				completeCapture(buffer.toString());
			}
		});
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
