package dev.mayank.convoinsights.ingest;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventParserTest {

    private final EventParser parser = new EventParser(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void parsesAValidEventAndIgnoresUnknownFields() {
        var e = parser.parse("""
                {"eventId":"abc","conversationId":"twcs-1","brand":"AcmeSupport","source":"twcs","channel":"sms",
                 "turns":[{"speaker":"CUSTOMER","text":"hi","ts":"2017-10-31T22:10:47Z"}]}
                """);
        assertThat(e.eventId()).isEqualTo("abc");
        assertThat(e.turns()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{not json", "[]", "\"a string\""})
    void rejectsUnparseable(String json) {
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsMissingRequiredFieldsWithAReadableMessage() {
        assertThatThrownBy(() -> parser.parse("""
                {"eventId":"","conversationId":"c","source":"twcs","turns":[]}
                """))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("eventId")
                .hasMessageContaining("turns");
    }

    @Test
    void rejectsBlankTurnTextAndUnknownSpeaker() {
        assertThatThrownBy(() -> parser.parse("""
                {"eventId":"a","conversationId":"c","source":"twcs","turns":[{"speaker":"CUSTOMER","text":" "}]}
                """)).isInstanceOf(InvalidEventException.class).hasMessageContaining("text");
        assertThatThrownBy(() -> parser.parse("""
                {"eventId":"a","conversationId":"c","source":"twcs","turns":[{"speaker":"BOT","text":"x"}]}
                """)).isInstanceOf(InvalidEventException.class);
    }
}
