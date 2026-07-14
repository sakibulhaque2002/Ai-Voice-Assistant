# Voice Assistant

A hands-free, real-time spoken interface over a JSON-defined branching questionnaire. The user
clicks **Start** once; from then on a single Gemini Live session speaks each question aloud,
listens to the spoken reply (English or Bengali), and hands the matched option straight back —
looping until every field is collected. The answers fill a JSON object like
`{"country": "Bangladesh", "ageGroup": "31-40", ...}`.

## How it works

There is **no second LLM**. One bidirectional [Gemini Live](https://ai.google.dev/gemini-api/docs/live)
session does everything through **function calling**:

1. The backend, driving the questionnaire's branching, sends the model a script for the current
   question: *recite this line aloud, then listen, then call `record_answer` with the matching
   option label.*
2. The model speaks the question (streamed to the browser as audio), hears the user's answer, and
   calls the `record_answer` function with the chosen option — already classified.
3. The backend validates that label against the current question's options, stores the option's
   value under the question's field, and advances via `nextQuestionId` (or finishes).

The browser only streams mic audio and plays back the spoken audio; all flow control is
server-driven over a single `/voice` WebSocket.

```
Browser (Start button, mic + speaker)
      ⇅  WebSocket /voice  (JSON control frames + binary PCM audio both ways)
Spring Boot  — walks questionnaire.json, stores answers, validates record_answer labels
      ⇅  Gemini Live session  (speaks · listens · record_answer function call)
Gemini
```

## Key files

| Area | Files |
|---|---|
| Questionnaire data (question graph, branching) | `src/main/resources/questionnaire.json`, [`model/`](src/main/java/com/example/voice_assistant/model/) |
| Flow engine (session state, branching, label→option) | [`SessionService.java`](src/main/java/com/example/voice_assistant/service/SessionService.java), [`QuestionService.java`](src/main/java/com/example/voice_assistant/service/QuestionService.java) |
| Gemini Live (speak + listen + `record_answer`) | [`GeminiSpeechService.java`](src/main/java/com/example/voice_assistant/speech/GeminiSpeechService.java), [`LiveVoiceSession.java`](src/main/java/com/example/voice_assistant/speech/LiveVoiceSession.java) |
| WebSocket orchestration | [`VoiceSessionHandler.java`](src/main/java/com/example/voice_assistant/voice/VoiceSessionHandler.java), [`VoiceMessages.java`](src/main/java/com/example/voice_assistant/voice/VoiceMessages.java) |
| Frontend (single Start button) | [`static/index.html`](src/main/resources/static/index.html), [`static/mic-processor.js`](src/main/resources/static/mic-processor.js) |

## Running

Set your Gemini API key (create one at https://aistudio.google.com/apikey):

```bash
export GEMINI_API_KEY=your-key-here
./gradlew bootRun
```

Then open http://localhost:8080, click **Start**, allow microphone access, and answer aloud.

### Configuration (`src/main/resources/application.properties`)

| Property | Meaning |
|---|---|
| `app.questionnaire.resource` | Which questionnaire JSON to load (e.g. `classpath:questionnaire.json`) |
| `app.voice.live-model` | Gemini Live model id |
| `app.voice.voice-name` | Prebuilt Live voice used to speak questions (e.g. `Zephyr`) |

## Questionnaire format

Fully data-driven — the flow lives in the JSON, not in Java:

```json
{
  "startQuestionId": 1,
  "questions": [
    {
      "id": 1, "question": "Where are you from?", "field": "country", "type": "single_select",
      "options": [
        { "label": "Bangladesh", "value": "Bangladesh", "nextQuestionId": 2 },
        { "label": "India", "value": "India", "nextQuestionId": 2 }
      ]
    }
  ]
}
```

`nextQuestionId: null` ends the questionnaire. `value` may be a string, boolean, or number.
