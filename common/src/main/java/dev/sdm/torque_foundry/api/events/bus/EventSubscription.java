package dev.sdm.torque_foundry.api.events.bus;

@FunctionalInterface
public interface EventSubscription extends AutoCloseable {

    EventSubscription NOOP = () -> {
    };

    void unsubscribe();

    @Override
    default void close() {
        unsubscribe();
    }
}
