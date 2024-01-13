package ru.deewend.classycord;

import java.io.*;
import java.util.*;

public class OrderedProperties {
    private final Properties backendProperties = new Properties();
    private final List<Pair<String, String>> orderedProperties = new LinkedList<>();

    public void setProperty(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        if (!backendProperties.containsKey(key)) {
            put(key, value);

            return;
        }
        String previousValue = (String) backendProperties.get(key);
        orderedProperties.remove(Pair.of(key, previousValue));

        put(key, value);
    }

    public String getProperty(String key) {
        Objects.requireNonNull(key);

        return (String) backendProperties.get(key);
    }

    private Pair<String, String> findEntry(String key) {
        for (Pair<String, String> entry : orderedProperties) {
            if (entry.getFirst().equals(key)) return entry;
        }

        return null;
    }

    public void load(InputStream stream) throws IOException {
        Objects.requireNonNull(stream);

        backendProperties.load(stream);

        Enumeration<Object> keys = backendProperties.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            String value = (String) backendProperties.get(key);

            Pair<String, String> entry = findEntry(key);
            if (entry != null) {
                String currentValue = entry.getSecond();
                if (!value.equals(currentValue)) entry.setSecond(value);
            } else {
                orderedProperties.add(Pair.of(key, value));
            }
        }
    }

    public void store(OutputStream stream, String comment) throws IOException {
        Objects.requireNonNull(stream);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream))) {
            if (comment != null) {
                writer.write("#" + comment);
                writer.newLine();
                writer.write("#" + (new Date()));
                writer.newLine();
            }
            for (Pair<String, String> entry : orderedProperties) {
                writer.write(entry.getFirst() + "=" + entry.getSecond());
                writer.newLine();
            }
        }
    }

    private void put(String key, String value) {
        backendProperties.put(key, value);
        orderedProperties.add(Pair.of(key, value));
    }
}
