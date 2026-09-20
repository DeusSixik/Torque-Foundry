package dev.sdm.torque_foundry.api.uitls;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import java.util.function.Supplier;

public class FastutilLruCache<K, V> {
    private final int maxSize;
    private final Object2ObjectLinkedOpenHashMap<K, V> map;

    public FastutilLruCache(int maxSize) {
        this.maxSize = maxSize;
        this.map = new Object2ObjectLinkedOpenHashMap<>(maxSize + 1);
    }

    public V getOrCreate(K key, Supplier<? extends V> supplier) {
        V v = map.get(key);
        if (v == null) {
            v = supplier.get();
            map.put(key, v);
            return v;
        }
        return v;
    }

    public V get(K key) {
        if (!map.containsKey(key)) {
            return null;
        }
        V val = map.get(key);
        map.getAndMoveToLast(key);
        return val;
    }

    public void put(K key, V value) {
        map.putAndMoveToLast(key, value);
        if (map.size() > maxSize) {
            map.removeFirst();
        }
    }

    public int size() {
        return map.size();
    }
}
