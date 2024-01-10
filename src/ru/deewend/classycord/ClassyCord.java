package ru.deewend.classycord;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class ClassyCord {
    private static ClassyCord theClassyCord;

    private final int port;
    private final String salt;
    private final boolean onlineMode;
    private final String heartbeatUrl;
    private final String logFormat;
    private final boolean saveLogsOnDisk;
    private final String logFileNameFormat;
    private final GameServer firstServer;
    private final Map<String, GameServer> gameServerMap = new HashMap<>();
    @SuppressWarnings("FieldCanBeLocal")
    private HandlerThread handlerThread;
    private Socket beingRegistered;

    public ClassyCord(
            int port,
            String salt,
            boolean onlineMode,
            String heartbeatUrl,
            String logFormat,
            boolean saveLogsOnDisk,
            String logFileNameFormat,
            GameServer firstServer,
            GameServer... gameServers
    ) {
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

        this.firstServer = firstServer;
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
    }

    public static void main(String[] args) throws IOException {
        Properties props = new Properties();
        props.setProperty("ready", "false");
        props.setProperty("port", "25565");
        props.setProperty("salt", Utils.randomSalt());
        props.setProperty("onlineMode", "true");
        props.setProperty("heartbeatUrl", "https://classicube.net/server/heartbeat");
        props.setProperty("logFormat", "[HH:mm:ss dd.MM.yyyy] ");
        props.setProperty("saveLogsOnDisk", "true");
        props.setProperty("logFileNameFormat", "dd-MM-yyyy-logs.txt");
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
        String logFormat = props.getProperty("logFormat");
        boolean saveLogsOnDisk = Boolean
                .parseBoolean(props.getProperty("saveLogsOnDisk"));
        String logFileNameFormat = props.getProperty("logFileNameFormat");

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
                port,
                salt,
                onlineMode,
                heartbeatUrl,
                logFormat,
                saveLogsOnDisk,
                logFileNameFormat,
                firstServer,
                gameServers
        )).start();
    }

    public static ClassyCord getInstance() {
        return theClassyCord;
    }

    @SuppressWarnings("InfiniteLoopStatement")
    public void start() throws IOException {
        try (ServerSocket listeningSocket = new ServerSocket(port)) {
            handlerThread = new HandlerThread();
            handlerThread.start();
            Log.i("Listening on port " + port + "...");

            while (true) {
                beingRegistered = listeningSocket.accept();
                beingRegistered.setTcpNoDelay(true);
                Log.i(Utils.getAddress(beingRegistered) + " connected");

                boolean successfullyAdded;
                try {
                    successfullyAdded = handlerThread.addClient(beingRegistered);
                } catch (IOException e) {
                    reportErrorAndClose(true);

                    continue;
                }
                if (!successfullyAdded) {
                    reportErrorAndClose(false);
                }
            }
        }
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

    public GameServer getFirstServer() {
        return firstServer;
    }
}
