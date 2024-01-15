package ru.deewend.classycord;

import java.io.*;
import java.util.*;

public class OrderedProperties {
    private final List<Pair<String, String>> orderedProperties = new LinkedList<>();

    public void setProperty(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        Pair<String, String> entry = findEntry(key);
        if (entry != null) {
            String oldValue = entry.getSecond();
            orderedProperties.remove(Pair.of(key, oldValue));
        }

        orderedProperties.add(Pair.of(key, value));
    }

    public String getProperty(String key) {
        Objects.requireNonNull(key);

        Pair<String, String> entry = findEntry(key);
        if (entry == null) return null;

        return entry.getSecond();
    }

    private Pair<String, String> findEntry(String key) {
        for (Pair<String, String> entry : orderedProperties) {
            if (entry.getFirst().equals(key)) return entry;
        }

        return null;
    }

    public void load(InputStream stream) throws IOException {
        Objects.requireNonNull(stream);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;

            while ((line = reader.readLine()) != null) {
                int eqIdx = line.indexOf('=');
                if (line.startsWith("#") || eqIdx == -1) continue;

                String key = line.substring(0, eqIdx);
                String value = line.substring(eqIdx + 1);

                setProperty(key, value);
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
}
