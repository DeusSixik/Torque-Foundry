package dev.sdm.torque_foundry.api.events.bus;


import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;

public final class EventScope implements AutoCloseable {

    private final List<EventSubscription> subscriptions = new ObjectArrayList<>();
    private final boolean enabled;
    private boolean closed;

    public EventScope() {
        this(true);
    }

    private EventScope(boolean enabled) {
        this.enabled = enabled;
    }

    public static EventScope noop() {
        return new EventScope(false);
    }

    public synchronized <T> EventSubscription listen(EventPtr<T> event, FastEventBus.EventListener<T> listener) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(listener, "listener");

        if (!enabled || closed) {
            return EventSubscription.NOOP;
        }

        EventSubscription subscription = event.subscribe(listener);
        subscriptions.add(subscription);
        return subscription;
    }

    public synchronized void clear() {
        for (int i = subscriptions.size() - 1; i >= 0; i--) {
            subscriptions.get(i).close();
        }
        subscriptions.clear();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        clear();
    }
}
