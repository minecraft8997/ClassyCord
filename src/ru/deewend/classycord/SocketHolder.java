package ru.deewend.classycord;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

public class SocketHolder {
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

    private boolean connecting;
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
        if (this.gameServer == gameServer) {
            Log.i("Reconnecting " + username + " to " + gameServer.getName());
        }

        this.gameServer = gameServer;
        Utils.close(serverSocket);

        // Proxy --> Game Server
        serverSocket = new Socket(gameServer.getAddress(), gameServer.getPort());
        serverInputStream = serverSocket.getInputStream();
        serverOutputStream = serverSocket.getOutputStream();
        lastServerReadTimestamp = System.currentTimeMillis();

        serverOutputStream.write(Utils.PLAYER_IDENTIFICATION_PACKET);
        serverOutputStream.write(Utils.PROTOCOL_VERSION);
        Utils.writeMCString(username, serverOutputStream);
        String mppass = Utils.md5(ClassyCord.getInstance().getSalt() + username);
        Utils.writeMCString(mppass, serverOutputStream);
        serverOutputStream.write(Utils.MAGIC);
        serverOutputStream.flush();

        connecting = true;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
