package dev.mayank.convoinsights;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Speaker;
import dev.mayank.convoinsights.domain.Turn;

import java.util.ArrayList;
import java.util.List;

/** Small builders for test events. */
public final class TestEvents {

    private TestEvents() {
    }

    public static ConversationEvent customer(String eventId, String... customerTexts) {
        List<Turn> turns = new ArrayList<>();
        for (String t : customerTexts) {
            turns.add(new Turn(Speaker.CUSTOMER, t, "2017-10-31T22:10:47Z"));
        }
        return new ConversationEvent(eventId, "conv-" + eventId, "AcmeSupport", "test", turns);
    }

    public static ConversationEvent thread(String eventId, String customer, String agent) {
        return new ConversationEvent(eventId, "conv-" + eventId, "AcmeSupport", "test", List.of(
                new Turn(Speaker.CUSTOMER, customer, "2017-10-31T22:10:47Z"),
                new Turn(Speaker.AGENT, agent, "2017-10-31T22:12:00Z")));
    }
}
