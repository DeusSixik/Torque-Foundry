package dev.sdm.torque_foundry.core.data;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;

import java.util.Collection;

public final class MechanicalGroupManager {

    private static final Long2ObjectMap<MechanicalGroup> GROUPS_BY_ID = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectMap<MechanicalMachine> MACHINES_BY_POS = new Long2ObjectOpenHashMap<>();

    public static synchronized MechanicalGroup getGroup(long groupId) {
        return GROUPS_BY_ID.get(groupId);
    }

    public static synchronized Collection<MechanicalGroup> getGroups() {
        return GROUPS_BY_ID.values();
    }

    /**
     * Полный сброс: вызывается при старте сервера. Группы пересоздаются
     * тикерами BlockEntity по мере загрузки чанков.
     */
    public static synchronized void clearAll() {
        GROUPS_BY_ID.clear();
        MACHINES_BY_POS.clear();
    }

    public static synchronized MechanicalGroup createOrAdd(LevelAccessor level, MechanicalBlockEntity entity) {
        final BlockPos rootPos = entity.getBlockPos();
        final MechanicalMachine machine = entity.machine;

        // Если для этой позиции уже числится другая машина (например,
        // оставшаяся от предыдущей загрузки чанка) — убираем её, чтобы
        // в группе не было дублей.
        final MechanicalMachine stale = MACHINES_BY_POS.get(rootPos.asLong());
        if (stale != null && stale != machine) {
            remove(stale);
        }

        long firstGroupId = -1;

        for (Direction side : Direction.values()) {
            final BlockPos neighborPos = rootPos.offset(side.getStepX(), side.getStepY(), side.getStepZ());

            // Ищем BlockEntity соседа
            if (level.getBlockEntity(neighborPos) instanceof MechanicalBlockEntity neighborEntity) {
                final MechanicalMachine neighborMachine = neighborEntity.machine;

                // Проверяем, могут ли две машины соединиться через грань 'side'
                if (canConnect(machine, neighborMachine, side)) {
                    final long neighborGroupId = neighborMachine.getGroupIndex();
                    if (neighborGroupId != -1) {
                        firstGroupId = neighborGroupId;
                        break;
                    }
                }
            }
        }

        final MechanicalGroup group;
        if (firstGroupId == -1) {
            group = new MechanicalGroup(machine);
            GROUPS_BY_ID.put(group.getGroupId(), group);
        } else {
            group = GROUPS_BY_ID.get(firstGroupId);
            if (group == null) {
                throw new RuntimeException("Group with ID " + firstGroupId + " not found");
            }

            group.addElement(machine);
        }

        MACHINES_BY_POS.put(rootPos.asLong(), machine);
        return group;
    }

    public static synchronized boolean remove(MechanicalBlockEntity block) {
        return remove(block.machine);
    }

    public static synchronized boolean remove(MechanicalMachine machine) {
        final long groupIndex = machine.getGroupIndex();
        if (groupIndex == -1) {
            return false;
        }

        final MechanicalGroup group = GROUPS_BY_ID.get(groupIndex);
        if (group == null) {
            return false;
        }

        group.removeElement(machine);

        if (machine.getBlockPos() != null) {
            MACHINES_BY_POS.remove(machine.getBlockPos().asLong(), machine);
        }

        return remove(group);
    }

    public static synchronized boolean remove(MechanicalGroup group) {
        if (!group.isEmpty()) {
            return false;
        }

        GROUPS_BY_ID.remove(group.getGroupId());
        return true;
    }

    /**
     * Проверяет механическую совместимость портов между текущей машиной и соседом.
     *
     * @param self       наша машина
     * @param neighbor   машина соседа
     * @param toNeighbor направление от нас к соседу
     */
    private static boolean canConnect(MechanicalMachine self, MechanicalMachine neighbor, Direction toNeighbor) {
        Direction fromNeighborToSelf = toNeighbor.getOpposite();

        boolean selfCanOutput = containsDirection(self.getOutputDirections(), toNeighbor);
        boolean neighborCanInput = containsDirection(neighbor.getInputDirections(), fromNeighborToSelf);

        // Наш выход стыкуется со входом соседа
        if (selfCanOutput && neighborCanInput) {
            return true;
        }

        boolean selfCanInput = containsDirection(self.getInputDirections(), toNeighbor);
        boolean neighborCanOutput = containsDirection(neighbor.getOutputDirections(), fromNeighborToSelf);

        // Наш вход стыкуется с выходом соседа (или двунаправленная передача вал-вал)
        return selfCanInput && neighborCanOutput;
    }

    private static boolean containsDirection(Direction[] directions, Direction target) {
        if (directions == null) return false;
        for (Direction dir : directions) {
            if (dir == target) return true;
        }
        return false;
    }
}
