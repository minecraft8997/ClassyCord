package ru.deewend.classycord;

import java.util.HashMap;
import java.util.Map;

public class ClassyCord {
    private static ClassyCord theClassyCord;

    private final String logFormat;
    private final boolean saveLogsOnDisk;
    private final String logFileNameFormat;
    private final GameServer firstServer;
    private final Map<String, GameServer> gameServerMap = new HashMap<>();

    public ClassyCord(
            String logFormat,
            boolean saveLogsOnDisk,
            String logFileNameFormat,
            GameServer firstServer,
            GameServer... gameServers
    ) {
        if (theClassyCord != null) {
            System.err.println("This doesn't seem the first ClassyCord " +
                    "instance running inside this Java Virtual Machine. " +
                    "We have to overwrite the static instance field, " +
                    "dangerous experiments...");
        }
        theClassyCord = this;

        if (logFormat == null) {
            logFormat = "[HH:mm:ss dd.MM.yyyy] ";
        }
        this.logFormat = logFormat;
        this.saveLogsOnDisk = saveLogsOnDisk;
        this.logFileNameFormat = logFileNameFormat;

        this.firstServer = firstServer;
        for (GameServer gameServer : gameServers) {
            if (gameServerMap.containsKey(gameServer.getName())) {
                System.err.printf("Found a duplicate: %s. It won't " +
                        "be registered%s", gameServer, System.lineSeparator());

                continue;
            }
            gameServerMap.put(gameServer.getName(), gameServer);
        }
        if (!gameServerMap.containsKey(firstServer.getName())) {
            System.err.println();
        }
    }

    public static void main(String[] args) {
        (new ClassyCord()).start();
    }

    public static ClassyCord getInstance() {
        return theClassyCord;
    }

    public void start() {

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
}
