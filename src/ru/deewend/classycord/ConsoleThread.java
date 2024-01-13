package ru.deewend.classycord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ConsoleThread extends Thread {
    public ConsoleThread() {
        setName("console");
        setDaemon(true);
    }

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        // closing this reader will result in closing System.in, don't do it

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i("Received from stdin: " + line);
                handleCommand(line);
            }
        } catch (IOException e) {
            Log.s("An IOException occurred in ConsoleThread", e);
        }

        Log.s("ConsoleThread loop has finished, it won't be able to accept your input anymore");
        Log.s("Use Ctrl+C to exit");
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public static void handleCommand(String line) {
        if (line.isEmpty()) {
            Log.i("This line is so... empty?");
        } else if (line.equalsIgnoreCase("info")) {
            ClassyCord.printVersionInfo(true);
            Log.i("");
            int playersOnline = 0;
            int maxCount = ClassyCord.getInstance().getMaxHandlerThreadCount();
            for (int i = 0; i < maxCount; i++) {
                HandlerThread thread = ClassyCord.getInstance().getHandlerThreadAt(i);
                if (thread == null) break;

                Log.i("HandlerThread (i=" + i + ")");
                int prevPlayersOnline = playersOnline;
                synchronized (thread) {
                    for (SocketHolder holder : thread.getClientList()) {
                        GameServer gameServer = holder.getGameServer();
                        Log.i(" - " + HandlerThread.getAddressAndUsername(holder) + " " +
                                (gameServer == null ? "pending authentication" :
                                        "at gameServer=" + gameServer.getName()));
                        playersOnline++;
                    }
                }
                if (playersOnline == prevPlayersOnline) {
                    Log.i(" - No one is online");
                }
            }
            Log.i("");
            Log.i("Total online player count: " +
                    playersOnline + "/" + ClassyCord.getInstance().getMaxPlayerCount());
        } else if (line.equalsIgnoreCase("exit")) {
            System.exit(0);
        } else {
            Log.w("Unknown command entered: \"" + line + "\"");
        }
    }
}
