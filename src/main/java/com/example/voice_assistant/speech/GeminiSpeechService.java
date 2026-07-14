package com.example.voice_assistant.speech;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.example.voice_assistant.config.AppProperties;
import com.google.genai.Client;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.LanguageHints;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import com.google.genai.types.PrebuiltVoiceConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.VoiceConfig;

import lombok.RequiredArgsConstructor;

/**
 * Opens one bidirectional Gemini Live session per browser connection. That single session drives
 * the entire questionnaire by voice: it speaks each question aloud (in the configured prebuilt
 * voice), listens to the spoken answer in English or Bengali, and hands the answer back already
 * classified by calling the {@code record_answer} function - so no second model is ever needed to
 * interpret what the user said. Input transcription is restricted to English/Bengali via language
 * hints purely to feed the on-screen transcript display.
 */
@Service
@RequiredArgsConstructor
public class GeminiSpeechService {

	private static final String SYSTEM_INSTRUCTION = """
			You are the voice of an automated questionnaire, not a conversational assistant. You \
			never have a conversation of your own and never answer the questions yourself.

			Each turn the client gives you a script describing one question. Do exactly what the \
			script says: recite the question aloud in English, word for word, then stop and wait \
			for the human user to answer. The user may answer in English or Bengali. Once you have \
			heard their answer, call the record_answer function with the option label that best \
			matches it (or "unclear" if none does). Never translate, rephrase, or answer a \
			question, and never speak anything other than the exact line you were asked to recite.
			""";

	/**
	 * The one function the model uses to hand back each answer already mapped to an option. It is
	 * declared with a free-form string label rather than a fixed enum because the valid options
	 * change from question to question; the script for each question tells the model which labels
	 * are valid, and the server validates the returned label against the current question.
	 */
	private static final Tool RECORD_ANSWER_TOOL = Tool.builder()
		.functionDeclarations(FunctionDeclaration.builder()
			.name(LiveVoiceSession.RECORD_ANSWER)
			.description("Record the user's answer to the current questionnaire question. Call this exactly once, "
					+ "and only after you have actually heard the user's spoken reply.")
			.parameters(Schema.builder()
				.type("OBJECT")
				.properties(Map.of(LiveVoiceSession.LABEL_ARG,
						Schema.builder()
							.type("STRING")
							.description("The exact option label that matches the user's reply, or \""
									+ LiveVoiceSession.UNCLEAR + "\" if none matches.")
							.build()))
				.required(LiveVoiceSession.LABEL_ARG)
				.build())
			.build())
		.build();

	private final Client client;

	private final AppProperties appProperties;

	/**
	 * Opens the Live session. {@code onOutputAudio} receives each chunk of the model's spoken
	 * output (24kHz mono PCM) as it arrives; {@code onInterimTranscript} receives the running
	 * transcript of the user's current answer for on-screen display.
	 */
	public CompletableFuture<LiveVoiceSession> openSession(Consumer<byte[]> onOutputAudio,
			Consumer<String> onInterimTranscript) {
		LiveConnectConfig config = LiveConnectConfig.builder()
			.responseModalities("AUDIO")
			.tools(RECORD_ANSWER_TOOL)
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
			.thenApply(session -> new LiveVoiceSession(session, onOutputAudio, onInterimTranscript));
	}

}
