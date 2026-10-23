package dev.mayank.convoinsights.ingest.twilio;

import com.twilio.security.RequestValidator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Our hand-written validator must agree with Twilio's official one, in both directions. */
class TwilioSignatureTest {

    private static final String TOKEN = "12345";
    private static final String URL = "https://insights.example.com/webhooks/twilio/conversations";

    private static Map<String, String> params() {
        Map<String, String> p = new HashMap<>();
        p.put("EventType", "onMessageAdded");
        p.put("ConversationSid", "CH00000000000000000000000000000001");
        p.put("MessageSid", "IM00000000000000000000000000000001");
        p.put("Author", "+15550001111");
        p.put("Body", "My card was charged twice & the refund hasn't arrived.");
        p.put("Source", "SMS");
        return p;
    }

    @Test
    void twilioAcceptsOurSignature() {
        String ours = TwilioSignature.compute(TOKEN, URL, params());
        assertThat(new RequestValidator(TOKEN).validate(URL, params(), ours)).isTrue();
    }

    @Test
    void weAcceptTwilioSignatureAndRejectTampering() {
        String ours = TwilioSignature.compute(TOKEN, URL, params());
        assertThat(TwilioSignature.isValid(TOKEN, URL, params(), ours)).isTrue();

        Map<String, String> tampered = params();
        tampered.put("Body", "My card was charged twice & the refund hasn't arrived!");
        assertThat(TwilioSignature.isValid(TOKEN, URL, tampered, ours)).as("changed body").isFalse();
        assertThat(new RequestValidator(TOKEN).validate(URL, tampered, ours)).as("Twilio agrees").isFalse();

        assertThat(TwilioSignature.isValid("wrong-token", URL, params(), ours)).as("wrong token").isFalse();
        assertThat(TwilioSignature.isValid(TOKEN, URL + "?x=1", params(), ours)).as("different URL").isFalse();
        assertThat(TwilioSignature.isValid(TOKEN, URL, params(), null)).as("no header").isFalse();
        assertThat(TwilioSignature.isValid("", URL, params(), ours)).as("no token configured").isFalse();
    }

    @Test
    void parameterOrderDoesNotMatter() {
        Map<String, String> a = new java.util.LinkedHashMap<>();
        a.put("b", "2");
        a.put("a", "1");
        Map<String, String> b = new java.util.LinkedHashMap<>();
        b.put("a", "1");
        b.put("b", "2");
        assertThat(TwilioSignature.compute(TOKEN, URL, a)).isEqualTo(TwilioSignature.compute(TOKEN, URL, b));
    }

    @Test
    void channelMapping() {
        assertThat(TwilioConversationsWebhook.channelOf("SMS")).isEqualTo("sms");
        assertThat(TwilioConversationsWebhook.channelOf("whatsapp")).isEqualTo("whatsapp");
        assertThat(TwilioConversationsWebhook.channelOf("SDK")).isEqualTo("chat");
        assertThat(TwilioConversationsWebhook.channelOf(null)).isEqualTo("chat");
    }
}
