package com.example.voice_assistant.dto;

import java.util.List;

import com.example.voice_assistant.model.Question;

/**
 * Client-facing view of a Question, built from the internal model so the API shape
 * stays decoupled from the questionnaire.json structure.
 */
public record QuestionDto(Integer id, String question, String field, String type, List<OptionDto> options) {

	public static QuestionDto from(Question question) {
		List<OptionDto> options = question.getOptions()
			.stream()
			.map(option -> new OptionDto(option.getLabel(), option.getValue()))
			.toList();
		return new QuestionDto(question.getId(), question.getQuestion(), question.getField(), question.getType(),
				options);
	}

}
