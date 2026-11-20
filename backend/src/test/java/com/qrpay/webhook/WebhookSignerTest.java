package com.qrpay.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test";
    private static final String NEW_SECRET = "whsec_new";
    private static final String OLD_SECRET = "whsec_old";
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void verifiesItsOwnSignature() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(SECRET, now.getEpochSecond(), "{\"a\":1}");
        assertThat(WebhookSigner.verify(SECRET, header, "{\"a\":1}", TOLERANCE, now)).isTrue();
    }

    @Test
    void rejectsModifiedBody() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(SECRET, now.getEpochSecond(), "{\"amount\":100}");
        assertThat(WebhookSigner.verify(SECRET, header, "{\"amount\":999}", TOLERANCE, now)).isFalse();
    }

    @Test
    void rejectsWrongSecret() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign("other", now.getEpochSecond(), "{}");
        assertThat(WebhookSigner.verify(SECRET, header, "{}", TOLERANCE, now)).isFalse();
    }

    @Test
    void rejectsReplayOutsideTolerance() {
        Instant signedAt = Instant.now().minus(Duration.ofMinutes(10));
        String header = WebhookSigner.sign(SECRET, signedAt.getEpochSecond(), "{}");
        assertThat(WebhookSigner.verify(SECRET, header, "{}", TOLERANCE, Instant.now())).isFalse();
    }

    @Test
    void rejectsMalformedHeader() {
        assertThat(WebhookSigner.verify(SECRET, "garbage", "{}", TOLERANCE, Instant.now())).isFalse();
        assertThat(WebhookSigner.verify(SECRET, null, "{}", TOLERANCE, Instant.now())).isFalse();
    }

    // ---------------------------------------------------------------- rotation grace window

    @Test
    void signsWithEverySecretDuringRotation() {
        String header = WebhookSigner.sign(List.of(NEW_SECRET, OLD_SECRET), Instant.now().getEpochSecond(), "{}");
        assertThat(header.split(",v1=", -1)).hasSize(3); // "t=..." plus one part per secret
    }

    /**
     * The point of the grace window: a receiver still holding the OLD secret must keep verifying.
     * Both orderings are asserted because a header carries several v1 parts and only one of them
     * belongs to any given receiver.
     */
    @Test
    void eitherSecretVerifiesWhileBothAreActive() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(List.of(NEW_SECRET, OLD_SECRET), now.getEpochSecond(), "{\"a\":1}");

        assertThat(WebhookSigner.verify(NEW_SECRET, header, "{\"a\":1}", TOLERANCE, now)).isTrue();
        assertThat(WebhookSigner.verify(OLD_SECRET, header, "{\"a\":1}", TOLERANCE, now)).isTrue();
    }

    @Test
    void rejectsASecretThatIsNotInTheHeader() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(List.of(NEW_SECRET, OLD_SECRET), now.getEpochSecond(), "{}");
        assertThat(WebhookSigner.verify("whsec_never_issued", header, "{}", TOLERANCE, now)).isFalse();
    }

    /** A tampered body must fail even though two signatures are present to match against. */
    @Test
    void rejectsModifiedBodyWithMultipleSignatures() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(List.of(NEW_SECRET, OLD_SECRET), now.getEpochSecond(), "{\"amount\":100}");
        assertThat(WebhookSigner.verify(NEW_SECRET, header, "{\"amount\":999}", TOLERANCE, now)).isFalse();
        assertThat(WebhookSigner.verify(OLD_SECRET, header, "{\"amount\":999}", TOLERANCE, now)).isFalse();
    }

    /** Once the grace window closes only the current secret is signed with, so the old one stops working. */
    @Test
    void oldSecretStopsVerifyingAfterGraceWindow() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(List.of(NEW_SECRET), now.getEpochSecond(), "{}");
        assertThat(WebhookSigner.verify(NEW_SECRET, header, "{}", TOLERANCE, now)).isTrue();
        assertThat(WebhookSigner.verify(OLD_SECRET, header, "{}", TOLERANCE, now)).isFalse();
    }
}
