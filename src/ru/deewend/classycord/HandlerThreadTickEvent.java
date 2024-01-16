package ru.deewend.classycord;

public class HandlerThreadTickEvent extends Event {
    private final HandlerThread thread;

    public HandlerThreadTickEvent(HandlerThread thread) {
        super(false);

        this.thread = thread;
    }

    public HandlerThread getThread() {
        return thread;
    }
}
