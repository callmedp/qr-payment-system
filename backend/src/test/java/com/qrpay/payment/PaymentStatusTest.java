package com.qrpay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStatusTest {

    @Test
    void createdCanMoveToEveryTerminalState() {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(PaymentStatus.CREATED.canTransitionTo(next)).isEqualTo(next != PaymentStatus.CREATED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    void terminalStatesAreFinal(PaymentStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }
}
