package ru.deewend.classycord;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class SocketHolder {
    public enum State {
        WAITING_FOR_PLAYER_IDENTIFICATION(
                Utils.SIDE_IDENTIFICATION_PACKET, (1 + 1 + 64 + 64 + 1)),
        /*
         * We have to separate a single state between two parts, because
         * theoretically we can just receive a Disconnect packet (65 bytes)
         * instead of ExtInfo (67 bytes). If we'll be waiting for exactly 67 bytes,
         * this may lead to a freeze.
         *
         * Thus, we'll be waiting for at least 65 bytes at first time.
         */
        WAITING_FOR_SERVER_EXT_INFO_PT_1(ANY_PACKET_ID, ANY_PACKET_LENGTH),
        WAITING_FOR_SERVER_EXT_INFO_PT_2(ANY_PACKET_ID, ANY_PACKET_LENGTH),
        WAITING_FOR_ALL_SERVER_EXT_ENTRIES(ANY_PACKET_ID, ANY_PACKET_LENGTH),
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

    private final HandlerThread thread;
    private final long creationTimestamp;

    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final AnalyzingStream analyzingStream;
    private long lastReadTimestamp;

    private GameServer gameServer;
    private Socket serverSocket;
    private InputStream serverInputStream;
    private OutputStream serverOutputStream;
    private long lastServerReadTimestamp;

    private final Map<Object, Object> metadata = new HashMap<>();
    private State state = State.WAITING_FOR_PLAYER_IDENTIFICATION;
    private boolean clientSupportsCPE;
    private short expectedServerExtEntryCount;
    private Object[][] serverCPEArrayConnectionWasInitializedWith;
    private byte[] clientCPEHandshake;
    private String username;
    private Boolean CPEConnection;
    private int ticksNoNewDataFromServer;
    private GameServer pendingGameServer;
    private boolean connectingForTheFirstTime = true;

    public SocketHolder(HandlerThread thread, Socket socket) throws IOException {
        this.creationTimestamp = System.currentTimeMillis();
        this.thread = thread;

        // Client --> Proxy
        this.socket = socket;
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
        this.lastReadTimestamp = creationTimestamp;
        this.analyzingStream = new AnalyzingStream(this);
    }

    public void setGameServer(GameServer gameServer) throws IOException {
        Objects.requireNonNull(gameServer);

        synchronized (thread) {
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
            serverOutputStream.write(clientSupportsCPE ? Utils.MAGIC : 0x00);
            serverOutputStream.flush();

            if (clientSupportsCPE) {
                setState(SocketHolder.State.WAITING_FOR_SERVER_EXT_INFO_PT_1);
            } else {
                setState(SocketHolder.State.CONNECTED);
            }

            EventManager.getInstance().fireEvent(
                    new HandlerThread.GameServerSetEvent(thread, this, gameServer));
        }
    }

    public int getExpectedServerPacketLength() {
        synchronized (thread) {
            switch (state) {
                case WAITING_FOR_SERVER_EXT_INFO_PT_1: return 65;
                case WAITING_FOR_SERVER_EXT_INFO_PT_2: return 2; /* only one short is remaining */
                case WAITING_FOR_ALL_SERVER_EXT_ENTRIES: return expectedServerExtEntryCount * 69;
                default: return ANY_PACKET_LENGTH;
            }
        }
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
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

    public AnalyzingStream getAnalyzingStream() {
        return analyzingStream;
    }

    public long getLastReadTimestamp() {
        return lastReadTimestamp;
    }

    public void setLastReadTimestamp(long lastReadTimestamp) {
        synchronized (thread) {
            this.lastReadTimestamp = lastReadTimestamp;
        }
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
        synchronized (thread) {
            this.lastServerReadTimestamp = lastServerReadTimestamp;
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        synchronized (thread) {
            this.state = state;
        }
    }

    public boolean doesSupportCPE() {
        return clientSupportsCPE;
    }

    public void setClientSupportsCPE(boolean clientSupportsCPE) {
        synchronized (thread) {
            this.clientSupportsCPE = clientSupportsCPE;
        }
    }

    public short getExpectedServerExtEntryCount() {
        return expectedServerExtEntryCount;
    }

    public void setExpectedServerExtEntryCount(short expectedServerExtEntryCount) {
        synchronized (thread) {
            this.expectedServerExtEntryCount = expectedServerExtEntryCount;
        }
    }

    public Object[][] getServerCPEArrayConnectionWasInitializedWith() {
        return serverCPEArrayConnectionWasInitializedWith;
    }

    public void setServerCPEArrayConnectionWasInitializedWith(Object[][] CPEArray) {
        synchronized (thread) {
            this.serverCPEArrayConnectionWasInitializedWith = CPEArray;
        }
    }

    public byte[] getClientCPEHandshake() {
        return clientCPEHandshake;
    }

    public void setClientCPEHandshake(byte[] clientCPEHandshake) {
        synchronized (thread) {
            this.clientCPEHandshake = clientCPEHandshake;
        }
    }

    public String getUsername() {
        return username;
    }

    public Boolean isCPEConnection() {
        return CPEConnection;
    }

    public void setCPEConnection(boolean CPEConnection) {
        synchronized (thread) {
            this.CPEConnection = CPEConnection;
        }
    }

    public void setUsername(String username) {
        synchronized (thread) {
            this.username = username;

            Log.i(Utils.getAddress(socket) + " logged in as " + username);
        }
    }

    public int getTicksNoNewDataFromServer() {
        return ticksNoNewDataFromServer;
    }

    public void incrementTicksNoNewDataFromServer() {
        synchronized (thread) {
            this.ticksNoNewDataFromServer++;
        }
    }

    public void resetTicksNoNewDataFromServer() {
        synchronized (thread) {
            this.ticksNoNewDataFromServer = 0;
        }
    }

    public GameServer getPendingGameServer() {
        return pendingGameServer;
    }

    public void setPendingGameServer(GameServer pendingGameServer) {
        synchronized (thread) {
            if (pendingGameServer == null) connectingForTheFirstTime = false;

            this.pendingGameServer = pendingGameServer;
        }
    }

    public boolean isConnectingForTheFirstTime() {
        return connectingForTheFirstTime;
    }

    public synchronized void putMetadata(Object key, Object value) {
        metadata.put(key, value);
    }

    public synchronized boolean hasMetadata(Object key) {
        return metadata.containsKey(key);
    }

    public synchronized Object getMetadata(Object key) {
        return metadata.get(key);
    }
}
