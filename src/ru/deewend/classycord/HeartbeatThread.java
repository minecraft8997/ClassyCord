package ru.deewend.classycord;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class HeartbeatThread extends Thread {
    private boolean recommendedToUseHttpsInstead;

    @Override
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
                    Log.w("Please use https:// " +
                            "instead for a heartbeatUrl link, it's a much better option");

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

                Thread.sleep(30000L);
            } catch (Exception e) {
                Log.w("An Exception occurred in HeartbeatThread", e);
            }
        }
    }
}
