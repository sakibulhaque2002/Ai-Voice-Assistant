package com.example.voice_assistant.service;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.voice_assistant.dto.ClassificationRequest;
import com.example.voice_assistant.dto.ClassificationResult;
import com.example.voice_assistant.factory.AIProviderFactory;
import com.example.voice_assistant.model.Option;
import com.example.voice_assistant.model.Question;

import lombok.RequiredArgsConstructor;

/**
 * Resolves a user's raw natural language answer to one of the current question's
 * options: an exact match short-circuits and skips the AI call entirely; otherwise the
 * configured AIProvider classifies it and the result is mapped back to an Option.
 */
@Service
@RequiredArgsConstructor
public class AIService {

	/**
	 * Below this, even a "valid" classification is treated as not confident enough.
	 */
	private static final double MIN_CONFIDENCE = 0.5;

	private final ValidationService validationService;

	private final QuestionService questionService;

	private final AIProviderFactory providerFactory;

	public Option resolveAnswer(Question question, Map<String, Object> currentAnswers, String rawAnswer) {
		Optional<Option> exactMatch = validationService.findExactMatch(question, rawAnswer);
		if (exactMatch.isPresent()) {
			return exactMatch.get();
		}

		ClassificationRequest request = new ClassificationRequest(currentAnswers, question.getQuestion(),
				questionService.getAllowedOptionLabels(question), rawAnswer);

		ClassificationResult result = providerFactory.getProvider().classify(request);

		if (!result.valid() || result.confidence() < MIN_CONFIDENCE || result.selectedOption() == null) {
			throw new AnswerNotUnderstoodException(question.getQuestion());
		}

		// Defensive: never trust an option label the AI returns that isn't actually allowed.
		return questionService.findOptionByLabel(question, result.selectedOption())
			.orElseThrow(() -> new AnswerNotUnderstoodException(question.getQuestion()));
	}

}
