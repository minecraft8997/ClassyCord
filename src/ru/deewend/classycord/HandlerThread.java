package ru.deewend.classycord;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
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
                    int available = clientInputStream.available();
                    if (available >= bytesCount) {
                        byte[] packet = new byte[(bytesCount ==
                                SocketHolder.ANY_PACKET_LENGTH ? available : bytesCount)];
                        //noinspection ResultOfMethodCallIgnored
                        clientInputStream.read(packet);
                        handleDataFromClient(holder, packet);
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

                    int bytesCount = holder.getExpectedServerPacketLength();
                    InputStream serverInputStream = holder.getServerInputStream();
                    int available = serverInputStream.available();
                    if (available >= bytesCount) {
                        byte[] packet = new byte[(bytesCount ==
                                SocketHolder.ANY_PACKET_LENGTH ? available : bytesCount)];
                        //noinspection ResultOfMethodCallIgnored
                        serverInputStream.read(packet);
                        handleDataFromServer(holder, packet);
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

    private static void handleDataFromClient(
            SocketHolder holder, byte[] packet
    ) throws IOException, SilentIOException {
        SocketHolder.State state = holder.getState();
        if (state != SocketHolder.State.WAITING_FOR_PLAYER_IDENTIFICATION) {
            OutputStream serverOutputStream = holder.getServerOutputStream();
            serverOutputStream.write(packet);
            serverOutputStream.flush();
            holder.getAnalyzingStream().write(packet);

            return;
        }
        ByteArrayInputStream stream0 = new ByteArrayInputStream(packet);
        DataInputStream stream = new DataInputStream(stream0);

        if (!state.checkClientPacketId(stream)) {
            throw new SilentIOException("Unexpected packetId");
        }
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

        boolean supportsCPE = (stream.readUnsignedByte() == Utils.MAGIC);
        if (supportsCPE) {
            holder.setState(SocketHolder.State.WAITING_FOR_EXT_INFO_PT_1);
        } else {
            holder.setState(SocketHolder.State.CONNECTED);
        }
        holder.setSupportsCPE(supportsCPE);

        holder.setGameServer(ClassyCord.getInstance().getFirstServer());
    }

    private static void handleDataFromServer(
            SocketHolder holder, byte[] packet
    ) throws IOException, SilentIOException {
        OutputStream clientOutputStream = holder.getOutputStream();
        clientOutputStream.write(packet);
        clientOutputStream.flush();

        SocketHolder.State state = holder.getState();
        if (state == SocketHolder.State.CONNECTED) return;

        ByteArrayInputStream stream0 = new ByteArrayInputStream(packet);
        DataInputStream stream = new DataInputStream(stream0);

        switch (state) {
            case WAITING_FOR_EXT_INFO_PT_1: {
                int packetId = stream.readUnsignedByte();
                switch (packetId) {
                    case Utils.EXT_INFO_PACKET: {
                        // we don't have to skip bytes since it's a "toy" stream
                        holder.setState(SocketHolder.State.WAITING_FOR_EXT_INFO_PT_2);

                        break;
                    }
                    case Utils.DISCONNECT_PACKET: {
                        throw new SilentIOException(Utils.readMCString(stream));
                    }
                    default: {
                        // we cannot receive a ServerIdentification packet at this state
                        throw new SilentIOException("Unexpected packetId");
                    }
                }

                break;
            }
            case WAITING_FOR_EXT_INFO_PT_2: {
                short extEntryCount = stream.readShort();
                holder.setExpectedExtEntryCount(extEntryCount);
                holder.setState(SocketHolder.State.WAITING_FOR_ALL_EXT_ENTRIES);

                break;
            }
            case WAITING_FOR_ALL_EXT_ENTRIES: {
                int extEntryCount = holder.getExpectedExtEntryCount();
                Object[][] CPEArray = new Object[extEntryCount][2];
                for (int i = 0; i < CPEArray.length; i++) {
                    int packetId = stream.readUnsignedByte();
                    if (packetId != Utils.EXT_ENTRY_PACKET) {
                        // it's very unlikely we can notice a Disconnect
                        // packet here; we've already received (extEntryCount * 69)
                        // bytes

                        throw new SilentIOException("Unexpected packetId");
                    }
                    String extName = Utils.readMCString(stream);
                    int version = stream.readInt();

                    CPEArray[i][0] = extName;
                    CPEArray[i][1] = version;
                }
                holder.setCPEArrayConnectionWasInitializedWith(CPEArray);
                holder.setState(SocketHolder.State.CONNECTED);

                break;
            }
            default: {
                throw new UnsupportedOperationException("Unknown state");
            }
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
            Log.s("An exception or error has occurred", t);
        } finally {
            Log.s("HandlerThread has died, the proxy will be terminated");

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
        // it will most likely mess up with another packet though
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
