package dev.mayank.convoinsights.extract;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedExtractorTest {

    private final RuleBasedExtractor rules = new RuleBasedExtractor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "I want a refund for the broken headphones|REFUND",
            "what is the early termination fee if I leave|CANCEL",
            "my package still hasn't been delivered|DELIVERY",
            "I can't log in, password reset never arrives|ACCOUNT",
            "the app keeps crashing after the update|TECHNICAL_SUPPORT",
            "can I change my shipping address please|SHIPPING",
            "lovely weather today|OTHER"
    })
    void classifiesByKeywords(String text, Intent expected) {
        assertThat(rules.extract(TestEvents.customer("e1", text)).intent()).isEqualTo(expected);
    }

    @Test
    void wordBoundariesAvoidFalseMatches() {
        // "apply" contains "app" but is not about an app
        assertThat(RuleBasedExtractor.classify("i want to apply for a job")).isEqualTo(Intent.OTHER);
    }

    @Test
    void sentimentFollowsLexicon() {
        assertThat(RuleBasedExtractor.sentiment("this is the worst service, still waiting")).isEqualTo(Sentiment.NEGATIVE);
        assertThat(RuleBasedExtractor.sentiment("thanks so much, that fixed it")).isEqualTo(Sentiment.POSITIVE);
        assertThat(RuleBasedExtractor.sentiment("what time do you open")).isEqualTo(Sentiment.NEUTRAL);
    }

    @Test
    void summaryIsFirstSentenceWithoutMentionsOrUrls() {
        var result = rules.extract(TestEvents.customer("e1",
                "@AcmeSupport my order arrived damaged yesterday. Can you help? https://t.co/abc"));
        assertThat(result.summary()).isEqualTo("my order arrived damaged yesterday.");
    }

    @Test
    void summaryIsCappedAndNeverEmpty() {
        String longText = "word ".repeat(200);
        assertThat(rules.extract(TestEvents.customer("e1", longText)).summary())
                .hasSizeLessThanOrEqualTo(RuleBasedExtractor.MAX_SUMMARY);
        assertThat(rules.extract(TestEvents.customer("e2", "@AcmeSupport")).summary()).isEqualTo("(no customer text)");
    }

    @Test
    void onlyCustomerTurnsAreClassified() {
        var result = rules.extract(TestEvents.thread("e1", "hello there", "we can process a refund for you"));
        assertThat(result.intent()).isEqualTo(Intent.OTHER);
    }

    @Test
    void isDeterministicAndTaggedAsRules() {
        var event = TestEvents.customer("e1", "refund please, the item is broken");
        var a = rules.extract(event);
        var b = rules.extract(event);
        assertThat(a).isEqualTo(b);
        assertThat(a.extractor()).isEqualTo(Extractor.RULES);
        assertThat(a.fallbackReason()).isNull();
    }
}
