package com.example.voice_assistant.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single selectable option for a {@link Question}, as defined in questionnaire.json.
 * {@code value} is typed as Object because the JSON allows string, boolean or numeric
 * option values (e.g. "Bangladesh" vs true/false).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Option {

	private String label;

	private Object value;

	private Integer nextQuestionId;

}
