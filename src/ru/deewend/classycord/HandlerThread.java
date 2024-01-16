package ru.deewend.classycord;

import java.io.*;
import java.net.Socket;
import java.util.*;

public class HandlerThread extends Thread {
    public static abstract class HandlerThreadEvent extends Event {
        protected final HandlerThread thread;
        protected final SocketHolder holder;

        public HandlerThreadEvent(boolean cancellable, HandlerThread thread) {
            this(cancellable, thread, null);
        }

        public HandlerThreadEvent(
                boolean cancellable, HandlerThread thread, SocketHolder holder
        ) {
            super(cancellable);

            this.thread = thread;
            this.holder = holder;
        }

        public final HandlerThread getThread() {
            return thread;
        }

        public final SocketHolder getHolder() {
            return holder;
        }
    }

    // Fired when a HandlerThread is created and launched
    public static class HandlerThreadStartEvent extends HandlerThreadEvent {
        public HandlerThreadStartEvent(HandlerThread thread) {
            super(false, thread);
        }
    }

    public static class TickEvent extends HandlerThreadEvent {
        public TickEvent(HandlerThread thread) {
            super(false, thread);
        }
    }

    // Note that in spite of an instance of this event contains
    // a reference to HandlerThread, it is not fired by a HandlerThread
    // (unless a plugin enqueued a HandlerThread task which fires this event
    // for some reason).
    // By default, it is fired by the main thread which accepts new Sockets.
    public static class NewConnectionEvent extends HandlerThreadEvent {
        public NewConnectionEvent(HandlerThread thread, SocketHolder holder) {
            super(false, thread, holder);
        }
    }

    // Proxy (or Origin Server if the Client is authenticated) <- Client
    public static class ServerboundDataReceiveEvent extends HandlerThreadEvent {
        private final byte[] packet;

        public ServerboundDataReceiveEvent(
                HandlerThread thread, SocketHolder holder, byte[] packet
        ) {
            super(true, thread, holder);

            this.packet = packet;
        }

        public byte[] getPacket() {
            byte[] packet = new byte[this.packet.length];
            System.arraycopy(this.packet, 0, packet, 0, packet.length);

            return packet;
        }
    }

    // Origin Server -> Client
    public static class ClientboundDataReceiveEvent extends ServerboundDataReceiveEvent {
        public ClientboundDataReceiveEvent(
                HandlerThread thread, SocketHolder holder, byte[] packet
        ) {
            super(thread, holder, packet);
        }
    }

    // The event signals you can now use holder.getUsername() method
    public static class UserAuthenticationEvent extends HandlerThreadEvent {
        public UserAuthenticationEvent(HandlerThread thread, SocketHolder holder) {
            super(false, thread, holder);
        }
    }

    public static class GameServerSetEvent extends HandlerThreadEvent {
        protected final GameServer gameServer;

        public GameServerSetEvent(
                HandlerThread thread, SocketHolder holder, GameServer gameServer
        ) {
            super(false, thread, holder);

            this.gameServer = gameServer;
        }

        public GameServer getGameServer() {
            return gameServer;
        }
    }

    public static class DisconnectEvent extends HandlerThreadEvent {
        private final String reason;
        private final Throwable throwable;

        public DisconnectEvent(
                HandlerThread thread, SocketHolder holder, String reason, Throwable t
        ) {
            super(false, thread, holder);

            this.reason = reason;
            throwable = t;
        }

        public String getReason() {
            return reason;
        }

        public Throwable getThrowable() {
            return throwable;
        }
    }

    public static class TaskContainer {
        private final Runnable task;
        private final Thread thread;
        private final StackTraceElement[] stacktrace;
        private final boolean subscribed;
        private final long timestamp;
        private volatile boolean finished;

        public TaskContainer(
                Runnable task,
                Thread thread,
                StackTraceElement[] stacktrace,
                boolean subscribed
        ) {
            this.task = task;
            this.thread = thread;
            this.stacktrace = stacktrace;
            this.subscribed = subscribed;
            timestamp = System.currentTimeMillis();
        }

        public Runnable getTask() {
            return task;
        }

        public Thread getThread() {
            return thread;
        }

        public void printStackTrace(boolean warn) {
            for (int i = 1; i < stacktrace.length; i++) {
                StackTraceElement element = stacktrace[i];

                Log.l((warn ? Log.LOG_LEVEL_WARN : Log.LOG_LEVEL_SEVERE),
                        "        at " + element.toString());
            }
        }

        public boolean isSubscribed() {
            return subscribed;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isFinished() {
            return finished;
        }
    }

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

    private final Queue<TaskContainer> taskList = new ArrayDeque<>();
    private final List<SocketHolder> clientList = new ArrayList<>();
    private final List<String> keysToRemove = new ArrayList<>();
    private final Map<String, Pair<GameServer, Long>> exceptionMap = new HashMap<>();
    private final int index;

    public HandlerThread(int index) {
        setName("handler (i=" + index + ")");
        setDaemon(true);

        this.index = index;
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
        SocketHolder holder;
        clientList.add((holder = new SocketHolder(this, socket)));
        EventManager.getInstance().fireEvent(new NewConnectionEvent(this, holder));

        return true;
    }

    public void addTask(Runnable task) {
        addTask(task, false);
    }

    public Object addTask(Runnable task, boolean subscribe) {
        Thread currentThread = Thread.currentThread();
        StackTraceElement[] stacktrace = currentThread.getStackTrace();

        TaskContainer container = new TaskContainer(
                task, currentThread, stacktrace, subscribe);
        synchronized (this) {
            taskList.offer(container);
        }

        return (subscribe ? container : null);
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private synchronized void tick() {
        if (ClassyCord.getInstance().shouldFireTickEvent()) {
            EventManager.getInstance().fireEvent(new TickEvent(this));
        }
        int taskListSize = taskList.size();
        for (int i = 0; i < taskListSize; i++) {
            TaskContainer container = taskList.poll();
            //noinspection DataFlowIssue
            Runnable task = container.task;
            try {
                task.run();
            } catch (Throwable t) {
                Log.s("Failed to complete a task:", t);
                Log.s("Details of the thread which " +
                        "enqueued the task: " + container.thread.toString());
                Log.s("Stacktrace snapshot of " +
                        "the thread at the moment the task was enqueued:");
                container.printStackTrace(false);
                Log.s("Date added: " + (new Date(container.timestamp)));
                Log.s("The proxy will be terminated");

                System.exit(-1);
            }
            if (container.subscribed) {
                synchronized (container) {
                    container.finished = true;
                    container.notifyAll();
                }
            }
        }

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
            } catch (Exception | SilentIOException e) {
                close(holder, e);
            }
        }
    }

    private void handleDataFromClient(
            SocketHolder holder, byte[] packet
    ) throws IOException, SilentIOException {
        ServerboundDataReceiveEvent event =
                new ServerboundDataReceiveEvent(this, holder, packet);
        EventManager.getInstance().fireEvent(event);
        if (event.isCancelled()) return;

        SocketHolder.State state = holder.getState();
        if (state != SocketHolder.State.WAITING_FOR_PLAYER_IDENTIFICATION) {
            AnalyzingStream analyzingStream = holder.getAnalyzingStream();
            if (!analyzingStream.isSuppressing()) {
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
        holder.setClientSupportsCPE(supportsCPE);

        EventManager.getInstance().fireEvent(new UserAuthenticationEvent(this, holder));

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
        ClientboundDataReceiveEvent event =
                new ClientboundDataReceiveEvent(this, holder, packet);
        EventManager.getInstance().fireEvent(event);
        if (event.isCancelled()) return;

        SocketHolder.State state = holder.getState();
        if (holder.isConnectingForTheFirstTime() || state == SocketHolder.State.CONNECTED) {
            OutputStream clientOutputStream = holder.getOutputStream();
            clientOutputStream.write(packet);
            clientOutputStream.flush();
        }
        AnalyzingStream analyzingStream = holder.getAnalyzingStream();
        analyzingStream.setServerMode(true);
        analyzingStream.write(packet);
        analyzingStream.setServerMode(false);
        if (analyzingStream.isRecording()) {
            byte[] cpeHandshake = analyzingStream.stopRecording();
            holder.setClientCPEHandshake(cpeHandshake);
        }

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
                        Boolean isCPEConnection = holder.isCPEConnection();
                        if (isCPEConnection != null && !isCPEConnection) {
                            byeBye(holder);
                        }
                        holder.setCPEConnection(true);

                        break;
                    }
                    case Utils.SIDE_IDENTIFICATION_PACKET: {
                        holder.setState(SocketHolder.State.CONNECTED);
                        Boolean isCPEConnection = holder.isCPEConnection();
                        if (isCPEConnection != null && isCPEConnection) {
                            byeBye(holder);
                        }
                        holder.setCPEConnection(false);

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
                        byeBye(holder);
                    }
                    byte[] clientCPEHandshake = holder.getClientCPEHandshake();
                    OutputStream serverOutputStream = holder.getServerOutputStream();
                    serverOutputStream.write(clientCPEHandshake);
                    analyzingStream.finishSuppressing();
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

    private void byeBye(SocketHolder holder) throws SilentIOException {
        exceptionMap.put(holder.getUsername(),
                Pair.of(holder.getGameServer(), System.currentTimeMillis()));

        throw new SilentIOException("Press \"Reconnect\" button");
    }

    @Override
    @SuppressWarnings({"InfiniteLoopStatement", "BusyWait", "finally"})
    public void run() {
        synchronized (this) {
            EventManager.getInstance().fireEvent(new HandlerThreadStartEvent(this));
        }

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
            String disconnectMessage = holder.getAnalyzingStream().findDisconnectMessage();
            if (disconnectMessage != null) {
                reason = disconnectMessage;
            } else {
                reason = "A disconnect or timeout occurred in your connection";
            }
        }
        // there is a chance it will mess up with another packet though
        Utils.sendDisconnect(holder, reason);

        Utils.close(holder.getSocket());
        Utils.close(holder.getServerSocket());
        synchronized (this) {
            clientList.remove(holder);

            EventManager.getInstance().fireEvent(
                    new DisconnectEvent(this, holder, reason, t));
        }

        Log.i(getAddressAndUsername(holder) + " disconnected");
    }

    List<SocketHolder> getClientList() {
        return clientList;
    }

    public int getIndex() {
        return index;
    }
}
