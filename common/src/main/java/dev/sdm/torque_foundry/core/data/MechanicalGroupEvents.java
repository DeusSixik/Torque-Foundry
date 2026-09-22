package dev.sdm.torque_foundry.core.data;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.sdm.torque_foundry.core.network.TFNetworking;
import dev.sdm.torque_foundry.physics.simulation.PhysicsPipeline;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Жизненный цикл групп:
 * - старт сервера: сброс in-memory групп + запуск пула физики;
 * - пересоздание групп происходит лениво, тикерами BlockEntity
 *   по мере загрузки чанков (см. MechanicalBlockEntity#serverTick);
 * - каждый серверный тик: физика групп (воркеры пула опережают сервер);
 * - вход игрока: полная синхронизация групп клиенту.
 */
public final class MechanicalGroupEvents {

    private static volatile PhysicsPipeline physicsPipeline;
    private static volatile ExecutorService physicsExecutor;

    /**
     * Период полного синка групп клиентам (в тиках). Подстраховка на случай
     * потерянного dirty-пакета или рассинхрона кэша. Пока — всем игрокам все
     * группы; позже заменим на отправку ближайших групп по игроку.
     */
    private static final int PERIODIC_SYNC_INTERVAL = 40;

    /**
     * Воркеры физики: половина ядер, зажата в 1..4 — физика групп лёгкая
     * (микросекунды на группу), большего параллелизма не требуется,
     * а лишние треды греют планировщик (см. док, Ф3).
     */
    private static final int PHYSICS_THREADS =
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private static int tickCounter;

    public static void register() {
        LifecycleEvent.SERVER_STARTING.register(server -> {
            MechanicalGroupManager.clearAll();

            // Пул физики: потоки-демоны, чтобы не держать JVM.
            final AtomicInteger seq = new AtomicInteger();
            physicsExecutor = Executors.newFixedThreadPool(PHYSICS_THREADS, r -> {
                final Thread thread =
                        new Thread(r, "TorqueFoundry-Physics-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            physicsPipeline = new PhysicsPipeline(PHYSICS_THREADS, physicsExecutor);
        });

        TickEvent.SERVER_POST.register(server -> {
            final PhysicsPipeline pipeline = physicsPipeline;
            if (pipeline == null) {
                return;
            }

            // 1. дожидаемся физики прошлого тика, запускаем новую
            pipeline.tick();

            // 2. досылаем клиентам группы, изменившиеся за тик
            //    (установка/разрушение звеньев): данные уже пересчитаны
            final long[] dirty = MechanicalGroupManager.drainDirtyGroups();
            final long[] removed = MechanicalGroupManager.drainRemovedGroups();

            if (dirty.length > 0 || removed.length > 0) {
                @SuppressWarnings("unchecked")
                final List<ServerPlayer> players = (List<ServerPlayer>) (List<?>) server.getPlayerList().getPlayers();
                TFNetworking.syncDirtyGroups(players, dirty, removed);
            }

            // 3. периодический полный синк (страховка от рассинхрона кэша)
            if (++tickCounter >= PERIODIC_SYNC_INTERVAL) {
                tickCounter = 0;

                if (!server.getPlayerList().getPlayers().isEmpty()) {
                    TFNetworking.syncAllGroupsToPlayers(server.getPlayerList().getPlayers());
                }
            }
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            final PhysicsPipeline pipeline = physicsPipeline;
            if (pipeline != null) {
                pipeline.stop();
            }
            final ExecutorService executor = physicsExecutor;
            if (executor != null) {
                executor.shutdownNow();
            }
            physicsPipeline = null;
            physicsExecutor = null;
        });

        PlayerEvent.PLAYER_JOIN.register(TFNetworking::syncAllGroups);
    }

    /**
     * Пайплайн текущей сессии (null вне сервера).
     */
    public static PhysicsPipeline getPhysicsPipeline() {
        return physicsPipeline;
    }

    private MechanicalGroupEvents() {
    }
}
