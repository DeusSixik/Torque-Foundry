package dev.sdm.torque_foundry.api.events.bus;

public class EventPtrInvoker<T> extends EventPtr<T> {

    public EventPtrInvoker(FastEventBus.EventType<T> eventType) {
        super(eventType);
    }

    public void invoke(T event) {
        FastEventBus.DEFAULT_BUS.fire(eventType, event);
    }
}
