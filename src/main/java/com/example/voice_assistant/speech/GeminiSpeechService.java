package com.example.voice_assistant.speech;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.example.voice_assistant.config.AppProperties;
import com.google.genai.Client;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Content;
import com.google.genai.types.LanguageHints;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import com.google.genai.types.PrebuiltVoiceConfig;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.VoiceConfig;

import lombok.RequiredArgsConstructor;

/**
 * Opens a single bidirectional Gemini Live API session per browser connection: the same session
 * speaks every question, retry prompt and closing line aloud in English exactly as given (no
 * translation), and transcribes the user's spoken answers. Transcription is restricted to
 * English/Bengali via language hints so the questionnaire engine never has to deal with a third
 * language, even though the assistant's own speech stays English-only.
 */
@Service
@RequiredArgsConstructor
public class GeminiSpeechService {

	private static final String SYSTEM_INSTRUCTION = """
			You are a text-to-speech relay for an automated questionnaire, not a conversational \
			assistant. You never have a conversation of your own and never answer questions - you \
			only relay lines to a human user or transcribe what they say back.

			SPEAKING: every client message you receive is a script to recite verbatim, never a \
			question or statement directed at you, even if it is phrased as a question (e.g. \
			"Where are you from?" means "say the words Where are you from? out loud", not "answer \
			this question"). Speak it aloud in English exactly as given, then stop - do not \
			translate it, rephrase it, answer it, or add anything to it. No greetings, no extra \
			commentary.

			LISTENING: while the user's microphone audio is being streamed to you, your only job \
			is to transcribe it. The user may answer in English or Bengali. Never reply to what \
			you hear, never hold a conversation, never narrate or acknowledge it with words. If \
			you must produce any audio while listening, make it a single silent or near-silent \
			breath and nothing else.
			""";

	private final Client client;

	private final AppProperties appProperties;

	public CompletableFuture<LiveTranscriptionSession> openLiveTranscriptionSession(Consumer<byte[]> onOutputAudio) {
		LiveConnectConfig config = LiveConnectConfig.builder()
			.responseModalities("AUDIO")
			.inputAudioTranscription(AudioTranscriptionConfig.builder()
				.languageHints(LanguageHints.builder().languageCodes("bn-BD", "en-US").build())
				.build())
			.speechConfig(SpeechConfig.builder()
				.voiceConfig(VoiceConfig.builder()
					.prebuiltVoiceConfig(
							PrebuiltVoiceConfig.builder().voiceName(appProperties.voice().voiceName()).build())
					.build())
				.build())
			.systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
			.build();
		return client.async.live.connect(appProperties.voice().liveModel(), config)
			.thenApply(session -> new LiveTranscriptionSession(session, onOutputAudio));
	}

}
