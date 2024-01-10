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
        WAITING_FOR_SERVER_SUPPORTED_CPE_LIST(-1 /* 0x10 */, 1),
        CONNECTED(-1, 1);

        private final int expectedPacketId;
        private final int expectedClientPacketLength;

        State(int expectedPacketId, int expectedClientPacketLength) {
            this.expectedPacketId = expectedPacketId;
            this.expectedClientPacketLength = expectedClientPacketLength;
        }

        public int getExpectedClientPacketLength() {
            return expectedClientPacketLength;
        }

        public boolean checkPacketId(DataInputStream stream) throws IOException {
            if (expectedPacketId == -1) return true; // no need to check pid at this state

            int packetId = stream.readUnsignedByte();

            return (expectedPacketId == packetId);
        }
    }

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
    private String[][] CPE;
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
        serverOutputStream.write(Utils.PLAYER_IDENTIFICATION_PACKET);
        serverOutputStream.write(Utils.PROTOCOL_VERSION);
        Utils.writeMCString(username, serverOutputStream);
        String verificationKey = Utils.md5(ClassyCord.getInstance().getSalt() + username);
        Utils.writeMCString(verificationKey, serverOutputStream);
        serverOutputStream.write(Utils.MAGIC);
        serverOutputStream.flush();
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;

        Log.i(Utils.getAddress(socket) + " logged in as " + username);
    }
}
