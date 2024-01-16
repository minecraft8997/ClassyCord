package ru.deewend.classycord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public class AnalyzingStream extends OutputStream {
    public static final String EXPECTED_MESSAGE_START =
            ClassyCord.getInstance().getGotoCommandStart();

    private final SocketHolder holder;
    private long currentPos = Long.MIN_VALUE;
    private final Map<Long, byte[]> map = new HashMap<>();
    private final List<Long> keysToRemove = new ArrayList<>();
    private ByteArrayOutputStream recordedBytes;
    private boolean suppressingBytes;
    private boolean serverMode;
    private final byte[] last1KiBFromServer = new byte[1024];
    private int last1KiBFromServerIdx;

    public AnalyzingStream(SocketHolder holder) {
        this.holder = holder;
    }

    @Override
    public void write(int b) throws IOException {
        if (serverMode) {
            if (last1KiBFromServerIdx != last1KiBFromServer.length) {
                last1KiBFromServer[last1KiBFromServerIdx++] = (byte) b;
            } else {
                for (int i = 1; i < last1KiBFromServer.length; i++) {
                    last1KiBFromServer[i - 1] = last1KiBFromServer[i];
                }
                last1KiBFromServer[last1KiBFromServer.length - 1] = (byte) b;
            }

            return;
        }
        if (isRecording()) {
            recordedBytes.write(b);
        }
        if (b == Utils.MESSAGE_PACKET) {
            map.put(currentPos, new byte[Utils.PROTOCOL_STRING_LENGTH]);
        }
        for (Map.Entry<Long, byte[]> entry : map.entrySet()) {
            long pos = entry.getKey();
            byte[] buffer = entry.getValue();
            int bufferIdx = (int) (currentPos - pos - 1L - /* skipping playerId */ 1L);
            if (bufferIdx < 0) continue;
            buffer[bufferIdx] = (byte) b;
            if (bufferIdx == Utils.PROTOCOL_STRING_LENGTH - 1) {
                handleMessage(Utils.readMCString(buffer));
                keysToRemove.add(pos);
            }
        }
        Utils.removeKeys(keysToRemove, map);

        if (currentPos == Long.MAX_VALUE) {
            // good job, let's offer the client to reconnect
            throw new IOException("currentPos reached its max value");
        }
        currentPos++;
    }

    public void setServerMode(boolean serverMode) {
        this.serverMode = serverMode;
    }

    public void startSuppressing() {
        suppressingBytes = true;
    }

    public boolean isSuppressing() {
        return suppressingBytes;
    }

    public void finishSuppressing() {
        suppressingBytes = false;
    }

    public void startRecording() {
        recordedBytes = new ByteArrayOutputStream();
    }

    public boolean isRecording() {
        return recordedBytes != null;
    }

    public byte[] stopRecording() {
        byte[] recorded = recordedBytes.toByteArray();
        recordedBytes = null;

        return recorded;
    }

    public String findDisconnectMessage() {
        for (int i = last1KiBFromServer.length - 1; i >= 0; i--) {
            if (last1KiBFromServer[i] == Utils.DISCONNECT_PACKET) {
                byte[] reasonBytes = new byte[64];
                System.arraycopy(last1KiBFromServer,
                        i, reasonBytes, 0, reasonBytes.length);

                return Utils.readMCString(reasonBytes);
            }
        }

        return null;
    }

    private void handleMessage(String message) {
        message = message.toLowerCase();

        if (message.startsWith(EXPECTED_MESSAGE_START)) {
            String serverName = message.substring(EXPECTED_MESSAGE_START.length());

            GameServer gameServer = ClassyCord.getInstance().getGameServer(serverName);
            if (gameServer == null || holder.getGameServer() == gameServer) return;

            holder.setPendingGameServer(gameServer);
            startSuppressing();
        }
    }
}
