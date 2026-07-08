package com.example.voice_assistant.speech;

import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.example.voice_assistant.config.AppProperties;
import com.google.genai.Client;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Content;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;

import lombok.RequiredArgsConstructor;

/**
 * Opens a Gemini Live API session used purely as a continuous speech-to-text tap: the
 * model's own spoken replies are discarded, only its transcription of the user's mic audio
 * is surfaced. See {@link LiveTranscriptionSession} for how the transcript is captured.
 */
@Service
@RequiredArgsConstructor
public class GeminiSpeechService {

	private static final String LISTENER_SYSTEM_INSTRUCTION = """
			You are the ears of an automated voice questionnaire backend. Your only job is to \
			listen to short spoken answers. Never hold a conversation, never ask questions of \
			your own, never narrate or explain anything, never acknowledge what you heard with \
			words. If you must produce any audio, make it a single silent or near-silent breath \
			and nothing else.
			""";

	private final Client client;

	private final AppProperties appProperties;

	public CompletableFuture<LiveTranscriptionSession> openLiveTranscriptionSession() {
		LiveConnectConfig config = LiveConnectConfig.builder()
			.responseModalities("AUDIO")
			.inputAudioTranscription(AudioTranscriptionConfig.builder().build())
			.systemInstruction(Content.fromParts(Part.fromText(LISTENER_SYSTEM_INSTRUCTION)))
			.build();
		return client.async.live.connect(appProperties.voice().liveModel(), config)
			.thenApply(LiveTranscriptionSession::new);
	}

}
