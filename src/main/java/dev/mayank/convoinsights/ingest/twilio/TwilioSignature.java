package dev.mayank.convoinsights.ingest.twilio;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Twilio's webhook signature (the {@code X-Twilio-Signature} header): take the full URL Twilio
 * called, append every POST parameter as name + value in name order, HMAC-SHA1 it with the
 * account's auth token, Base64 the result. A request whose header doesn't match was not sent by
 * Twilio (or was altered) and is rejected.
 */
public final class TwilioSignature {

    private TwilioSignature() {
    }

    public static String compute(String authToken, String url, Map<String, String> params) {
        StringBuilder data = new StringBuilder(url);
        new TreeMap<>(params).forEach((k, v) -> data.append(k).append(v));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /** Constant-time comparison, so response timing doesn't reveal how much of a forged signature was right. */
    public static boolean isValid(String authToken, String url, Map<String, String> params, String header) {
        if (header == null || authToken == null || authToken.isBlank()) return false;
        byte[] expected = compute(authToken, url, params).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
    }
}
