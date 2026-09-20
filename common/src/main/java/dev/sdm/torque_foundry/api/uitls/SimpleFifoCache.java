package dev.sdm.torque_foundry.api.uitls;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

public class SimpleFifoCache<K, V> extends Object2ObjectLinkedOpenHashMap<K, V> {
    private final int maxSize;

    public SimpleFifoCache(int maxSize) {
        super(maxSize + 1);
        this.maxSize = maxSize;
    }

    @Override
    public V put(K key, V value) {
        V old = super.put(key, value);
        if (size() > maxSize) {
            removeFirst();
        }
        return old;
    }
}
