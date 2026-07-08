package com.example.voice_assistant.config;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.example.voice_assistant.model.Questionnaire;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the questionnaire definition from JSON on startup. The questionnaire (including
 * branching between questions) is entirely data-driven - no question or flow logic is
 * hardcoded in Java.
 */
@Configuration
@RequiredArgsConstructor
public class QuestionnaireConfig {

	private final ResourceLoader resourceLoader;

	private final AppProperties appProperties;

	@Bean
	public Questionnaire questionnaire(ObjectMapper objectMapper) throws IOException {
		Resource resource = resourceLoader.getResource(appProperties.questionnaire().resource());
		try (InputStream inputStream = resource.getInputStream()) {
			return objectMapper.readValue(inputStream, Questionnaire.class);
		}
	}

}
