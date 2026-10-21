package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.domain.ConversationEvent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EventParser {

    private final ObjectMapper mapper;
    private final Validator validator;

    public EventParser(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public ConversationEvent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("empty record value");
        }
        ConversationEvent event;
        try {
            event = mapper.readValue(json, ConversationEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("unparseable event: " + e.getOriginalMessage(), e);
        }
        Set<ConstraintViolation<ConversationEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new InvalidEventException("invalid event: " + msg);
        }
        return event;
    }
}
