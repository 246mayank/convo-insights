package dev.mayank.convoinsights.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One conversation as it arrives on {@code conversations.raw}. {@code eventId} is a content hash
 * computed by the producer and is the dedupe key for the whole pipeline.
 */
public record ConversationEvent(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String conversationId,
        @Size(max = 64) String brand,
        @NotBlank @Size(max = 32) String source,
        @NotEmpty @Valid List<Turn> turns) {

    public String customerText() {
        return turns.stream()
                .filter(t -> t.speaker() == Speaker.CUSTOMER)
                .map(Turn::text)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    public String transcript() {
        StringBuilder sb = new StringBuilder();
        for (Turn t : turns) {
            sb.append(t.speaker()).append(": ").append(t.text()).append('\n');
        }
        return sb.toString();
    }
}
