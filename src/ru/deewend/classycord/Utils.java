package ru.deewend.classycord;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

public class Utils {
    public static final int PLAYER_IDENTIFICATION_PACKET = 0x00;
    public static final int PROTOCOL_VERSION = 0x07;
    public static final int MAGIC = 0x42;
    public static final int PING_PACKET = 0x01;
    public static final int KICK_PACKET = 0xFF;

    private Utils() {
    }

    public static void close(Closeable closeable) {
        if (closeable == null) return;

        try {
            closeable.close();
        } catch (IOException ignored) {
            /* Ignoring this. */
        }
    }

    public static void writeMCString(String s, OutputStream to) throws IOException {
        byte[] bytes = s.getBytes("Cp437");
        if (bytes.length > 64) {
            writeMCString("<the string is too long, hashcode=" + s.hashCode() + ">", to);

            return;
        }
        byte[] padding = new byte[64 - bytes.length];
        Arrays.fill(padding, (byte) 0x20);
        to.write(bytes);
        to.write(padding);
    }

    public static String md5(String str) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        md5.update(str.getBytes(StandardCharsets.UTF_8));

        return String.format("%032x", new BigInteger(1, md5.digest()));
    }

    public static String randomSalt() {
        // uuids are generated using SecureRandom (cryptographically secure generator)
        String salt = UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID();
        salt = salt.replace("-", "");

        return salt;
    }

    public static long delta(long timestamp) {
        return System.currentTimeMillis() - timestamp;
    }
}
