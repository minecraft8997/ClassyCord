package ru.deewend.classycord;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

public class SocketHolder {
    public enum State {
        WAITING_FOR_PLAYER_IDENTIFICATION(0x00, (1 + 1 + 64 + 64 + 1)),
        /*
         * We have to separate a single state between two parts, because
         * theoretically we can just receive a Disconnect packet (65 bytes)
         * instead of ExtInfo (67 bytes). If we'll be waiting for exactly 67 bytes,
         * this may lead to a freeze.
         *
         * Thus, we'll be waiting for at least 65 bytes at first time.
         */
        WAITING_FOR_EXT_INFO_PT_1(ANY_PACKET_ID, ANY_PACKET_LENGTH),
        WAITING_FOR_EXT_INFO_PT_2(ANY_PACKET_ID, ANY_PACKET_LENGTH),
        WAITING_FOR_ALL_EXT_ENTRIES(ANY_PACKET_ID, ANY_PACKET_LENGTH),
        CONNECTED(ANY_PACKET_ID, ANY_PACKET_LENGTH);

        private final int expectedClientPacketId;
        private final int expectedClientPacketLength;

        State(int expectedClientPacketId, int expectedClientPacketLength) {
            if (expectedClientPacketLength < 1) {
                throw new IllegalArgumentException("Too low expectedClientPacketLength");
            }

            this.expectedClientPacketId = expectedClientPacketId;
            this.expectedClientPacketLength = expectedClientPacketLength;
        }

        public int getExpectedClientPacketLength() {
            return expectedClientPacketLength;
        }

        public boolean checkClientPacketId(DataInputStream stream) throws IOException {
            if (expectedClientPacketId == ANY_PACKET_ID) return true;

            int packetId = stream.readUnsignedByte();

            return (expectedClientPacketId == packetId);
        }
    }

    public static final int ANY_PACKET_ID = -1;
    public static final int ANY_PACKET_LENGTH = 1;

    private final long creationTimestamp;
    private long lastTestedIfAlive;

    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private long lastReadTimestamp;

    private GameServer gameServer;
    private Socket serverSocket;
    private InputStream serverInputStream;
    private OutputStream serverOutputStream;
    private long lastServerReadTimestamp;

    private State state = State.WAITING_FOR_PLAYER_IDENTIFICATION;
    private boolean supportsCPE;
    private short expectedExtEntryCount;
    private Object[][] CPEArrayConnectionWasInitializedWith;
    private String username;

    public SocketHolder(Socket socket) throws IOException {
        this.creationTimestamp = System.currentTimeMillis();
        this.lastTestedIfAlive = creationTimestamp;

        // Client --> Proxy
        this.socket = socket;
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
        this.lastReadTimestamp = creationTimestamp;
    }

    public void setGameServer(GameServer gameServer) throws IOException {
        Objects.requireNonNull(gameServer);
        if (username == null) {
            throw new IllegalStateException("Cannot connect " +
                    "to a GameServer without knowing the player's username");
        }
        Log.i((this.gameServer == gameServer ? "Rec" : "C") +
                "onnecting " + username + " to " + gameServer.getName());

        this.gameServer = gameServer;
        Utils.close(serverSocket);

        // Proxy --> Game Server
        serverSocket = new Socket(gameServer.getAddress(), gameServer.getPort());
        serverSocket.setTcpNoDelay(true);
        serverInputStream = serverSocket.getInputStream();
        serverOutputStream = serverSocket.getOutputStream();
        lastServerReadTimestamp = System.currentTimeMillis();

        // writing PlayerIdentification packet
        serverOutputStream.write(Utils.SIDE_IDENTIFICATION_PACKET);
        serverOutputStream.write(Utils.PROTOCOL_VERSION);
        Utils.writeMCString(username, serverOutputStream);
        String verificationKey = Utils.md5(ClassyCord.getInstance().getSalt() + username);
        Utils.writeMCString(verificationKey, serverOutputStream);
        serverOutputStream.write(supportsCPE ? Utils.MAGIC : 0x00);
        serverOutputStream.flush();
    }

    public int getExpectedServerPacketLength() {
        switch (state) {
            case WAITING_FOR_EXT_INFO_PT_1: return 65;
            case WAITING_FOR_EXT_INFO_PT_2: return 2; /* only one short is remaining */
            case WAITING_FOR_ALL_EXT_ENTRIES: return expectedExtEntryCount * 69;
            default: return ANY_PACKET_LENGTH;
        }
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    public long getLastTestedIfAlive() {
        return lastTestedIfAlive;
    }

    public void setLastTestedIfAlive(long lastTestedIfAlive) {
        this.lastTestedIfAlive = lastTestedIfAlive;
    }

    public Socket getSocket() {
        return socket;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public long getLastReadTimestamp() {
        return lastReadTimestamp;
    }

    public void setLastReadTimestamp(long lastReadTimestamp) {
        this.lastReadTimestamp = lastReadTimestamp;
    }

    public GameServer getGameServer() {
        return gameServer;
    }

    public Socket getServerSocket() {
        return serverSocket;
    }

    public InputStream getServerInputStream() {
        return serverInputStream;
    }

    public OutputStream getServerOutputStream() {
        return serverOutputStream;
    }

    public long getLastServerReadTimestamp() {
        return lastServerReadTimestamp;
    }

    public void setLastServerReadTimestamp(long lastServerReadTimestamp) {
        this.lastServerReadTimestamp = lastServerReadTimestamp;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public boolean doesSupportCPE() {
        return supportsCPE;
    }

    public void setSupportsCPE(boolean supportsCPE) {
        this.supportsCPE = supportsCPE;
    }

    public short getExpectedExtEntryCount() {
        return expectedExtEntryCount;
    }

    public void setExpectedExtEntryCount(short expectedExtEntryCount) {
        this.expectedExtEntryCount = expectedExtEntryCount;
    }

    public Object[][] getCPEArrayConnectionWasInitializedWith() {
        return CPEArrayConnectionWasInitializedWith;
    }

    public void setCPEArrayConnectionWasInitializedWith(Object[][] CPEArray) {
        this.CPEArrayConnectionWasInitializedWith = CPEArray;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;

        Log.i(Utils.getAddress(socket) + " logged in as " + username);
    }
}
