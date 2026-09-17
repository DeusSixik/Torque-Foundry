package dev.sdm.torque_foundry.core.network;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import dev.sdm.torque_foundry.physics.basic.WorkState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Клиентский кэш состояния групп, полученный с сервера.
 * Хранит по позициям и состав группы (id/slot), и результаты физики
 * (state + received), потому что пакеты могут приходить раньше,
 * чем клиент прогрузит чанки; когда BlockEntity появится,
 * клиентский тикер применит данные через {@link #applyTo}.
 */
public final class ClientGroupCache {

    /** groupId -> члены группы (позиция + слот). */
    private static final Long2ObjectMap<List<GroupSyncPayload.Entry>> GROUPS = new Long2ObjectOpenHashMap<>();

    /** pos -> [groupId, slot]. */
    private static final Long2ObjectMap<long[]> POS_TO_GROUP = new Long2ObjectOpenHashMap<>();

    /** pos -> [state, speedRaw, torqueRaw, direction] — результаты физики. */
    private static final Long2ObjectMap<long[]> PHYSICS_BY_POS = new Long2ObjectOpenHashMap<>();

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
                    POS_TO_GROUP.remove(entry.pos().asLong());
                    PHYSICS_BY_POS.remove(entry.pos().asLong());
                }
            }
        }

        if (payload.entries().isEmpty()) {
            GROUPS.remove(payload.groupId());
            return;
        }

        for (GroupSyncPayload.Entry entry : payload.entries()) {
            applyMachine(level, entry, payload.groupId());
            POS_TO_GROUP.put(entry.pos().asLong(), new long[]{payload.groupId(), entry.slot()});
        }
        GROUPS.put(payload.groupId(), List.copyOf(payload.entries()));
    }

    /**
     * До применяет сохранённые данные к появившемуся BlockEntity
     * (вызывается из клиентского тикера, когда чанк догрузился).
     */
    public static synchronized void applyTo(MechanicalBlockEntity mechanical) {
        final long packed = mechanical.getBlockPos().asLong();

        final long[] groupData = POS_TO_GROUP.get(packed);
        if (groupData != null) {
            mechanical.machine.setGroupIndex(groupData[0]);
            mechanical.machine.setGroupElementIndex((int) groupData[1]);
        }

        final long[] physics = PHYSICS_BY_POS.get(packed);
        if (physics != null) {
            applyPhysics(mechanical, physics[0], physics[1], physics[2], (byte) physics[3]);
        }
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
        POS_TO_GROUP.clear();
        PHYSICS_BY_POS.clear();
    }

    private static void applyMachine(Level level, GroupSyncPayload.Entry entry, long groupId) {
        PHYSICS_BY_POS.put(entry.pos().asLong(), new long[]{
                entry.state(), entry.speedRaw(), entry.torqueRaw(), entry.direction()});

        if (!(level.getBlockEntity(entry.pos()) instanceof MechanicalBlockEntity mechanical)) {
            return;
        }
        mechanical.machine.setGroupIndex(groupId);
        mechanical.machine.setGroupElementIndex(entry.slot());
        applyPhysics(mechanical, entry.state(), entry.speedRaw(), entry.torqueRaw(), entry.direction());
    }

    private static void clearMachine(Level level, BlockPos pos) {
        PHYSICS_BY_POS.remove(pos.asLong());

        if (!(level.getBlockEntity(pos) instanceof MechanicalBlockEntity mechanical)) {
            return;
        }
        mechanical.machine.setGroupIndex(-1);
        mechanical.machine.setGroupElementIndex(-1);
        applyPhysics(mechanical, WorkState.IDLE.ordinal(), 0, 0, RotationDirection.FORWARD.index);
    }

    private static void applyPhysics(MechanicalBlockEntity mechanical, long state, long speedRaw, long torqueRaw, long direction) {
        mechanical.machine.setWorkState(WorkState.values()[(int) state]);
        mechanical.machine.setReceived(MechanicalPower.fromRaw(
                speedRaw, torqueRaw, RotationDirection.from((byte) direction)));
    }
}
