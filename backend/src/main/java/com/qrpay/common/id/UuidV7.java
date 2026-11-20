package com.qrpay.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7: 48-bit unix-millis timestamp followed by random bits.
 * Time-ordered ids keep B-tree index inserts localized (unlike random v4 UUIDs)
 * while still being globally unique and safe to generate on any app node.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        long timestamp = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL;
        long msb = (timestamp << 16) | (0x7L << 12) | RANDOM.nextInt(1 << 12);
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
