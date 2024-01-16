package ru.deewend.classycord;

@SuppressWarnings("RedundantThrows")
public interface Plugin {
    default void load() throws Exception {}
    default void enable() throws Exception {}
}
