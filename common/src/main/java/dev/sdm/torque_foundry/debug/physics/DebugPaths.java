package dev.sdm.torque_foundry.debug.physics;

import java.nio.file.Path;

/**
 * Пути отладочных файлов инспектора. Реализация для платформы —
 * через {@link java.util.function.Supplier}: common не знает, где у
 * Fabric/NeoForge папка config.
 */
public final class DebugPaths {

    private static volatile java.util.function.Supplier<Path> configDir = () -> Path.of("config");

    private DebugPaths() {
    }

    /** Вызывается платформой на клиенте при старте (если известно иначе). */
    public static void setConfigDir(java.util.function.Supplier<Path> supplier) {
        if (supplier != null) {
            configDir = supplier;
        }
    }

    public static Path pinFile() {
        return configDir.get().resolve("torque_foundry_pinned.cfg");
    }
}
