package dev.iamkavindu.streamapp.backend.support;

import java.util.HexFormat;
import java.util.UUID;

public final class TestData {

    public static final String VALID_SHA256_HEX = "a".repeat(64);

    private TestData() {}

    public static byte[] uniqueShaBytes() {
        var bytes = new byte[32];
        var uuid = UUID.randomUUID();
        System.arraycopy(longToBytes(uuid.getMostSignificantBits()), 0, bytes, 0, 8);
        System.arraycopy(longToBytes(uuid.getLeastSignificantBits()), 0, bytes, 8, 8);
        System.arraycopy(longToBytes(UUID.randomUUID().getMostSignificantBits()), 0, bytes, 16, 8);
        System.arraycopy(longToBytes(UUID.randomUUID().getLeastSignificantBits()), 0, bytes, 24, 8);
        return bytes;
    }

    public static String uniqueShaHex() {
        return HexFormat.of().formatHex(uniqueShaBytes());
    }

    private static byte[] longToBytes(long value) {
        return new byte[] {
                (byte) (value >>> 56),
                (byte) (value >>> 48),
                (byte) (value >>> 40),
                (byte) (value >>> 32),
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
