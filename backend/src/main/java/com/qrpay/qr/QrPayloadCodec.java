package com.qrpay.qr;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.config.QrPayProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encodes/decodes the string that is rendered into the QR image. Format:
 *
 * <pre>
 *   qrpay://pay?v=2&amp;qid={qrId}&amp;sig={hmac}
 * </pre>
 *
 * {@code sig} is a truncated (128-bit) HMAC-SHA256 over everything before {@code &sig=}, base64url.
 * The payload is deliberately nothing but an unguessable pointer plus an integrity tag: the server
 * treats its <b>own database</b> as the source of truth for payee, amount, expiry and status, so
 * carrying those in the code would add bytes (and disclose the merchant's VPA and order value to
 * anyone who photographs it) without being believed.
 *
 * <p>Version 1 codes carried {@code mid}, {@code pa}, {@code pn}, {@code am}, {@code exp} and
 * {@code cu} as well. They are still accepted on decode - a static QR may be printed and in the
 * field for years - but the extra params are ignored, exactly as they were before.
 */
@Component
public class QrPayloadCodec {

    private static final String VERSION = "2";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("1", "2");
    private static final int SIGNATURE_BYTES = 16;
    private static final int MAX_LENGTH = 1024;

    private final byte[] secret;
    private final String prefix;

    public QrPayloadCodec(QrPayProperties props) {
        this.secret = Base64.getDecoder().decode(props.qr().signingSecret());
        this.prefix = props.qr().scheme() + "://pay?";
    }

    public String encode(QrPayload p) {
        String signed = prefix + "v=" + VERSION + "&qid=" + p.qrId();
        return signed + "&sig=" + sign(signed);
    }

    /** @throws ApiException QR_INVALID if the payload is malformed or its signature does not verify */
    public QrPayload decodeAndVerify(String raw) {
        if (raw == null || !raw.startsWith(prefix) || raw.length() > MAX_LENGTH) {
            throw new ApiException(ErrorCode.QR_INVALID);
        }
        int sigAt = raw.lastIndexOf("&sig=");
        if (sigAt < 0) {
            throw new ApiException(ErrorCode.QR_INVALID);
        }
        String signedPart = raw.substring(0, sigAt);
        byte[] expected = sign(signedPart).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = raw.substring(sigAt + 5).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) { // constant-time comparison
            throw new ApiException(ErrorCode.QR_INVALID);
        }

        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : signedPart.substring(prefix.length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new ApiException(ErrorCode.QR_INVALID);
            }
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        try {
            if (!SUPPORTED_VERSIONS.contains(params.get("v"))) {
                throw new ApiException(ErrorCode.QR_INVALID, "Unsupported QR version");
            }
            // v1 codes also carry mid/pa/pn/am/exp/cu; they were never trusted, so they are ignored.
            return new QrPayload(UUID.fromString(params.get("qid")));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.QR_INVALID);
        }
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, SIGNATURE_BYTES));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public record QrPayload(UUID qrId) {}
}
