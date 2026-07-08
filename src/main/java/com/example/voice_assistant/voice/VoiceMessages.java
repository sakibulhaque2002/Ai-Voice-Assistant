package com.example.voice_assistant.voice;

import java.util.Map;

import com.example.voice_assistant.dto.QuestionDto;

/**
 * JSON control messages exchanged with the browser over the /voice WebSocket. Audio itself
 * always travels as separate binary frames (raw 16-bit PCM, 16kHz mono, browser to server
 * only); these records are only ever sent/received as text frames.
 */
final class VoiceMessages {

	private VoiceMessages() {
	}

	/**
	 * Inbound control frame sent by the browser, e.g. {"type":"start"} or
	 * {"type":"listen"}.
	 */
	record Inbound(String type) {
	}

	record Question(String type, QuestionDto question) {
		Question(QuestionDto question) {
			this("question", question);
		}
	}

	/**
	 * Tells the browser to stop streaming mic audio: Gemini has either finished detecting
	 * the end of the user's speech or the capture timed out. Always sent exactly once per
	 * "listen" click, before whatever outcome message (transcript/not_understood/error)
	 * follows.
	 */
	record ListeningStopped(String type) {
		ListeningStopped() {
			this("listening_stopped");
		}
	}

	record Transcript(String type, String text) {
		Transcript(String text) {
			this("transcript", text);
		}
	}

	record Answers(String type, boolean completed, Map<String, Object> answers) {
		Answers(boolean completed, Map<String, Object> answers) {
			this("answers", completed, answers);
		}
	}

	record NotUnderstood(String type, String message) {
		NotUnderstood(String message) {
			this("not_understood", message);
		}
	}

	record Completed(String type) {
		Completed() {
			this("completed");
		}
	}

	record Error(String type, String message) {
		Error(String message) {
			this("error", message);
		}
	}

}
