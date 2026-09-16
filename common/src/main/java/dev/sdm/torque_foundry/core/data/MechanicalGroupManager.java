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

    public static synchronized MechanicalGroup getGroup(long groupId) {
        return GROUPS_BY_ID.get(groupId);
    }

    public static synchronized MechanicalGroup createOrAdd(LevelAccessor level, MechanicalBlockEntity entity) {
        final BlockPos.MutableBlockPos rootPos = entity.getBlockPos().mutable();
        final MechanicalMachine machine = entity.machine;

        // РњРЅРѕР¶РµСЃС‚РІРѕ РґР»СЏ СѓРЅРёРєР°Р»СЊРЅС‹С… ID РіСЂСѓРїРї СЃРѕСЃРµРґРµР№, Рє РєРѕС‚РѕСЂС‹Рј РјРѕР¶РЅРѕ РїРѕРґРєР»СЋС‡РёС‚СЊСЃСЏ
//        final LongOpenHashSet connectedGroupIds = new LongOpenHashSet();

        long firstGroupId = -1;

        for (Direction side : Direction.values()) {
            final BlockPos neighborPos = rootPos.move(side.getStepX(), side.getStepY(), side.getStepZ());

            // РС‰РµРј BlockEntity СЃРѕСЃРµРґР°
            if (level.getBlockEntity(neighborPos) instanceof MechanicalBlockEntity neighborEntity) {
                final MechanicalMachine neighborMachine = neighborEntity.machine;

                // РџСЂРѕРІРµСЂСЏРµРј, РјРѕРіСѓС‚ Р»Рё РґРІРµ РјР°С€РёРЅС‹ СЃРѕРµРґРёРЅРёС‚СЊСЃСЏ С‡РµСЂРµР· РіСЂР°РЅСЊ 'side'
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
            final MechanicalGroup group = new MechanicalGroup(machine);
            GROUPS_BY_ID.put(group.getGroupId(), group);
            return group;
        }

        final MechanicalGroup group = GROUPS_BY_ID.get(firstGroupId);
        if(group == null)
            throw new RuntimeException("Group with ID " + firstGroupId + " not found");

        group.addElement(machine);
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
     * РџСЂРѕРІРµСЂСЏРµС‚ РјРµС…Р°РЅРёС‡РµСЃРєСѓСЋ СЃРѕРІРјРµСЃС‚РёРјРѕСЃС‚СЊ РїРѕСЂС‚РѕРІ РјРµР¶РґСѓ С‚РµРєСѓС‰РµР№ РјР°С€РёРЅРѕР№ Рё СЃРѕСЃРµРґРѕРј.
     *
     * @param self       РЅР°С€Р° РјР°С€РёРЅР°
     * @param neighbor   РјР°С€РёРЅР° СЃРѕСЃРµРґР°
     * @param toNeighbor РЅР°РїСЂР°РІР»РµРЅРёРµ РѕС‚ РЅР°СЃ Рє СЃРѕСЃРµРґСѓ
     */
    private static boolean canConnect(MechanicalMachine self, MechanicalMachine neighbor, Direction toNeighbor) {
        Direction fromNeighborToSelf = toNeighbor.getOpposite();

        boolean selfCanOutput = containsDirection(self.getOutputDirections(), toNeighbor);
        boolean neighborCanInput = containsDirection(neighbor.getInputDirections(), fromNeighborToSelf);

        // РќР°С€ РІС‹С…РѕРґ СЃС‚С‹РєСѓРµС‚СЃСЏ СЃРѕ РІС…РѕРґРѕРј СЃРѕСЃРµРґР°
        if (selfCanOutput && neighborCanInput) {
            return true;
        }

        boolean selfCanInput = containsDirection(self.getInputDirections(), toNeighbor);
        boolean neighborCanOutput = containsDirection(neighbor.getOutputDirections(), fromNeighborToSelf);

        // РќР°С€ РІС…РѕРґ СЃС‚С‹РєСѓРµС‚СЃСЏ СЃ РІС‹С…РѕРґРѕРј СЃРѕСЃРµРґР° (РёР»Рё РґРІСѓРЅР°РїСЂР°РІР»РµРЅРЅР°СЏ РїРµСЂРµРґР°С‡Р° РІР°Р»-РІР°Р»)
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
