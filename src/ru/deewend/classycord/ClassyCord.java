package ru.deewend.classycord;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class ClassyCord {
    public static final String VERSION = "0.9";
    public static final boolean DEBUG = Boolean
            .parseBoolean(System.getProperty("ccdebug", "false"));

    private static ClassyCord theClassyCord;

    private final String name;
    private final boolean publicServer;
    private final int port;
    private final String salt;
    private final boolean onlineMode;
    private final String heartbeatUrl;
    private final String logFormat;
    private final boolean saveLogsOnDisk;
    private final String logFileNameFormat;
    private final HandlerThread[] handlerThreads;
    private final int maxConnectionsCountPerHandlerThread;
    private final int tickRateOfHandlerThread;
    private final long readTimeoutMillis;
    private final long exceptionMapStorageTimeoutMillis;
    private final int minTicksToWaitBeforeReconnecting;
    private final GameServer firstServer;
    private final int maxPlayerCount;
    private final Map<String, GameServer> gameServerMap = new HashMap<>();
    private Socket beingRegistered;

    public ClassyCord(
            String name,
            boolean publicServer,
            int port,
            String salt,
            boolean onlineMode,
            String heartbeatUrl,
            String logFormat,
            boolean saveLogsOnDisk,
            String logFileNameFormat,
            int maxHandlerThreadCount,
            int maxConnectionsCountPerHandlerThread,
            int tickRateOfHandlerThread,
            long readTimeoutMillis,
            long exceptionMapStorageTimeoutMillis,
            int minTicksToWaitBeforeReconnecting,
            GameServer firstServer,
            GameServer... gameServers
    ) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(salt);
        Objects.requireNonNull(firstServer);
        Objects.requireNonNull(gameServers);

        if (theClassyCord != null) {
            System.err.println("This doesn't seem the first ClassyCord " +
                    "instance running inside this Java Virtual Machine. " +
                    "We have to overwrite the static instance field, " +
                    "dangerous experiments...");
        }
        theClassyCord = this;

        this.name = name;
        this.publicServer = publicServer;
        this.port = port;
        this.salt = salt;
        this.onlineMode = onlineMode;
        this.heartbeatUrl = heartbeatUrl;
        if (logFormat == null) {
            logFormat = "[HH:mm:ss dd.MM.yyyy] ";
        }
        if (logFileNameFormat == null) {
            logFileNameFormat = "dd-MM-yyyy-logs.txt";
        }
        this.logFormat = logFormat;
        this.saveLogsOnDisk = saveLogsOnDisk;
        this.logFileNameFormat = logFileNameFormat;
        this.handlerThreads = new HandlerThread[maxHandlerThreadCount];
        this.maxConnectionsCountPerHandlerThread = maxConnectionsCountPerHandlerThread;
        this.tickRateOfHandlerThread = tickRateOfHandlerThread;
        this.readTimeoutMillis = readTimeoutMillis;
        this.exceptionMapStorageTimeoutMillis = exceptionMapStorageTimeoutMillis;
        this.minTicksToWaitBeforeReconnecting = minTicksToWaitBeforeReconnecting;

        this.firstServer = firstServer;
        this.maxPlayerCount = maxHandlerThreadCount * maxConnectionsCountPerHandlerThread;
        for (GameServer gameServer : gameServers) {
            Objects.requireNonNull(gameServer);

            if (gameServerMap.containsKey(gameServer.getName())) {
                Log.w(Log.f("Found a duplicate: %s. It won't " +
                        "be registered%s", gameServer, System.lineSeparator()));

                continue;
            }
            gameServerMap.put(gameServer.getName(), gameServer);
        }
        if (gameServerMap.get(firstServer.getName()) != firstServer) {
            Log.w("Could not find the first server in the list of all nodes");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> Log.i("Goodbye!")));
    }

    public static void main(String[] args) throws IOException {
        printVersionInfo(false);
        System.out.println("Make sure to check for updates sometimes: " +
                "https://github.com/minecraft8997/ClassyCord");
        System.out.println();

        Properties props = new Properties();
        props.setProperty("ready", "false");
        props.setProperty("name", "A ClassyCord instance");
        props.setProperty("public", "true");
        props.setProperty("port", "25565");
        props.setProperty("salt", Utils.randomSalt());
        props.setProperty("onlineMode", "true");
        props.setProperty("heartbeatUrl", "https://classicube.net/server/heartbeat");
        props.setProperty("logFormat", "[HH:mm:ss dd.MM.yyyy] ");
        props.setProperty("saveLogsOnDisk", "true");
        props.setProperty("logFileNameFormat", "dd-MM-yyyy-logs.txt");
        props.setProperty("maxHandlerThreadCount", "2");
        props.setProperty("maxConnectionsCountPerHandlerThread", "100");
        props.setProperty("tickRateOfHandlerThread", "25");
        props.setProperty("readTimeoutMillis", "420000");
        props.setProperty("exceptionMapStorageTimeoutMillis", "900000");
        props.setProperty("minTicksToWaitBeforeReconnecting", "2");
        props.setProperty("serverCount", "1");
        props.setProperty("server1Name", "Freebuild");
        props.setProperty("server1Address", "127.0.0.1");
        props.setProperty("server1Port", "25566");
        props.setProperty("firstServer", "Freebuild");

        File propertiesFile = new File("classycord.properties");
        if (propertiesFile.exists()) {
            try (InputStream stream = new FileInputStream(propertiesFile)) {
                props.load(stream);
            }
        }
        try (OutputStream stream = new FileOutputStream(propertiesFile)) {
            props.store(stream, "Don't forget to specify ready=true " +
                    "when you're done with configuring the network");
        }

        boolean ready = Boolean
                .parseBoolean(props.getProperty("ready"));
        if (!ready) {
            System.err.println("Please configure your " +
                    "ClassyCord instance. When you're done, set ready=true");

            return;
        }
        String serverName = props.getProperty("name");
        boolean publicServer = Boolean.parseBoolean(props.getProperty("public"));
        String logFormat = props.getProperty("logFormat");
        boolean saveLogsOnDisk = Boolean.parseBoolean(props.getProperty("saveLogsOnDisk"));
        String logFileNameFormat = props.getProperty("logFileNameFormat");
        int maxHandlerThreadCount = Integer.parseInt(
                props.getProperty("maxHandlerThreadCount"));
        if (maxHandlerThreadCount < 1) {
            System.err.println("maxHandlerThreadCount is too low");

            System.exit(-1);
        }
        int maxConnectionsCountPerHandlerThread = Integer.parseInt(
                props.getProperty("maxConnectionsCountPerHandlerThread"));
        int tickRateOfHandlerThread = Integer.parseInt(
                props.getProperty("tickRateOfHandlerThread"));
        if (tickRateOfHandlerThread < 1) {
            System.err.println("tickRateOfHandlerThread is too low");

            System.exit(-1);
        }
        if (1000 % tickRateOfHandlerThread != 0) {
            System.err.println("It's better (but not " +
                    "obligatory) when 1000 % tickRateOfHandlerThread == 0");
        }
        long readTimeoutMillis = Long.parseLong(
                props.getProperty("readTimeoutMillis"));
        long exceptionMapStorageTimeoutMillis = Long.parseLong(
                props.getProperty("exceptionMapStorageTimeoutMillis"));
        int minTicksToWaitBeforeReconnecting = Integer.parseInt(
                props.getProperty("minTicksToWaitBeforeReconnecting"));

        int port = Integer.parseInt(props.getProperty("port"));
        String salt = props.getProperty("salt");
        boolean onlineMode = Boolean.parseBoolean(props.getProperty("onlineMode"));
        String heartbeatUrl = props.getProperty("heartbeatUrl");
        int serverCount = Integer.parseInt(props.getProperty("serverCount"));
        if (serverCount < 1) {
            System.err.println("serverCount is too low");

            System.exit(-1);
        }
        String firstServerName = props.getProperty("firstServer");

        GameServer firstServer = null;
        GameServer[] gameServers = new GameServer[serverCount];
        for (int i = 0; i < gameServers.length; i++) {
            String prefix = "server" + (i + 1);
            String name = props.getProperty(prefix + "Name");
            String address = props.getProperty(prefix + "Address");
            int serverPort = Integer.parseInt(props.getProperty(prefix + "Port"));

            GameServer gameServer = new GameServer(name, address, serverPort);
            if (name.equals(firstServerName)) firstServer = gameServer;
            gameServers[i] = gameServer;
        }
        if (firstServer == null) {
            System.err.println("Could not " +
                    "find server specified in the \"firstServer\" field");

            System.exit(-1);
        }
        System.out.println("Done " +
                "reading config, collected " + gameServers.length + " server(s)");

        (new ClassyCord(
                serverName,
                publicServer,
                port,
                salt,
                onlineMode,
                heartbeatUrl,
                logFormat,
                saveLogsOnDisk,
                logFileNameFormat,
                maxHandlerThreadCount,
                maxConnectionsCountPerHandlerThread,
                tickRateOfHandlerThread,
                readTimeoutMillis,
                exceptionMapStorageTimeoutMillis,
                minTicksToWaitBeforeReconnecting,
                firstServer,
                gameServers
        )).start();
        // by the way after instantiating ClassyCord we can now use methods of Log class
    }

    public static void printVersionInfo(boolean log) {
        String message = "You are running ClassyCord " + VERSION + " by deewend";
        if (log) Log.i(message);
        else System.out.println(message);
    }

    public static ClassyCord getInstance() {
        return theClassyCord;
    }

    @SuppressWarnings("InfiniteLoopStatement")
    public void start() throws IOException {
        (new ConsoleThread()).start();
        if (onlineMode) (new HeartbeatThread()).start();

        try (ServerSocket listeningSocket = new ServerSocket(port)) {
            startHandlerThreadAt(0);
            Log.i("Listening on port " + port + "...");

            while (true) {
                beingRegistered = listeningSocket.accept();
                beingRegistered.setTcpNoDelay(true);
                Log.i(Utils.getAddress(beingRegistered) + " connected");

                int i = 0;
                boolean successfullyAdded = false;
                while (!successfullyAdded) {
                    try {
                        successfullyAdded = handlerThreads[i++].addClient(beingRegistered);
                    } catch (IOException e) {
                        reportErrorAndClose(true);

                        break;
                    }
                    if (i >= handlerThreads.length) break;
                    if (handlerThreads[i] == null) startHandlerThreadAt(i);
                }

                if (!successfullyAdded) {
                    reportErrorAndClose(false);
                }
            }
        }
    }

    private void startHandlerThreadAt(int i) {
        handlerThreads[i] = new HandlerThread();
        handlerThreads[i].start();
    }

    public GameServer getGameServer(String name) {
        return gameServerMap.get(name);
    }

    private void reportErrorAndClose(boolean ioError) {
        Utils.sendDisconnect(beingRegistered, ioError ?
                "An I/O error occurred" :
                "The network is overloaded, try again in a minute");
        Utils.close(beingRegistered);

        Log.i(Utils.getAddress(beingRegistered) + " disconnected");
    }

    public String getName() {
        return name;
    }

    public boolean isPublicServer() {
        return publicServer;
    }

    public int getPort() {
        return port;
    }

    public String getSalt() {
        return salt;
    }

    public boolean isOnlineMode() {
        return onlineMode;
    }

    public String getHeartbeatUrl() {
        return heartbeatUrl;
    }

    public String getLogFormat() {
        return logFormat;
    }

    public boolean shouldSaveLogsOnDisk() {
        return saveLogsOnDisk;
    }

    public String getLogFileNameFormat() {
        return logFileNameFormat;
    }

    public int getMaxHandlerThreadCount() {
        return handlerThreads.length;
    }

    public HandlerThread getHandlerThreadAt(int i) {
        return handlerThreads[i];
    }

    public int getMaxConnectionsCountPerHandlerThread() {
        return maxConnectionsCountPerHandlerThread;
    }

    public int getTickRateOfHandlerThread() {
        return tickRateOfHandlerThread;
    }

    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public long getExceptionMapStorageTimeoutMillis() {
        return exceptionMapStorageTimeoutMillis;
    }

    public int getMinTicksToWaitBeforeReconnecting() {
        return minTicksToWaitBeforeReconnecting;
    }

    public GameServer getFirstServer() {
        return firstServer;
    }

    public int getMaxPlayerCount() {
        return maxPlayerCount;
    }
}
