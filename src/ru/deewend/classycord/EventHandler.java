package ru.deewend.classycord;

public abstract class EventHandler<T extends Event> {
    public abstract void handleEvent(T event);
}
