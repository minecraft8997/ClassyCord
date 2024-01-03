package ru.deewend.classycord;

import java.util.Objects;

public class GameServer {
    private final String name;
    private final String address;
    private final int port;

    public GameServer(String name, String address, int port) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(address);

        this.name = name;
        this.address = address;
        this.port = port;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GameServer that = (GameServer) o;

        return port == that.port &&
                name.equals(that.name) && address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, address, port);
    }

    @Override
    public String toString() {
        return "GameServer{" +
                "name='" + name + '\'' +
                ", address='" + address + '\'' +
                ", port=" + port +
                '}';
    }
}
