package dev.sdm.torque_foundry.core.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;

public final class TFNetworkingClient {

    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, GroupSyncPayload.TYPE, GroupSyncPayload.CODEC,
                (payload, context) -> context.queue(() ->
                        ClientGroupCache.apply(Minecraft.getInstance().level, payload)));
    }

    private TFNetworkingClient() {
    }
}
