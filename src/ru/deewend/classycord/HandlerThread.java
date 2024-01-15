package ru.deewend.classycord;

import java.io.*;
import java.net.Socket;
import java.util.*;

public class HandlerThread extends Thread {
    public static final int MAX_ACTIVE_CONNECTIONS_COUNT =
            ClassyCord.getInstance().getMaxConnectionsCountPerHandlerThread();
    public static final long TICK_INTERVAL_MS =
            1000L / ClassyCord.getInstance().getTickRateOfHandlerThread();
    public static final long READ_TIMEOUT =
            ClassyCord.getInstance().getReadTimeoutMillis();
    public static final long EXCEPTION_MAP_STORAGE_TIMEOUT =
            ClassyCord.getInstance().getExceptionMapStorageTimeoutMillis();
    public static final int MIN_TICKS_TO_WAIT_BEFORE_RECONNECTING =
            ClassyCord.getInstance().getMinTicksToWaitBeforeReconnecting();

    private final List<SocketHolder> clientList = new ArrayList<>();
    private final List<String> keysToRemove = new ArrayList<>();
    private final Map<String, Pair<GameServer, Long>> exceptionMap = new HashMap<>();

    public HandlerThread() {
        setName("handler");
        setDaemon(true);
    }

    public static String getAddressAndUsername(SocketHolder holder) {
        String username = holder.getUsername();

        return Utils.getAddress(holder.getSocket()) +
                (username != null ? " (" + username + ")" : "");
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

            for (Map.Entry<String, Pair<GameServer, Long>> entry : exceptionMap.entrySet()) {
                long timestampAdded = entry.getValue().getSecond();
                if (currentTimeMillis - timestampAdded >= EXCEPTION_MAP_STORAGE_TIMEOUT) {
                    keysToRemove.add(entry.getKey());
                }
            }
            Utils.removeKeys(keysToRemove, exceptionMap);

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
                        holder.resetTicksNoNewDataFromServer();
                        holder.setLastServerReadTimestamp(currentTimeMillis);
                    } else {
                        holder.incrementTicksNoNewDataFromServer();
                        GameServer pendingGameServer = holder.getPendingGameServer();
                        if (holder.getTicksNoNewDataFromServer() >=
                                MIN_TICKS_TO_WAIT_BEFORE_RECONNECTING &&
                                pendingGameServer != null
                        ) {
                            holder.setGameServer(pendingGameServer);
                            holder.setPendingGameServer(null);
                        }
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

    private void handleDataFromClient(
            SocketHolder holder, byte[] packet
    ) throws IOException, SilentIOException {
        SocketHolder.State state = holder.getState();
        if (state != SocketHolder.State.WAITING_FOR_PLAYER_IDENTIFICATION) {
            AnalyzingStream analyzingStream = holder.getAnalyzingStream();
            if (!analyzingStream.isPaused()) {
                OutputStream serverOutputStream = holder.getServerOutputStream();
                serverOutputStream.write(packet);
                serverOutputStream.flush();
            }
            analyzingStream.write(packet);

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
        holder.setSupportsCPE(supportsCPE);

        Pair<GameServer, Long> exception = exceptionMap.get(username);
        GameServer desiredGameServer;
        if (exception != null) {
            desiredGameServer = exception.getFirst();
            exceptionMap.remove(username);
        } else {
            desiredGameServer = ClassyCord.getInstance().getFirstServer();
        }
        holder.setGameServer(desiredGameServer);
    }

    private void handleDataFromServer(
            SocketHolder holder, byte[] packet
    ) throws IOException, SilentIOException {
        if (holder.connectingTwice && holder.getState() != SocketHolder.State.CONNECTED) {

        } else {
            OutputStream clientOutputStream = holder.getOutputStream();
            clientOutputStream.write(packet);
            clientOutputStream.flush();
        }


        AnalyzingStream analyzingStream = holder.getAnalyzingStream();
        if (analyzingStream.isRecording()) {
            byte[] cpeHandshake = analyzingStream.stopRecording();
            holder.setClientCPEHandshake(cpeHandshake);
        }

        SocketHolder.State state = holder.getState();
        if (state == SocketHolder.State.CONNECTED) return;

        ByteArrayInputStream stream0 = new ByteArrayInputStream(packet);
        DataInputStream stream = new DataInputStream(stream0);

        switch (state) {
            case WAITING_FOR_SERVER_EXT_INFO_PT_1: {
                int packetId = stream.readUnsignedByte();
                switch (packetId) {
                    case Utils.EXT_INFO_PACKET: {
                        // we don't have to skip bytes since it's a "toy" stream
                        holder.setState(SocketHolder.State.WAITING_FOR_SERVER_EXT_INFO_PT_2);

                        break;
                    }
                    case Utils.SIDE_IDENTIFICATION_PACKET: {
                        holder.setState(SocketHolder.State.CONNECTED);

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
            case WAITING_FOR_SERVER_EXT_INFO_PT_2: {
                short extEntryCount = stream.readShort();
                holder.setExpectedServerExtEntryCount(extEntryCount);
                holder.setState(SocketHolder.State.WAITING_FOR_ALL_SERVER_EXT_ENTRIES);

                break;
            }
            case WAITING_FOR_ALL_SERVER_EXT_ENTRIES: {
                int extEntryCount = holder.getExpectedServerExtEntryCount();
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
                Object[][] initialCPEArray = holder
                        .getServerCPEArrayConnectionWasInitializedWith();
                if (initialCPEArray != null) {
                    if (!Arrays.deepEquals(CPEArray, initialCPEArray)) {
                        exceptionMap.put(holder.getUsername(),
                                Pair.of(holder.getGameServer(), System.currentTimeMillis()));

                        throw new SilentIOException("Press \"Reconnect\" button");
                    }
                    byte[] clientCPEHandshake = holder.getClientCPEHandshake();
                    OutputStream serverOutputStream = holder.getServerOutputStream();
                    serverOutputStream.write(clientCPEHandshake);
                    byte[] pausedBytes = analyzingStream.finishPause();
                    serverOutputStream.write(pausedBytes);

                    serverOutputStream.flush();
                } else {
                    holder.setServerCPEArrayConnectionWasInitializedWith(CPEArray);
                    holder.getAnalyzingStream().startRecording();
                }
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
                Thread.sleep(Math.max(TICK_INTERVAL_MS - delta, 1L));
            }
        } catch (Throwable t) {
            Log.s("An exception or error has occurred", t);
        } finally {
            Log.s("HandlerThread has died, the proxy will be terminated");

            System.exit(-1);
        }
    }

    private void close(SocketHolder holder, Throwable t) {
        if (ClassyCord.DEBUG) {
            Log.w("Caught a Throwable, closing the connection", t);
        }

        String reason;
        if (t instanceof SilentIOException) {
            reason = t.getMessage();
        } else {
            reason = "A disconnect or timeout occurred in your connection";
        }
        // there is a chance it will mess up with another packet though
        Utils.sendDisconnect(holder, reason);

        Utils.close(holder.getSocket());
        Utils.close(holder.getServerSocket());
        synchronized (this) {
            clientList.remove(holder);
        }

        Log.i(getAddressAndUsername(holder) + " disconnected");
    }

    List<SocketHolder> getClientList() {
        return clientList;
    }
}
