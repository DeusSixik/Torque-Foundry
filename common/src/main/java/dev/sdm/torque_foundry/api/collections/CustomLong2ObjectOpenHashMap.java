package dev.sdm.torque_foundry.api.collections;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;

public class CustomLong2ObjectOpenHashMap<V> extends Long2ObjectOpenHashMap<V> {

    @Override
    public ObjectCollection<V> values() {
        return super.values();
    }
}
