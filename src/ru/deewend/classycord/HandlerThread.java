package ru.deewend.classycord;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HandlerThread extends Thread {
    public static final int MAX_ACTIVE_CONNECTIONS_COUNT = 100;
    public static final int TICK_RATE = 25;
    public static final int TICK_INTERVAL_MS = 1000 / TICK_RATE;
    public static final int READ_TIMEOUT = 420_000;

    private final List<SocketHolder> clientList;

    public HandlerThread() {
        setName("handler");
        setDaemon(true);

        this.clientList = new ArrayList<>();
    }

    public synchronized boolean addClient(Socket socket) throws IOException {
        if (clientList.size() >= MAX_ACTIVE_CONNECTIONS_COUNT) {
            return false;
        }
        clientList.add(new SocketHolder(socket));

        return true;
    }

    private synchronized void tick() {
        int clientListSize = clientList.size();
        global: for (int i = clientListSize - 1; i >= 0; i--) {
            SocketHolder holder = clientList.get(i);
            long currentTimeMillis = System.currentTimeMillis();
            try {
                while (true) {
                    int bytesCount = holder.getState().getExpectedClientPacketLength();
                    InputStream clientInputStream = holder.getInputStream();
                    // System.out.println("bf availbale");
                    int available = clientInputStream.available();
                    if (available >= bytesCount) {
                        byte[] packet = new byte[(bytesCount == 1 ? available : bytesCount)];
                        //noinspection ResultOfMethodCallIgnored
                        clientInputStream.read(packet);
                        handleIncomingDataFromClient(holder, packet);
                        holder.setLastReadTimestamp(currentTimeMillis);
                    } else {
                        if (Utils.delta(holder.getLastReadTimestamp()) >= READ_TIMEOUT) {
                            close(holder, null);

                            continue global;
                        }

                        break;
                    }
                }

                while (true) {
                    if (holder.getGameServer() == null) break;

                    //System.out.println("It's not null, sending some data");
                    int bytesCount = 1;
                    InputStream serverInputStream = holder.getServerInputStream();
                    int available = serverInputStream.available();
                    if (available >= bytesCount) {
                        byte[] packet = new byte[(bytesCount == 1 ? available : bytesCount)];
                        //noinspection ResultOfMethodCallIgnored
                        serverInputStream.read(packet);
                        //System.out.println("Sending " + Arrays.toString(packet));
                        holder.getOutputStream().write(packet);
                        holder.getOutputStream().flush();
                        holder.setLastServerReadTimestamp(currentTimeMillis);
                    } else {
                        if (Utils.delta(holder.getLastServerReadTimestamp()) >= READ_TIMEOUT) {
                            close(holder, null);

                            continue global;
                        }

                        break;
                    }
                }
            } catch (IOException | SilentIOException e) {
                close(holder, e);
            }
        }
    }

    private static void handleIncomingDataFromClient(
            SocketHolder holder, byte[] packet
    ) throws IOException, SilentIOException {
        ByteArrayInputStream stream0 = new ByteArrayInputStream(packet);
        DataInputStream stream = new DataInputStream(stream0);

        SocketHolder.State state = holder.getState();
        if (!state.checkPacketId(stream)) {
            throw new SilentIOException("Unexpected packetId");
        }
        if (state == SocketHolder.State.WAITING_FOR_PLAYER_IDENTIFICATION) {
            int protocolVersion = stream.readUnsignedByte();
            if (protocolVersion != Utils.PROTOCOL_VERSION) {
                throw new SilentIOException("Unsupported protocol version");
            }
            String username = Utils.readMCString(stream);
            if (!Utils.validateUsername(username)) {
                throw new SilentIOException("Illegal username");
            }
            String verificationKey = Utils.readMCString(stream);
            if (!Utils.authenticatePlayer(username, verificationKey)) {
                throw new SilentIOException("Failed " +
                        "to authenticate, try refreshing the server list");
            }
            holder.setUsername(username);
            holder.setGameServer(ClassyCord.getInstance().getFirstServer());

            int magic = stream.readUnsignedByte();
            if (magic == Utils.MAGIC) {
                holder.setState(SocketHolder.State
                        .WAITING_FOR_SERVER_SUPPORTED_CPE_LIST);
            } else {
                holder.setState(SocketHolder.State.CONNECTED);
            }
        } else {
            OutputStream serverOutputStream = holder.getServerOutputStream();
            serverOutputStream.write(packet);
            serverOutputStream.flush();
        }
    }

    @Override
    @SuppressWarnings({"InfiniteLoopStatement", "BusyWait", "finally"})
    public void run() {
        try {
            while (true) {
                long start = System.currentTimeMillis();
                tick();
                long delta = Utils.delta(start);
                Thread.sleep(Math.max(TICK_INTERVAL_MS - delta, 1));
            }
        } catch (Throwable t) {
            System.err.println("An exception/error has occurred, " +
                    "printing the stacktrace...");
            t.printStackTrace();
        } finally {
            System.err.println("HandlerThread has died, terminating the proxy...");

            System.exit(-1);
        }
    }

    private void close(SocketHolder holder, Throwable t) {
        // if (t != null) t.printStackTrace();

        String reason;
        if (t instanceof SilentIOException) {
            reason = t.getMessage();
        } else {
            reason = "A disconnect or timeout occurred in your connection";
        }
        // it will most likely mess up with other packets though
        Utils.sendDisconnect(holder, reason);

        Socket clientSocket = holder.getSocket();
        Utils.close(clientSocket);
        Utils.close(holder.getServerSocket());
        synchronized (this) {
            clientList.remove(holder);
        }
        String username = holder.getUsername();

        Log.i(Utils.getAddress(clientSocket) +
                (username != null ? " (" + username + ")" : "") + " disconnected");
    }
}
