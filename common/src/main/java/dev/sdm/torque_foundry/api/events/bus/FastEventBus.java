package dev.sdm.torque_foundry.api.events.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Сверхбыстрый Lock-Free EventBus.
 * Не использует рефлексию, не использует хэш-мапы, не создает мусор при диспетчеризации.
 */
public final class FastEventBus {

    public static final FastEventBus DEFAULT_BUS = new FastEventBus();
    private static final Logger LOGGER = LoggerFactory.getLogger(FastEventBus.class);

    /**
     * Глобальный генератор ID для типов событий.
     * Каждому новому типу события выдается свой уникальный индекс (0, 1, 2...).
     */
    private static final AtomicInteger EVENT_TYPE_ID_GENERATOR = new AtomicInteger(0);

    /**
     * Класс-маркер для события. Заменяет {@code Class<?>} и служит ключом.
     * @param <T> Тип данных события
     */
    public static class EventType<T> {
        public final int id = EVENT_TYPE_ID_GENERATOR.getAndIncrement();
        public final String name;

        public EventType(String name) {
            this.name = name;
        }
    }

    /**
     * Функциональный интерфейс для слушателей (заменяет @Subscribe)
     */
    @FunctionalInterface
    public interface EventListener<T> {
        void onEvent(T event);
    }

    private volatile AtomicReferenceArray<EventListener<?>[]> subscribers = new AtomicReferenceArray<>(128);

    /**
     * Подписка на событие (Copy-On-Write)
     * Использует synchronized, так как подписки происходят редко (обычно при загрузке мира/мода).
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> EventSubscription subscribe(EventType<T> type, EventListener<T> listener) {
        ensureCapacity(type.id);

        final EventListener<T>[] currentListeners = (EventListener<T>[]) subscribers.get(type.id);

        if (currentListeners == null) {
            final EventListener<T>[] newListeners = new EventListener[]{listener};
            subscribers.set(type.id, newListeners);
        } else {
            final EventListener<T>[] newListeners = Arrays.copyOf(currentListeners, currentListeners.length + 1);
            newListeners[newListeners.length - 1] = listener;

            subscribers.set(type.id, newListeners);
        }

        return new EventSubscription() {
            private boolean closed;

            @Override
            public synchronized void unsubscribe() {
                if (closed) {
                    return;
                }

                closed = true;
                FastEventBus.this.unsubscribe(type, listener);
            }
        };
    }

    /**
     * Отписка от события (Copy-On-Write)
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> void unsubscribe(EventType<T> type, EventListener<T> listener) {
        if (type.id >= subscribers.length()) return;

        final EventListener<T>[] currentListeners = (EventListener<T>[]) subscribers.get(type.id);
        if (currentListeners == null) return;

        int index = -1;
        for (int i = 0; i < currentListeners.length; i++) {
            if (currentListeners[i] == listener) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            if (currentListeners.length == 1) {
                subscribers.set(type.id, null);
            } else {
                EventListener<T>[] newListeners = new EventListener[currentListeners.length - 1];
                System.arraycopy(currentListeners, 0, newListeners, 0, index);
                System.arraycopy(currentListeners, index + 1, newListeners, index, currentListeners.length - index - 1);

                subscribers.set(type.id, newListeners);
            }
        }
    }

    /**
     * Вызов события (LockFree)
     */
    @SuppressWarnings("unchecked")
    public <T> void fire(EventType<T> type, T event) {
        final AtomicReferenceArray<EventListener<?>[]> subscribers = this.subscribers;
        if (type.id >= subscribers.length()) return;
        final EventListener<T>[] listeners = (EventListener<T>[]) subscribers.get(type.id);

        if (listeners != null) {
            for (int i = 0; i < listeners.length; i++) {
                try {
                    listeners[i].onEvent(event);
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to handle event {}", type.name, exception);
                }
            }
        }
    }

    /**
     * Безопасное увеличение размера массива типов событий,
     * если кто-то зарегистрирует больше 128 типов.
     */
    private void ensureCapacity(int neededId) {
        if (neededId >= subscribers.length()) {
            int newSize = Math.max(subscribers.length() * 2, neededId + 1);
            AtomicReferenceArray<EventListener<?>[]> newSubscribers = new AtomicReferenceArray<>(newSize);

            for (int i = 0; i < subscribers.length(); i++) {
                newSubscribers.set(i, subscribers.get(i));
            }

            this.subscribers = newSubscribers;
        }
    }
}
