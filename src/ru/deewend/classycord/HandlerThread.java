package ru.deewend.classycord;

import java.io.IOException;
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
        setName("HandlerThread");

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
        for (int i = clientListSize - 1; i >= 0; i--) {
            SocketHolder holder = clientList.get(i);
            long currentTimeMillis = System.currentTimeMillis();
            try {
                if (currentTimeMillis - holder.getLastTestedIfAlive() >= 1500) {
                    holder.getOutputStream().write(Utils.PING_PACKET);
                    holder.getOutputStream().flush();
                    holder.getServerOutputStream().write(Utils.PING_PACKET);
                    holder.getServerOutputStream().flush();

                    holder.setLastTestedIfAlive(currentTimeMillis);
                }

                int availableFromClient, availableFromServer;
                if ((availableFromClient = holder.getInputStream().available()) > 0) {
                    byte[] packet = new byte[availableFromClient];
                    //noinspection ResultOfMethodCallIgnored
                    holder.getInputStream().read(packet);
                    holder.getServerOutputStream().write(packet);
                    holder.getServerOutputStream().flush();
                    holder.setLastReadTimestamp(currentTimeMillis);
                } else {
                    if (Utils.delta(holder.getLastReadTimestamp()) >= READ_TIMEOUT) {
                        close(holder);

                        continue;
                    }
                }

                if ((availableFromServer = holder.getServerInputStream().available()) > 0) {
                    byte[] packet = new byte[availableFromServer];
                    //noinspection ResultOfMethodCallIgnored
                    holder.getServerInputStream().read(packet);
                    holder.getOutputStream().write(packet);
                    holder.getOutputStream().flush();
                    holder.setLastServerReadTimestamp(currentTimeMillis);

                    /*
                    int initialLength = packet.length;
                    boolean needToSendGameTitle = false;

                    int position = analyzeServerPacket(packet);
                    if (position != -1) {
                        initialLength = position;
                        needToSendGameTitle = true;
                    }

                    holder.getOutputStream().write(packet, 0, initialLength);
                    if (needToSendGameTitle) {
                        inject(holder);

                        if (!holder.thisIsMainMenu) {
                            holder.thisIsMainMenu = true;
                            holder.cachedOriginalMainMenuPacket = packet;
                            holder.cachedMainMenuInjectPosition = initialLength;
                        }
                    } else {
                        if (holder.thisIsMainMenu) {
                            holder.thisIsMainMenu = false;
                            holder.cachedOriginalMainMenuPacket = null; // free memory
                        }
                    }
                    if (initialLength < packet.length) {
                        holder.outputStream.write(packet,
                                initialLength, (packet.length - initialLength));
                    }
                    holder.outputStream.flush();
                    holder.lastServerReadTimestamp = currentTimeMillis;

                     */
                } else {
                    if (Utils.delta(holder.getLastServerReadTimestamp()) >= READ_TIMEOUT) {
                        close(holder);
                    }
                }
            } catch (IOException e) {
                close(holder);
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
            System.err.println("An exception/error has occurred, " +
                    "printing the stacktrace...");
            t.printStackTrace();
        } finally {
            System.err.println("HandlerThread has died, terminating the tunnel...");

            System.exit(-1);
        }
    }

    private void close(SocketHolder holder) {
        Utils.close(holder.getSocket());
        Utils.close(holder.getServerSocket());
        synchronized (this) {
            clientList.remove(holder);
        }

        // Utils.reportSomeoneHas("left", holder.socket, null);
    }
}
