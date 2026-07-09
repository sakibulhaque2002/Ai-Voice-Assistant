package com.example.voice_assistant.voice;

import java.util.Map;

import com.example.voice_assistant.dto.QuestionDto;

/**
 * JSON control messages exchanged with the browser over the /voice WebSocket. Binary frames
 * always carry raw 16-bit PCM audio and travel in both directions: browser to server is the
 * user's mic input (16kHz mono), server to browser is Gemini's spoken output (24kHz mono).
 */
final class VoiceMessages {

	private VoiceMessages() {
	}

	/**
	 * Inbound control frame sent by the browser, e.g. {"type":"start"}. The rest of the
	 * conversation - speaking each question, listening for the answer, retrying on an
	 * unrecognized answer, ending once every field is collected - is entirely server-driven.
	 */
	record Inbound(String type) {
	}

	record Question(String type, QuestionDto question) {
		Question(QuestionDto question) {
			this("question", question);
		}
	}

	/**
	 * The server is about to speak (question, retry prompt or closing line): output audio
	 * binary frames follow. The browser should stop streaming mic audio until "listening"
	 * arrives.
	 */
	record Speaking(String type) {
		Speaking() {
			this("speaking");
		}
	}

	/**
	 * The server has finished speaking and is now waiting for the user's spoken answer: the
	 * browser should start streaming mic audio until the next "speaking" message arrives.
	 */
	record Listening(String type) {
		Listening() {
			this("listening");
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

	/**
	 * Informational only: the user's answer wasn't understood. The server always follows this
	 * with a spoken retry prompt (a "speaking" message) rather than expecting the browser to
	 * show a warning and wait for another click.
	 */
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
