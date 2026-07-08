package com.example.voice_assistant.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single questionnaire question, as defined in questionnaire.json.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Question {

	private Integer id;

	private String question;

	private String field;

	private String type;

	private List<Option> options;

}
