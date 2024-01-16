package ru.deewend.classycord;

import java.util.*;

@SuppressWarnings("unused")
public class EventManager {
    private static final EventManager INSTANCE = new EventManager();

    private final Map<Class<? extends Event>, List<EventHandler<?>>> eventHandlerMap;

    private EventManager() {
        this.eventHandlerMap = new HashMap<>();
    }

    public static EventManager getInstance() {
        return INSTANCE;
    }

    public synchronized <T extends Event> void registerEventHandler(
            Class<T> eventClass, EventHandler<T> eventHandler
    ) {
        Objects.requireNonNull(eventClass);
        Objects.requireNonNull(eventHandler);

        List<EventHandler<? extends Event>> eventHandlerList =
                eventHandlerMap.computeIfAbsent(eventClass, (key) -> new ArrayList<>());
        eventHandlerList.add(eventHandler);
    }

    public synchronized <T extends Event> void fireEvent(T event) {
        Objects.requireNonNull(event);

        if (ClassyCord.DEBUG) {
            if ("true".equalsIgnoreCase(System.getProperty("ccDontDebugTickEvent"))) {
                if (event.getClass() == HandlerThreadTickEvent.class) return;
            }
            Log.i("Firing event " + event.getClass().getName());
        }

        Class<? extends Event> eventClass = event.getClass();
        if (!eventHandlerMap.containsKey(eventClass)) return;

        List<EventHandler<? extends Event>> handlerList =
                eventHandlerMap.get(eventClass);
        for (EventHandler<? extends Event> eventHandler : handlerList) {
            //noinspection unchecked
            ((EventHandler<T>) eventHandler).handleEvent(event);
        }
    }
}
