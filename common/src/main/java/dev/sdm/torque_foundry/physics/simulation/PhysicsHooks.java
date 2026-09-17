package dev.sdm.torque_foundry.physics.simulation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реестр физических хуков. Чтение — в потоке физики каждый тик,
 * регистрация — обычно при старте; CopyOnWriteArrayList безопасен для обоих.
 */
public final class PhysicsHooks {

    private static final List<PhysicsHook> HOOKS = new CopyOnWriteArrayList<>();

    static {
        // Пример внедрения физического условия (см. класс)
        register(new ShaftWearHook());
    }

    public static void register(PhysicsHook hook) {
        HOOKS.add(hook);
    }

    public static void unregister(PhysicsHook hook) {
        HOOKS.remove(hook);
    }

    public static List<PhysicsHook> getHooks() {
        return HOOKS;
    }

    private PhysicsHooks() {
    }
}
