package dev.sdm.torque_foundry.api.events.bus;

public class EventPtr<T> {

    protected final FastEventBus.EventType<T> eventType;

    public EventPtr(FastEventBus.EventType<T> eventType) {
        this.eventType = eventType;
    }

    public EventSubscription subscribe(FastEventBus.EventListener<T> listener) {
        return FastEventBus.DEFAULT_BUS.subscribe(eventType, listener);
    }

    public void unsubscribe(FastEventBus.EventListener<T> listener) {
        FastEventBus.DEFAULT_BUS.unsubscribe(eventType, listener);
    }
}
