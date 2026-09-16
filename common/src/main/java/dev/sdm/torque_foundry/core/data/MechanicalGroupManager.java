package dev.sdm.torque_foundry.core.data;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;

public final class MechanicalGroupManager {

    private static final Long2ObjectMap<MechanicalGroup> GROUPS_BY_ID = new Long2ObjectOpenHashMap<>();

    public static MechanicalGroup createOrAdd(LevelAccessor level, MechanicalBlockEntity entity) {
        final BlockPos.MutableBlockPos rootPos = entity.getBlockPos().mutable();
        final MechanicalMachine machine = entity.machine;

        // Множество для уникальных ID групп соседей, к которым можно подключиться
//        final LongOpenHashSet connectedGroupIds = new LongOpenHashSet();

        long firstGroupId = -1;

        for (Direction side : Direction.values()) {
            final BlockPos neighborPos = rootPos.move(side.getStepX(), side.getStepY(), side.getStepZ());

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

        if(firstGroupId == -1) {
             return new MechanicalGroup(machine);
        }

        final MechanicalGroup group = GROUPS_BY_ID.get(firstGroupId);
        if(group == null)
            throw new RuntimeException("Group with ID " + firstGroupId + " not found");

        group.addElement(machine);
        return group;
    }

    public static boolean remove(MechanicalBlockEntity block) {
        return remove(block.machine);
    }

    public static boolean remove(MechanicalMachine machine) {
        final long groupIndex = machine.getGroupIndex();
        final MechanicalGroup group = GROUPS_BY_ID.get(groupIndex);
        if(group == null)
            throw new RuntimeException("Group with ID " + groupIndex + " not found");

        group.removeElement(machine);
        return remove(group);
    }

    public static boolean remove(MechanicalGroup group) {
        final MechanicalMachine[] machines = group.getMachines();
        if(machines.length != 0) {
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
