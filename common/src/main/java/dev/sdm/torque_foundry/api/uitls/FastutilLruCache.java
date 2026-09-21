package dev.sdm.torque_foundry.api.uitls;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import java.util.function.Function;

/**
 * Bounded LRU-кэш на linked-мапе fastutil.
 *
 * <p>Порядок — access-order: каждое чтение двигает запись в хвост, при переполнении
 * выкидывается голова (давно не использованное). Лимит строгий: размер никогда не
 * превышает {@code maxSize} ни через {@link #put}, ни через {@link #getOrCreate}.
 *
 * <p>Hot-path заметки: hit — один хеш-lookup без аллокаций; miss — создание значения
 * через фабрику (аллокация самого значения неизбежна). Лямбда-фабрика не захватывает
 * контекст: ключ передаётся аргументом ({@link Function}), а не замыканием — JIT
 * инлайнит такую фабрику, capture-аллокации на hit нет.
 *
 * <p>Не потокобезопасен: предполагается использование из одного треда (render-тред,
 * поток физики). Синхронизация в hot path не ставится осознанно.
 *
 * @param <K> тип ключа
 * @param <V> тип значения
 */
public class FastutilLruCache<K, V> {
    private final int maxSize;
    private final Object2ObjectLinkedOpenHashMap<K, V> map;

    public FastutilLruCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got " + maxSize);
        }
        this.maxSize = maxSize;
        this.map = new Object2ObjectLinkedOpenHashMap<>(maxSize + 1);
    }

    /**
     * Получить значение или создать через фабрику при промахе.
     *
     * <p>Hit двигает запись в хвост (LRU), miss вставляет в хвост и выкидывает голову
     * при переполнении. Фабрика вызывается только на miss — на hit аллокаций нет.
     *
     * @param key     ключ
     * @param factory фабрика от ключа (без захвата контекста — ключ идёт аргументом)
     * @return существующее или вновь созданное значение (никогда null, если фабрика
     * не возвращает null)
     */
    public V getOrCreate(K key, Function<? super K, ? extends V> factory) {
        // Один lookup: getAndMoveToLast сразу двигает hit в хвост.
        // Нюанс fastutil: getAndMoveToLast по отсутствующему ключу возвращает null,
        // но при defaultReturnValue == null это неотличимо от "лежит null" —
        // null-значения в кэш не кладём (см. put), неоднозначности нет.
        final V hit = map.getAndMoveToLast(key);
        if (hit != null) {
            return hit;
        }
        final V created = factory.apply(key);
        put(key, created);
        return created;
    }

    /**
     * Получить значение с LRU-продвижением (hit — в хвост). Промах — null.
     *
     * <p>Один хеш-lookup вместо containsKey + get + move.
     */
    public V get(K key) {
        return map.getAndMoveToLast(key);
    }

    /**
     * Получить без LRU-продвижения (peek для инспекций/отладки).
     */
    public V peek(K key) {
        return map.get(key);
    }

    /**
     * Положить значение в хвост; при переполнении выкидывает голову.
     * Null-значения запрещены: они неотличимы от промаха в {@link #get}.
     */
    public void put(K key, V value) {
        if (value == null) {
            throw new NullPointerException("null values not allowed (indistinguishable from miss)");
        }
        map.putAndMoveToLast(key, value);
        if (map.size() > maxSize) {
            map.removeFirst();
        }
    }

    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    public V remove(K key) {
        return map.remove(key);
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }
}
