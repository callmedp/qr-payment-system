package com.qrpay.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.qrpay.common.id.UuidV7;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = UuidV7.next();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsCurrentTimestamp() {
        long before = System.currentTimeMillis();
        long embedded = UuidV7.next().getMostSignificantBits() >>> 16;
        long after = System.currentTimeMillis();
        assertThat(embedded).isBetween(before, after);
    }

    @Test
    void idsFromLaterMillisecondsSortLater() throws InterruptedException {
        UUID first = UuidV7.next();
        Thread.sleep(2);
        UUID second = UuidV7.next();
        assertThat(Long.compareUnsigned(first.getMostSignificantBits(), second.getMostSignificantBits())).isNegative();
    }

    @Test
    void isUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            ids.add(UuidV7.next());
        }
        assertThat(ids).hasSize(100_000);
    }
}
