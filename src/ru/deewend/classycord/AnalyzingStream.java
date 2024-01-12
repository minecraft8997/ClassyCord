package ru.deewend.classycord;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public class AnalyzingStream extends OutputStream {
    public static final String EXPECTED_MESSAGE_START = "/ccgoto ";

    private final SocketHolder holder;
    private long currentPos = Long.MIN_VALUE;
    private final Map<Long, byte[]> map = new HashMap<>();
    private final List<Long> keysToRemove = new ArrayList<>();

    public AnalyzingStream(SocketHolder holder) {
        this.holder = holder;
    }

    @Override
    public void write(int b) throws IOException {
        if (b == Utils.MESSAGE_PACKET) {
            map.put(currentPos, new byte[Utils.PROTOCOL_STRING_LENGTH]);
        }
        for (Map.Entry<Long, byte[]> entry : map.entrySet()) {
            long pos = entry.getKey();
            byte[] buffer = entry.getValue();
            int bufferIdx = (int) (currentPos - pos - 1 - /* skipping playerId */ 1);
            if (bufferIdx < 0) continue;
            buffer[bufferIdx] = (byte) b;
            if (bufferIdx == Utils.PROTOCOL_STRING_LENGTH - 1) {
                handleMessage(Utils.readMCString(buffer));
                keysToRemove.add(pos);
            }
        }
        if (!keysToRemove.isEmpty()) {
            for (Long key : keysToRemove) map.remove(key);

            keysToRemove.clear();
        }
        currentPos++;
    }

    private void handleMessage(String message) {
        message = message.toLowerCase();

        if (message.startsWith(EXPECTED_MESSAGE_START)) {
            String serverName = message.substring(EXPECTED_MESSAGE_START.length());

            GameServer gameServer = ClassyCord.getInstance().getGameServer(serverName);
            if (gameServer == null || holder.getGameServer() == gameServer) return;

            holder.setPendingGameServer(gameServer);
        }
    }
}
