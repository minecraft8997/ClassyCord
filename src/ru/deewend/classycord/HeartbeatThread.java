package ru.deewend.classycord;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class HeartbeatThread extends Thread {
    public static final long INTERVAL_MILLIS = 30_000L;

    private boolean recommendedToUseHttpsInstead;

    public HeartbeatThread() {
        setName("heartbeat");
        setDaemon(true);
    }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        while (true) {
            String requestUrl = ClassyCord.getInstance().getHeartbeatUrl();
            requestUrl += "?name=" + ClassyCord.getInstance().getName();
            requestUrl += "&port=" + ClassyCord.getInstance().getPort();
            requestUrl += "&users=" + Utils.getOnlinePlayerCount();
            requestUrl += "&max=" + ClassyCord.getInstance().getMaxPlayerCount();
            requestUrl += "&salt=" + ClassyCord.getInstance().getSalt();
            requestUrl += "&public=" + ClassyCord.getInstance().isPublicServer();

            try {
                HttpURLConnection connection =
                        (HttpURLConnection) (new URL(requestUrl)).openConnection();
                if (!(connection instanceof HttpsURLConnection) && !recommendedToUseHttpsInstead) {
                    Log.w("Please use https:// instead for a heartbeatUrl link, " +
                            "it's a more secure option for transferring server " +
                            "salt, which should be kept in a secret");

                    recommendedToUseHttpsInstead = true;
                }
                connection.setRequestMethod("GET");
                connection.setDoInput(true);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))
                ) {
                    boolean firstLine = true;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine && line.startsWith("http")) {
                            break;
                        }
                        firstLine = false;

                        Log.w(line);
                    }
                }

                Thread.sleep(INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.s("Received a request to interrupt HeartbeatThread", e);

                break;
            } catch (IOException e) {
                Log.s("An IOException occurred in HeartbeatThread", e);
            }
        }

        Log.s("HeartbeatThread loop has finished, the proxy will be terminated");
        System.exit(-1);
    }
}
