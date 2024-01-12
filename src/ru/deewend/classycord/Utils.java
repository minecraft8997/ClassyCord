package ru.deewend.classycord;

import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Utils {
    public static final int SIDE_IDENTIFICATION_PACKET = 0x00;
    public static final int PROTOCOL_VERSION = 0x07;
    public static final int MAGIC = 0x42;
    public static final int DISCONNECT_PACKET = 0x0E;
    public static final int EXT_INFO_PACKET = 0x10;
    public static final int EXT_ENTRY_PACKET = 0x11;
    public static final int MESSAGE_PACKET = 0x0d;
    public static final int PROTOCOL_STRING_LENGTH = 64;

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

    public static void sendDisconnect(Socket socket, String reason) {
        if (socket == null) return;

        try {
            sendDisconnect(socket.getOutputStream(), reason);
        } catch (IOException ignored) {
            /* Ignoring this. */
        }
    }
    
    public static void sendDisconnect(SocketHolder holder, String reason) {
        if (holder == null) return;

        sendDisconnect(holder.getOutputStream(), reason);
    }

    public static void sendDisconnect(OutputStream dst, String reason) {
        if (dst == null) return;

        // Proxy --> Client
        try {
            dst.write(DISCONNECT_PACKET);
            writeMCString(reason, dst);
            dst.flush();
        } catch (IOException ignored) {
            /* Ignoring this. */
        }
    }

    public static String getAddress(Socket socket) {
        return socket.getRemoteSocketAddress().toString();
    }

    public static void writeMCString(String s, OutputStream dst) throws IOException {
        byte[] bytes = s.getBytes("Cp437");
        if (bytes.length > PROTOCOL_STRING_LENGTH) {
            writeMCString("<the string is too long, hashcode=" + s.hashCode() + ">", dst);

            return;
        }
        byte[] padding = new byte[PROTOCOL_STRING_LENGTH - bytes.length];
        Arrays.fill(padding, (byte) 0x20);
        dst.write(bytes);
        dst.write(padding);
    }

    public static String readMCString(DataInputStream src) throws IOException {
        byte[] buffer = new byte[PROTOCOL_STRING_LENGTH];
        src.readFully(buffer);

        return readMCString(buffer);
    }

    public static String readMCString(byte[] buffer) throws IOException {
        int end = -1;
        for (int i = PROTOCOL_STRING_LENGTH - 1; i >= 0; i--) {
            if (buffer[i] != 0x20) {
                end = i + 1;

                break;
            }
        }
        if (end == -1) return "";

        byte[] result = new byte[end];
        System.arraycopy(buffer, 0, result, 0, result.length);

        return new String(result);
    }

    public static boolean validateUsername(String username) {
        if (username == null) {
            return false; // wut?
        }
        if (username.length() < 2 || username.length() > 16) {
            return false; // too short and too large nicknames are not allowed
        }
        for (int i = 0; i < username.length(); i++) {
            char current = username.charAt(i);
            if (current == '_' || current == '.') {
                continue; // this is allowed
            }
            if (current >= '0' && current <= '9') {
                continue; // numbers are allowed too
            }
            if ((current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')) {
                continue; // characters from the Latin alphabet are allowed, of course
            }

            // we have encouraged an unknown character
            // this username has failed our validation
            return false;
        }

        // we haven't noticed anything suspicious
        // OK!
        return true;
    }

    public static boolean authenticatePlayer(String username, String verificationKey) {
        if (!ClassyCord.getInstance().isOnlineMode()) return true;

        String salt = ClassyCord.getInstance().getSalt();
        String hash = md5(salt + username);

        return verificationKey.equals(hash) ||
                verificationKey.equals("0" + hash) ||
                verificationKey.equals("00" + hash);
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

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public static int getOnlinePlayerCount() {
        int playerCount = 0;
        int maxCount = ClassyCord.getInstance().getMaxHandlerThreadCount();
        for (int i = 0; i < maxCount; i++) {
            HandlerThread thread = ClassyCord.getInstance().getHandlerThreadAt(i);

            synchronized (thread) {
                playerCount += thread.getClientList().size();
            }
        }

        return playerCount;
    }

    public static <K, V> void removeKeys(List<K> keysToRemove, Map<K, V> map) {
        if (!keysToRemove.isEmpty()) {
            for (K key : keysToRemove) {
                map.remove(key);
            }
            keysToRemove.clear();
        }
    }

    public static String randomSalt() {
        // uuids are generated using SecureRandom (cryptographically secure generator)
        String salt = UUID.randomUUID().toString() + UUID.randomUUID();
        salt = salt.replace("-", "");

        return salt;
    }

    public static long delta(long timestamp) {
        return System.currentTimeMillis() - timestamp;
    }
}
