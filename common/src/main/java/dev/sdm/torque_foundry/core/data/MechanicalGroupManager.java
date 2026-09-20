package dev.sdm.torque_foundry.core.data;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.api.collections.CustomLong2ObjectOpenHashMap;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public final class MechanicalGroupManager {

    private static final CustomLong2ObjectOpenHashMap<MechanicalGroup> GROUPS_BY_ID = new CustomLong2ObjectOpenHashMap<>();
    private static final CustomLong2ObjectOpenHashMap<MechanicalMachine> MACHINES_BY_POS = new CustomLong2ObjectOpenHashMap<>();
    private static final LongList PENDING_REMOVED_GROUPS = new LongArrayList();
    private static final List<MechanicalGroup> PENDING_NEW_GROUPS = new ArrayList<>();
    private static final LongList DIRTY_GROUPS = new LongArrayList();

    /**
     * Группа изменилась (состав/физика) — требует досылки снапшота клиентам.
     */
    public static synchronized void markDirty(long groupId) {
        if (groupId != -1) {
            DIRTY_GROUPS.add(groupId);
        }
    }

    /**
     * Id групп, изменённых с прошлого вызова. Синк клиентов — после физ. тика.
     */
    public static synchronized long[] drainDirtyGroups() {
        if (DIRTY_GROUPS.isEmpty()) {
            return new long[0];
        }
        final long[] ids = DIRTY_GROUPS.toLongArray();
        DIRTY_GROUPS.clear();
        return ids;
    }

    public MechanicalGroupManager() { }

    public static synchronized MechanicalGroup getGroup(long groupId) {
        return GROUPS_BY_ID.get(groupId);
    }

    public static synchronized Collection<MechanicalGroup> getGroups() {
        return GROUPS_BY_ID.values();
    }

    /**
     * Снапшот групп для физического тика (снимается под блокировкой).
     * values() — живой view мапы: без копии в массив итерация
     * в потоке физики упадёт с CME при мутации с серверного треда.
     */
    public static synchronized MechanicalGroup[] getGroupsPrimitive() {
        return GROUPS_BY_ID.values().toArray(new MechanicalGroup[0]);
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

        long targetGroupId = -1;
        final LongList mergeGroupIds = new LongArrayList();

        for (Direction side : Direction.values()) {
            final BlockPos neighborPos = rootPos.offset(side.getStepX(), side.getStepY(), side.getStepZ());

            // Ищем BlockEntity соседа
            if (level.getBlockEntity(neighborPos) instanceof MechanicalBlockEntity neighborEntity) {
                final MechanicalMachine neighborMachine = neighborEntity.machine;

                // Проверяем, могут ли две машины соединиться через грань 'side'
                // (выход -> встречный вход, или наш вход -> встречный выход)
                if (canConnect(machine, neighborMachine, side)) {
                    final long neighborGroupId = neighborMachine.getGroupIndex();
                    if (neighborGroupId != -1 && neighborGroupId != targetGroupId) {
                        if (targetGroupId == -1) {
                            targetGroupId = neighborGroupId;
                        } else {
                            // Блок соединяет две разные группы — их нужно слить
                            mergeGroupIds.add(neighborGroupId);
                        }
                    }
                }
            }
        }

        final MechanicalGroup group;
        if (targetGroupId == -1) {
            group = new MechanicalGroup(machine);
            GROUPS_BY_ID.put(group.getGroupId(), group);
        } else {
            group = GROUPS_BY_ID.get(targetGroupId);
            if (group == null) {
                throw new RuntimeException("Group with ID " + targetGroupId + " not found");
            }

            group.addElement(machine);

            // Все группы, к которым блок также стыкуется, вливаются в целевую:
            // через новый блок они физически соединены в одну сеть.
            for (int i = 0; i < mergeGroupIds.size(); i++) {
                final MechanicalGroup other = GROUPS_BY_ID.get(mergeGroupIds.getLong(i));
                if (other != null && other != group) {
                    group.merge(other);
                    remove(other);
                }
            }
        }

        markDirty(group.getGroupId());
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

        // Состав группы изменился — клиенту нужен свежий снапшот
        markDirty(groupIndex);

        group.removeElement(machine);

        if (machine.getBlockPos() != null) {
            MACHINES_BY_POS.remove(machine.getBlockPos().asLong(), machine);
        }

        // Цепочка могла распасться на части — проверяем связность
        if (!group.isEmpty()) {
            revalidateConnectivity(group);
        }

        return remove(group);
    }

    public static synchronized boolean remove(MechanicalGroup group) {
        if (!group.isEmpty()) {
            return false;
        }

        GROUPS_BY_ID.remove(group.getGroupId());
        // Клиент должен удалить эту группу из кэша (syncGroupRemoved)
        PENDING_REMOVED_GROUPS.add(group.getGroupId());
        return true;
    }

    /**
     * Возвращает id групп, удалённых с прошлого вызова (слияния,
     * опустевшие группы после выгрузки чанков), чтобы отправить
     * клиентам пустые снапшоты.
     */
    public static synchronized long[] drainRemovedGroups() {
        if (PENDING_REMOVED_GROUPS.isEmpty()) {
            return new long[0];
        }

        final long[] ids = PENDING_REMOVED_GROUPS.toLongArray();
        PENDING_REMOVED_GROUPS.clear();
        return ids;
    }

    /**
     * Возвращает группы, образовавшиеся при разрыве цепочек
     * (см. {@link #revalidateConnectivity}), для синхронизации клиентам.
     */
    public static synchronized List<MechanicalGroup> drainNewGroups() {
        if (PENDING_NEW_GROUPS.isEmpty()) {
            return List.of();
        }

        final List<MechanicalGroup> groups = new ArrayList<>(PENDING_NEW_GROUPS);
        PENDING_NEW_GROUPS.clear();
        return groups;
    }

    /**
     * Проверяет связность группы после удаления машины: BFS от каждой машины
     * по соседним позициям с учётом портов ({@link #canConnect}). Оторвавшиеся
     * компоненты выделяются в новые группы.
     */
    private static void revalidateConnectivity(MechanicalGroup group) {
        if (group.getSize() <= 1) {
            return;
        }

        // Работаем со снапшотом: цикл переноса мутирует массив группы
        // (removeElement swap-last), разметка по живому массиву невалидна.
        final int count = group.getSize();
        final MechanicalMachine[] machines = group.getMachines();
        final MechanicalMachine[] snapshot = new MechanicalMachine[count];
        System.arraycopy(machines, 0, snapshot, 0, count);

        // Разметка компонент связности по снапшоту
        final int[] component = new int[count];
        java.util.Arrays.fill(component, -1);
        int components = 0;
        for (int i = 0; i < count; i++) {
            if (component[i] != -1) {
                continue;
            }
            floodFill(i, snapshot, component, components);
            components++;
        }

        if (components <= 1) {
            return;
        }

        // Компонента 0 (с первой машиной снапшота) остаётся в текущей группе,
        // остальные становятся новыми группами.
        final Map<Integer, MechanicalGroup> newGroups = new HashMap<>();
        for (int i = 0; i < count; i++) {
            final int c = component[i];
            if (c == 0) {
                continue;
            }

            final MechanicalGroup target = newGroups.computeIfAbsent(c, k -> {
                final MechanicalGroup newGroup = new MechanicalGroup();
                GROUPS_BY_ID.put(newGroup.getGroupId(), newGroup);
                PENDING_NEW_GROUPS.add(newGroup);
                return newGroup;
            });

            group.removeElement(snapshot[i]);
            target.addElement(snapshot[i]);
        }
    }

    private static void floodFill(int start, MechanicalMachine[] machines, int[] component, int id) {
        final Queue<Integer> queue = new ArrayDeque<>();
        component[start] = id;
        queue.add(start);

        while (!queue.isEmpty()) {
            final int i = queue.poll();
            final MechanicalMachine self = machines[i];

            for (int j = 0; j < machines.length; j++) {
                if (component[j] != -1 || machines[j] == null) {
                    continue;
                }

                final Direction dir = directionBetween(self, machines[j]);
                if (dir == null) {
                    continue;
                }
                if (!canConnect(self, machines[j], dir)) {
                    continue;
                }

                component[j] = id;
                queue.add(j);
            }
        }
    }

    /**
     * Направление от машины A к машине B, если они смежны (по X/Y/Z),
     * иначе null. Публично: используется физикой группы.
     */
    public static Direction directionBetween(MechanicalMachine from, MechanicalMachine to) {
        final BlockPos a = from.getBlockPos();
        final BlockPos b = to.getBlockPos();
        if (a == null || b == null) {
            return null;
        }

        final int dx = b.getX() - a.getX();
        final int dy = b.getY() - a.getY();
        final int dz = b.getZ() - a.getZ();

        if (dx == 1 && dy == 0 && dz == 0) return Direction.EAST;
        if (dx == -1 && dy == 0 && dz == 0) return Direction.WEST;
        if (dy == 1 && dx == 0 && dz == 0) return Direction.UP;
        if (dy == -1 && dx == 0 && dz == 0) return Direction.DOWN;
        if (dz == 1 && dx == 0 && dy == 0) return Direction.SOUTH;
        if (dz == -1 && dx == 0 && dy == 0) return Direction.NORTH;
        return null;
    }

    /**
     * Проверяет механическую совместимость портов между текущей машиной и соседом.
     * Публично: используется также физикой группы (распространение энергии
     * только по валидным output->input цепочкам).
     *
     * @param self       наша машина
     * @param neighbor   машина соседа
     * @param toNeighbor направление от нас к соседу
     */
    public static boolean canConnect(MechanicalMachine self, MechanicalMachine neighbor, Direction toNeighbor) {
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

    /**
     * Строгое направление ПЕРЕДАЧИ ЭНЕРГИИ: from обязан иметь выход в грань
     * toNeighbor, to — принимать с обратной грани. В отличие от {@link #canConnect}
     * (структурная стыковка, симметричная) — не позволяет потребителю
     * "отдавать" энергию дальше: у потребителя выходов нет.
     */
    public static boolean canTransferPower(MechanicalMachine from, MechanicalMachine to, Direction fromTo) {
        return containsDirection(from.getOutputDirections(), fromTo)
                && containsDirection(to.getInputDirections(), fromTo.getOpposite());
    }

    private static boolean containsDirection(Direction[] directions, Direction target) {
        if (directions == null) return false;
        for (Direction dir : directions) {
            if (dir == target) return true;
        }
        return false;
    }
}
