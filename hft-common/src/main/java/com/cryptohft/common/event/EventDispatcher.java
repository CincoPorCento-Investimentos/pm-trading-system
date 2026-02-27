package com.cryptohft.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe event dispatcher that notifies registered listeners.
 * Isolates listener failures so one failing listener does not prevent others from being notified.
 *
 * @param <T> the event type
 */
public class EventDispatcher<T> {

    private final Logger log;
    private final String name;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    public EventDispatcher(String name) {
        this.name = name;
        this.log = LoggerFactory.getLogger(EventDispatcher.class.getName() + "." + name);
    }

    /**
     * Register a listener to receive events.
     */
    public void addListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    /**
     * Remove a previously registered listener.
     */
    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    /**
     * Dispatch an event to all registered listeners.
     * Each listener is called in a try/catch so a failure in one listener
     * does not prevent other listeners from being notified.
     */
    public void dispatch(T event) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in {} listener", name, e);
            }
        }
    }

    /**
     * Return the number of registered listeners.
     */
    public int listenerCount() {
        return listeners.size();
    }
}
