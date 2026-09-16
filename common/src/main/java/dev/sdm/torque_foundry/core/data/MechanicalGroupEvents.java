package dev.sdm.torque_foundry.core.data;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.sdm.torque_foundry.core.network.TFNetworking;

/**
 * Жизненный цикл групп:
 * - старт сервера: полный сброс in-memory групп;
 * - пересоздание групп происходит лениво, тикерами BlockEntity
 *   по мере загрузки чанков (см. MechanicalBlockEntity#serverTick);
 * - вход игрока: полная синхронизация групп клиенту.
 */
public final class MechanicalGroupEvents {

    public static void register() {
        LifecycleEvent.SERVER_STARTING.register(server -> MechanicalGroupManager.clearAll());
        PlayerEvent.PLAYER_JOIN.register(TFNetworking::syncAllGroups);
    }

    private MechanicalGroupEvents() {
    }
}
