package dev.sdm.torque_foundry.core.data;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.sdm.torque_foundry.core.network.TFNetworking;
import dev.sdm.torque_foundry.physics.simulation.PhysicsPipeline;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Жизненный цикл групп:
 * - старт сервера: сброс in-memory групп + запуск потока физики;
 * - пересоздание групп происходит лениво, тикерами BlockEntity
 *   по мере загрузки чанков (см. MechanicalBlockEntity#serverTick);
 * - каждый серверный тик: физика группы (поток физики опережает сервер);
 * - вход игрока: полная синхронизация групп клиенту.
 */
public final class MechanicalGroupEvents {

    private static volatile PhysicsPipeline physicsPipeline;

    /**
     * Период полного синка групп клиентам (в тиках). Подстраховка на случай
     * потерянного dirty-пакета или рассинхрона кэша. Пока — всем игрокам все
     * группы; позже заменим на отправку ближайших групп по игроку.
     */
    private static final int PERIODIC_SYNC_INTERVAL = 40;

    private static int tickCounter;

    public static void register() {
        LifecycleEvent.SERVER_STARTING.register(server -> {
            MechanicalGroupManager.clearAll();

            // Один поток физики по умолчанию; поток-демон, чтобы не держать JVM
            final PhysicsPipeline pipeline = new PhysicsPipeline(1, Executors.newSingleThreadExecutor(r -> {
                final Thread thread = new Thread(r, "TorqueFoundry-Physics");
                thread.setDaemon(true);
                return thread;
            }));
            pipeline.start();
            physicsPipeline = pipeline;
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
                physicsPipeline = null;
            }
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
