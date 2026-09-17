package dev.sdm.torque_foundry.core.network;

import dev.architectury.networking.NetworkManager;
import dev.sdm.torque_foundry.TorqueFoundry;
import net.minecraft.client.Minecraft;

public final class TFNetworkingClient {

    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, GroupSyncPayload.TYPE, GroupSyncPayload.CODEC,
                (payload, context) -> context.queue(() -> {
                    TorqueFoundry.LOGGER.info("[TF-SYNC] received: groupId={}, members={}, entries={}",
                            payload.groupId(), payload.memberCount(), payload.entries().size());
                    ClientGroupCache.apply(Minecraft.getInstance().level, payload);
                }));
    }

    private TFNetworkingClient() {
    }
}
