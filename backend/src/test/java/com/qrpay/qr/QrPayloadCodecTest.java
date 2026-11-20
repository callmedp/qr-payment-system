package com.qrpay.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.config.QrPayProperties;
import com.qrpay.qr.QrPayloadCodec.QrPayload;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrPayloadCodecTest {

    private static final String SECRET = "test-secret-key-0123456789abcdef";

    /** A v1 code as minted before the payload was slimmed, signed with {@link #SECRET}. */
    private static final String LEGACY_V1 =
            "qrpay://pay?v=1&qid=11111111-1111-4111-8111-111111111111"
                    + "&mid=22222222-2222-4222-8222-222222222222"
                    + "&pa=shop%40qrpay&pn=Shop&am=25000&exp=1900000000&cu=INR"
                    + "&sig=XAPLfcJI5YQwrGFaX2VQLQ";

    /** Correctly signed, but announces a version this codec does not know. */
    private static final String FUTURE_V3 =
            "qrpay://pay?v=3&qid=11111111-1111-4111-8111-111111111111&sig=a18kiJLku3yCZYfDBWpyDA";

    private final QrPayloadCodec codec = codecWithSecret(SECRET);

    @Test
    void roundTripsPayload() {
        QrPayload original = new QrPayload(UUID.randomUUID());

        String encoded = codec.encode(original);

        assertThat(encoded).startsWith("qrpay://pay?v=2&qid=" + original.qrId() + "&sig=");
        assertThat(codec.decodeAndVerify(encoded)).isEqualTo(original);
    }

    /** The code is a pointer and nothing else - no payee, amount, currency or expiry rides along. */
    @Test
    void carriesNothingButIdAndSignature() {
        String encoded = codec.encode(new QrPayload(UUID.randomUUID()));

        assertThat(encoded).matches("qrpay://pay\\?v=2&qid=[0-9a-f-]{36}&sig=[A-Za-z0-9_-]{22}");
        assertThat(encoded.length()).isLessThan(100);
    }

    /** Static and dynamic codes are indistinguishable on the wire; only the DB knows the difference. */
    @Test
    void hasTheSameShapeForEveryCode() {
        String a = codec.encode(new QrPayload(UUID.randomUUID()));
        String b = codec.encode(new QrPayload(UUID.randomUUID()));

        assertThat(a.length()).isEqualTo(b.length());
    }

    @Test
    void rejectsTamperedQrId() {
        UUID qrId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String encoded = codec.encode(new QrPayload(qrId));

        assertInvalid(encoded.replace("1111-4111", "1111-4112"));
    }

    @Test
    void rejectsPayloadSignedWithAnotherKey() {
        assertInvalid(codecWithSecret("some-other-secret-key-9876543210").encode(new QrPayload(UUID.randomUUID())));
    }

    /** Codes printed before the format was slimmed keep resolving; the extra params are ignored. */
    @Test
    void acceptsLegacyV1PayloadAndKeepsOnlyTheId() {
        QrPayload decoded = codec.decodeAndVerify(LEGACY_V1);

        assertThat(decoded.qrId()).isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    }

    @Test
    void rejectsLegacyV1PayloadWithATamperedAmount() {
        assertInvalid(LEGACY_V1.replace("am=25000", "am=00001"));
    }

    @Test
    void rejectsUnsupportedVersionEvenWhenCorrectlySigned() {
        assertThatThrownBy(() -> codec.decodeAndVerify(FUTURE_V3))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unsupported QR version");
    }

    @Test
    void rejectsOverlongPayload() {
        String padded = "qrpay://pay?v=2&qid=" + UUID.randomUUID() + "&x=" + "a".repeat(1024) + "&sig=whatever";

        assertThat(padded.length()).isGreaterThan(1024);
        assertInvalid(padded);
    }

    @Test
    void rejectsGarbage() {
        assertInvalid("upi://pay?pa=someone@bank");
        assertInvalid("qrpay://pay?v=2&qid=not-a-uuid");
        assertInvalid("qrpay://pay?v=2&qid=" + UUID.randomUUID()); // no signature at all
        assertInvalid(null);
    }

    private void assertInvalid(String raw) {
        assertThatThrownBy(() -> codec.decodeAndVerify(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.QR_INVALID));
    }

    private static QrPayloadCodec codecWithSecret(String secret) {
        var qr = new QrPayProperties.Qr(Base64.getEncoder().encodeToString(secret.getBytes()), "qrpay",
                Duration.ofMinutes(5), Duration.ofMinutes(30));
        return new QrPayloadCodec(new QrPayProperties(null, qr, null, null, null, null, null, null, null, null, null));
    }
}
