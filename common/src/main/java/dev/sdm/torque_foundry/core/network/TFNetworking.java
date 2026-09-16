package dev.sdm.torque_foundry.core.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.fabricmc.api.EnvType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class TFNetworking {

    private static final double SYNC_RADIUS_SQR = 64.0 * 64.0;

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
            NetworkManager.sendToPlayer(player, buildPayload(group));
        }
    }

    private static GroupSyncPayload buildPayload(MechanicalGroup group) {
        final MechanicalMachine[] machines = group.getMachines();
        final int size = Math.min(group.getSize(), machines.length);

        final List<GroupSyncPayload.Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final MechanicalMachine machine = machines[i];
            if (machine == null || machine.getBlockPos() == null) {
                continue;
            }
            entries.add(new GroupSyncPayload.Entry(machine.getBlockPos(), machine.getGroupElementIndex()));
        }

        return new GroupSyncPayload(group.getGroupId(), group.getSize(), entries);
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

        if (!targets.isEmpty()) {
            NetworkManager.sendToPlayers(targets, payload);
        }
    }

    private TFNetworking() {
    }
}
