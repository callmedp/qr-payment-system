package com.qrpay.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code X-QrPay-Signature: t=<unix-seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}
 *
 * <p>Signing the timestamp together with the body lets receivers reject replays older than a tolerance
 * window, and lets them verify the payload really came from us.
 *
 * <p>The header carries <strong>one {@code v1} part per active secret</strong>. Normally that is one;
 * during a secret rotation's grace window it is two (new and old), so a receiver that has not yet
 * updated its configuration still verifies. A receiver accepts the request if any part matches.
 */
public final class WebhookSigner {

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestamp, String body) {
        return sign(List.of(secret), timestamp, body);
    }

    /**
     * Signs with every supplied secret, emitting one {@code v1} part each. During a secret rotation's
     * grace window this carries both the new and the old signature, so a receiver verifies successfully
     * whichever secret it currently holds.
     */
    public static String sign(List<String> secrets, long timestamp, String body) {
        StringBuilder header = new StringBuilder("t=").append(timestamp);
        for (String secret : secrets) {
            header.append(",v1=").append(hmacHex(secret, timestamp + "." + body));
        }
        return header.toString();
    }

    public static boolean verify(String secret, String header, String body, Duration tolerance, Instant now) {
        if (header == null || secret == null) {
            return false;
        }
        long timestamp = -1;
        List<String> provided = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                provided.add(kv[1]);
            }
        }
        if (timestamp < 0 || provided.isEmpty()) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            return false;
        }
        // A header may carry several v1 parts during a rotation; ours is valid if ANY of them matches.
        // Every candidate is compared (no short-circuit) so the work does not depend on which one hits.
        byte[] expected = hmacHex(secret, timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        boolean matched = false;
        for (String candidate : provided) {
            matched |= MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII));
        }
        return matched;
    }

    private static String hmacHex(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
