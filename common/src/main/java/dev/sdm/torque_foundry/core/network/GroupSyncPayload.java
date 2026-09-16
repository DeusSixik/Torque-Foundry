package dev.sdm.torque_foundry.core.network;

import dev.sdm.torque_foundry.core.block.TFBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Полный снапшот одной механической группы: id, число членов
 * и список позиций с индексами в группе. Пустой список членов
 * означает, что группа удалена.
 */
public record GroupSyncPayload(long groupId, int memberCount, List<Entry> entries) implements CustomPacketPayload {

    public record Entry(BlockPos pos, int slot) {
    }

    public static final CustomPacketPayload.Type<GroupSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(TFBlocks.id("mechanical_group_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GroupSyncPayload> CODEC = StreamCodec.ofMember(
            GroupSyncPayload::encode, GroupSyncPayload::decode);

    private static void encode(GroupSyncPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeVarLong(payload.groupId);
        buf.writeVarInt(payload.memberCount);
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buf.writeBlockPos(entry.pos);
            buf.writeVarInt(entry.slot);
        }
    }

    private static GroupSyncPayload decode(RegistryFriendlyByteBuf buf) {
        final long groupId = buf.readVarLong();
        final int memberCount = buf.readVarInt();
        final int size = buf.readVarInt();

        final List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readBlockPos(), buf.readVarInt()));
        }
        return new GroupSyncPayload(groupId, memberCount, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
