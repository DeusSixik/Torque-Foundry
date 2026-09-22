package dev.sdm.torque_foundry.core.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.api.physics.GroupSnapshotView;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.fabricmc.api.EnvType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class TFNetworking {

    private static final double SYNC_RADIUS_SQR = 64.0 * 64.0;

    /**
     * Буфер сборки payload из снапшота. Только серверный тред (все sync*
     * вызываются из него): один переиспользуемый view — ноль аллокаций
     * массивов на пакет, растёт только при росте групп.
     */
    private static final GroupSnapshotView SYNC_VIEW = new GroupSnapshotView();

    public static void register() {
        if (Platform.getEnv() == EnvType.CLIENT) {
            TFNetworkingClient.register();
        }
    }

    /**
     * Отправляет клиентам полный снапшот группы (состав, слоты).
     */
    public static void syncGroup(ServerLevel level, MechanicalGroup group, BlockPos origin) {
        if (group == null) {
            return;
        }

        sendToTracking(level, origin, buildPayload(group));
    }

    /**
     * Отправляет игроку снапшоты всех групп (при входе в игру).
     */
    public static void syncAllGroups(ServerPlayer player) {
        for (MechanicalGroup group : MechanicalGroupManager.getGroups()) {
            if (!group.isEmpty()) {
                NetworkManager.sendToPlayer(player, buildPayload(group));
            }
        }
    }

    /**
     * Периодический полный синк: снапшоты всех групп всем игрокам.
     * Пока для теста; позже — только ближайшие группы.
     */
    public static void syncAllGroupsToPlayers(List<ServerPlayer> players) {
        for (MechanicalGroup group : MechanicalGroupManager.getGroups()) {
            if (group.isEmpty()) {
                continue;
            }
            NetworkManager.sendToPlayers(players, buildPayload(group));
        }
    }

    /**
     * Досылает снапшоты изменённых (грязных) групп и удаляет пропавшие.
     * Вызывается ПОСЛЕ завершения физического тика — данные в машинах
     * уже пересчитаны, клиент получает актуальную энергию.
     *
     * @param players    все игроки сервера (группы не привязаны к уровню)
     * @param dirtyIds   id групп, чей состав/физика изменились
     * @param removedIds id полностью удалённых групп
     */
    public static void syncDirtyGroups(List<ServerPlayer> players, long[] dirtyIds, long[] removedIds) {
        for (long groupId : dirtyIds) {
            final MechanicalGroup group = MechanicalGroupManager.getGroup(groupId);
            if (group != null && !group.isEmpty()) {
                NetworkManager.sendToPlayers(players, buildPayload(group));
            } else {
                // группа исчезла — клиент чистит её кэш
                NetworkManager.sendToPlayers(players, new GroupSyncPayload(groupId, 0, List.of()));
            }
        }

        for (long removedId : removedIds) {
            NetworkManager.sendToPlayers(players, new GroupSyncPayload(removedId, 0, List.of()));
        }
    }

    /**
     * Payload из опубликованного снапшота группы: копия под замком группы —
     * данные всегда из ОДНОГО завершённого тика, гонки с потоком физики нет
     * (раньше здесь читались живые машины на серверном треде).
     *
     * <p>View переиспользуется: серверный тред — единственный потребитель.
     *
     * @param group группа (публикация берётся её снапшотом)
     * @return payload; если группа ещё ничего не публиковала (tickId < 0) —
     *     payload без записей (клиент получит пустой состав, досыл будет)
     */
    static GroupSyncPayload buildPayload(MechanicalGroup group) {
        if (!group.copySnapshotTo(SYNC_VIEW, true) || SYNC_VIEW.tickId < 0) {
            return new GroupSyncPayload(group.getGroupId(), 0, List.of());
        }
        return buildPayload(SYNC_VIEW);
    }

    /**
     * Сборка payload из снапшота. Пакетно-private: тесты сверяют маппинг
     * view -> entries. Пустые слоты (posLong == 0) пропускаются.
     */
    static GroupSyncPayload buildPayload(GroupSnapshotView view) {
        final List<GroupSyncPayload.Entry> entries =
                new ArrayList<>(view.machineCount);
        for (int i = 0; i < view.machineCount; i++) {
            if (view.posLong[i] == 0L) {
                continue;
            }
            entries.add(new GroupSyncPayload.Entry(
                    BlockPos.of(view.posLong[i]),
                    i,
                    view.states[i].ordinal(),
                    view.receivedSpeedRaw[i],
                    view.receivedTorqueRaw[i],
                    view.receivedDirection[i]));
        }
        return new GroupSyncPayload(view.groupId, view.machineCount, entries);
    }

    /**
     * Уведомляет клиентов об удалении группы.
     */
    public static void syncGroupRemoved(ServerLevel level, long groupId, BlockPos origin) {
        sendToTracking(level, origin, new GroupSyncPayload(groupId, 0, List.of()));
    }

    private static void sendToTracking(ServerLevel level, BlockPos origin, GroupSyncPayload payload) {
        final List<ServerPlayer> targets = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(origin.getX(), origin.getY(), origin.getZ()) <= SYNC_RADIUS_SQR) {
                targets.add(player);
            }
        }

        TorqueFoundry.LOGGER.info("[TF-SYNC] send: groupId={}, members={}, entries={}, targets={}",
                payload.groupId(), payload.memberCount(), payload.entries().size(), targets.size());

        if (!targets.isEmpty()) {
            NetworkManager.sendToPlayers(targets, payload);
        }
    }

    private TFNetworking() {
    }
}
