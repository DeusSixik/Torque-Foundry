package dev.sdm.torque_foundry.core.network;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Клиентский кэш состояния групп, полученный с сервера.
 * Данные хранятся по позициям, потому что пакеты могут приходить
 * раньше, чем клиент прогрузит чанки; когда BlockEntity появится,
 * клиентский тикер применит данные через {@link #applyTo}.
 */
public final class ClientGroupCache {

    private static final Long2ObjectMap<List<GroupSyncPayload.Entry>> GROUPS = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectMap<long[]> POS_TO_DATA = new Long2ObjectOpenHashMap<>();

    public static synchronized void apply(Level level, GroupSyncPayload payload) {
        final List<GroupSyncPayload.Entry> old = GROUPS.get(payload.groupId());

        if (old != null) {
            for (GroupSyncPayload.Entry entry : old) {
                boolean stillMember = false;
                for (GroupSyncPayload.Entry fresh : payload.entries()) {
                    if (fresh.pos().equals(entry.pos())) {
                        stillMember = true;
                        break;
                    }
                }
                if (!stillMember) {
                    clearMachine(level, entry.pos());
                    POS_TO_DATA.remove(entry.pos().asLong());
                }
            }
        }

        if (payload.entries().isEmpty()) {
            GROUPS.remove(payload.groupId());
            return;
        }

        for (GroupSyncPayload.Entry entry : payload.entries()) {
            applyMachine(level, entry, payload.groupId());
            POS_TO_DATA.put(entry.pos().asLong(), new long[]{payload.groupId(), entry.slot()});
        }
        GROUPS.put(payload.groupId(), List.copyOf(payload.entries()));
    }

    /**
     * До применяет сохранённые данные к появившемуся BlockEntity
     * (вызывается из клиентского тикера, когда чанк догрузился).
     */
    public static synchronized void applyTo(MechanicalBlockEntity mechanical) {
        final long[] data = POS_TO_DATA.get(mechanical.getBlockPos().asLong());
        if (data == null) {
            return;
        }

        mechanical.machine.setGroupIndex(data[0]);
        mechanical.machine.setGroupElementIndex((int) data[1]);
    }

    /**
     * @return число членов группы или null, если группа ещё не синхронизирована.
     */
    public static synchronized Integer getMembers(long groupId) {
        final List<GroupSyncPayload.Entry> entries = GROUPS.get(groupId);
        return entries == null ? null : entries.size();
    }

    public static synchronized void clear() {
        GROUPS.clear();
        POS_TO_DATA.clear();
    }

    private static void applyMachine(Level level, GroupSyncPayload.Entry entry, long groupId) {
        if (!(level.getBlockEntity(entry.pos()) instanceof MechanicalBlockEntity mechanical)) {
            return;
        }
        mechanical.machine.setGroupIndex(groupId);
        mechanical.machine.setGroupElementIndex(entry.slot());
    }

    private static void clearMachine(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MechanicalBlockEntity mechanical)) {
            return;
        }
        mechanical.machine.setGroupIndex(-1);
        mechanical.machine.setGroupElementIndex(-1);
    }
}
