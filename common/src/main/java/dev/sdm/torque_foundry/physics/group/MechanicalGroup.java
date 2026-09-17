package dev.sdm.torque_foundry.physics.group;


import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalPowerConstants;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import dev.sdm.torque_foundry.physics.basic.WorkState;
import dev.sdm.torque_foundry.physics.simulation.PhysicsHook;
import dev.sdm.torque_foundry.physics.simulation.PhysicsHooks;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Группа машин = жёстко сцепленная сеть. Физика считается как DAG передачи
 * мощности: от источников (Output) по направленным рёбрам output->input
 * к потребителям (Input), где каждый узел может ТРАНСФОРМИРОВАТЬ мощность.
 *
 * Узел может иметь НЕСКОЛЬКО родителей (два генератора в один потребитель):
 * входы сливаются — обороты сети max, момент sum. Требования же детей узла
 * наоборот суммируются на его выходе.
 *
 * Ключевое свойство: за коробкой передач 8:1 (stepUp) скорость вырастает
 * в 8 раз, момент падает в 8 — потребители в этой ветке работают на других
 * показателях, чем в соседней ветке 4:1.
 *
 * Точки расширения физических условий — хуки {@link PhysicsHook}
 * (износ валов, потеря мощности двигателя, температура и т.д.),
 * регистрируются в {@link PhysicsHooks}.
 */
public class MechanicalGroup {
    protected static final int ADD_SIZE = 4;
    private static final AtomicLong INDEX_GENERATOR = new AtomicLong(0);

    protected long groupId;
    protected MechanicalMachine[] machines;
    protected int size;

    /** Счётчик физических тиков группы (для SimulationContext). */
    protected long simTick;

    // --- Scratch-буферы тика (mutable-архитектура: ноль аллокаций в тике) ---
    // Переиспользуются между тиками, растут при росте группы.
    private int[] parentBuf = new int[0];
    private int[] depthBuf = new int[0];
    private long[] subtreeTorqueBuf = new long[0];
    private WorkState[] stateBuf = new WorkState[0];
    private MechanicalPower[] inputPowerBuf = new MechanicalPower[0];
    private Integer[] orderBuf = new Integer[0];
    private int[] edgeFromBuf = new int[0];
    private int[] edgeToBuf = new int[0];
    private long[] edgeSpeedBuf = new long[0];
    private long[] edgeTorqueBuf = new long[0];
    private final SimulationContext simContext = new SimulationContext(this, 0);
    private final Queue<Integer> queueBuf = new ArrayDeque<>();
    private final MechanicalPower edgeScratch = MechanicalPower.fromRaw(0, 0);

    private static int[] ensureInt(int[] buffer, int size) {
        return buffer.length >= size ? buffer : new int[size];
    }

    private static long[] ensureLong(long[] buffer, int size) {
        return buffer.length >= size ? buffer : new long[size];
    }

    @SuppressWarnings("unchecked")
    private static <T> T[] ensureObjects(T[] buffer, int size, java.util.function.IntFunction<T[]> alloc) {
        return buffer.length >= size ? buffer : alloc.apply(size);
    }

    public MechanicalGroup() {
        generateIndex();
        this.machines = new MechanicalMachine[0];
    }

    public MechanicalGroup(long groupId) {
        this.groupId = groupId;
        this.machines = new MechanicalMachine[0];
    }

    public MechanicalGroup(MechanicalMachine... machines) {
        generateIndex();
        setMachines(machines);
    }


    public void setMachines(MechanicalMachine... machines) {
        this.machines = machines;
        this.size = machines.length;
        for (int i = 0; i < machines.length; i++) {
            var machine = machines[i];
            machine.setGroupIndex(groupId);
            machine.setGroupElementIndex(i);
        }
    }

    private void generateIndex() {
        this.groupId = INDEX_GENERATOR.getAndIncrement();;
    }

    public long getGroupId() {
        return groupId;
    }

    public MechanicalMachine getMachine(int index) {
        return machines[index];
    }

    public MechanicalMachine[] getMachines() {
        return machines;
    }

    public int getSize() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int addElement(@NotNull MechanicalMachine machine) {
        ensureCapacity(size + 1);
        int newIndex = size++;
        machines[newIndex] = machine;
        machine.setGroupIndex(groupId);
        machine.setGroupElementIndex(newIndex);
        return size;
    }

    public boolean removeElement(@NotNull MechanicalMachine machine) {
        for (int i = 0; i < size; i++) {
            if (machines[i] == machine) {
                int lastIndex = size - 1;

                if (i < lastIndex) {
                    MechanicalMachine lastMachine = machines[lastIndex];
                    machines[i] = lastMachine;
                    lastMachine.setGroupIndex(groupId);
                    lastMachine.setGroupElementIndex(i);
                }

                machines[lastIndex] = null;
                size--;

                machine.setGroupIndex(-1);
                return true;
            }
        }
        return false;
    }

    public void merge(@NotNull MechanicalGroup otherGroup) {
        if (otherGroup == this || otherGroup.size == 0) {
            return;
        }

        int startOffset = this.size;
        int totalNewSize = this.size + otherGroup.size;

        ensureCapacity(totalNewSize);

        System.arraycopy(otherGroup.machines, 0, this.machines, startOffset, otherGroup.size);
        this.size = totalNewSize;

        for (int i = startOffset; i < this.size; i++) {
            this.machines[i].setGroupIndex(groupId);
            this.machines[i].setGroupElementIndex(i);
        }

        Arrays.fill(otherGroup.machines, 0, otherGroup.size, null);
        otherGroup.size = 0;
    }

    @Nullable
    public MechanicalGroup split(int splitIndex) {
        if (splitIndex < 0 || splitIndex >= size) {
            throw new IndexOutOfBoundsException("Invalid split index: " + splitIndex + ", current size: " + size);
        }

        MechanicalMachine removedMachine = machines[splitIndex];
        int tailSize = size - splitIndex - 1;

        MechanicalGroup newGroup = null;

        if (tailSize > 0) {
            newGroup = new MechanicalGroup();
            newGroup.machines = new MechanicalMachine[tailSize];
            newGroup.size = tailSize;

            System.arraycopy(this.machines, splitIndex + 1, newGroup.machines, 0, tailSize);

            for (int i = 0; i < tailSize; i++) {
                newGroup.machines[i].setGroupIndex(newGroup.groupId);
            }
        }

        for (int i = splitIndex; i < size; i++) {
            machines[i] = null;
        }

        this.size = splitIndex;

        if (removedMachine != null) {
            removedMachine.setGroupIndex(-1);
        }

        return newGroup;
    }

    @Nullable
    public MechanicalGroup split(@NotNull MechanicalMachine machine) {
        for (int i = 0; i < size; i++) {
            if (machines[i] == machine) {
                return split(i);
            }
        }
        return null;
    }

    protected final void ensureCapacity(int minCapacity) {
        if (minCapacity > machines.length) {
            int newCapacity = Math.max(machines.length + ADD_SIZE, minCapacity);
            MechanicalMachine[] newArray = new MechanicalMachine[newCapacity];
            System.arraycopy(machines, 0, newArray, 0, size);
            this.machines = newArray;
        }
    }

    /**
     * Физический тик группы: симуляция передачи кинетической энергии по DAG.
     *
     * Фаза A — дерево/граф мощности: BFS от источников по направленным рёбрам
     *           output->input. Узел может получать энергию от НЕСКОЛЬКИХ
     *           родителей: входы сливаются (RPM max, момент sum). На каждом
     *           ребре применяются transform родителя и хуки.
     *
     * Фаза B — нагрузка снизу вверх: demand узла на входе = своя потребность
     *           + требования детей (снимаемые с его выхода), приведённые через
     *           его transform (t_in = t_out * ratio).
     *
     * Фаза C — состояния сверху вниз: demand vs доступный момент входа.
     *           Мёртвая ветка (все родители без питания) — IDLE.
     *
     * Выполняется в потоке физики (см. PhysicsPipeline), не на серверном треде.
     */
    public void computeTick() {
        // Mutable-архитектура: контекст один на группу, tick обновляется на месте
        simContext.setTick(++simTick);

        final List<PhysicsHook> hooks = PhysicsHooks.getHooks();
        for (int h = 0; h < hooks.size(); h++) {
            hooks.get(h).onGroupTickStart(this, simContext);
        }

        final MechanicalMachine[] machines = this.machines;
        final int n = size;

        // --- Scratch-буферы (переиспользуются, растут только при росте группы) ---
        final int[] parent = ensureInt(parentBuf, n);
        parentBuf = parent;
        final int[] depth = ensureInt(depthBuf, n);
        depthBuf = depth;
        final long[] subtreeTorque = ensureLong(subtreeTorqueBuf, n);
        subtreeTorqueBuf = subtreeTorque;
        final WorkState[] states = ensureObjects(stateBuf, n, WorkState[]::new);
        stateBuf = states;
        final MechanicalPower[] inputPower = ensureObjects(inputPowerBuf, n, MechanicalPower[]::new);
        inputPowerBuf = inputPower;
        final Integer[] order = ensureObjects(orderBuf, n, Integer[]::new);
        orderBuf = order;

        // Рёбра DAG (parent -> child): худший случай n*(n-1), растут редко.
        // На каждом ребре хранится мощность ПОСЛЕ transform родителя и хуков:
        // у раздатки/конической на разных выходах она разная.
        final int maxEdges = n > 1 ? n * (n - 1) : 1;
        int[] edgeFrom = edgeFromBuf;
        int[] edgeTo = edgeToBuf;
        long[] edgeSpeed = edgeSpeedBuf;
        long[] edgeTorque = edgeTorqueBuf;
        if (edgeFrom.length < maxEdges) {
            edgeFrom = new int[maxEdges];
            edgeTo = new int[maxEdges];
            edgeSpeed = new long[maxEdges];
            edgeTorque = new long[maxEdges];
            edgeFromBuf = edgeFrom;
            edgeToBuf = edgeTo;
            edgeSpeedBuf = edgeSpeed;
            edgeTorqueBuf = edgeTorque;
        }
        int edgeCount = 0;

        Arrays.fill(parent, 0, n, -1);
        Arrays.fill(inputPower, 0, n, null);
        Arrays.fill(depth, 0, n, 0);

        final Queue<Integer> queue = queueBuf;
        queue.clear();

        // --- Фаза A: граф мощности (BFS от источников, слияние входов) ---
        for (int i = 0; i < n; i++) {
            MechanicalPower output = machines[i].getOutput();
            if (output == null) {
                continue;
            }

            // Хук: ослабление источника (износ, топливо, температура)
            for (int h = 0; h < hooks.size(); h++) {
                output = hooks.get(h).onSourceOutput(machines[i], output, simContext);
            }

            if (inputPower[i] == null) {
                inputPower[i] = MechanicalPower.fromRaw(0, 0);
            }
            inputPower[i].copyFrom(output);
            queue.add(i);
        }

        while (!queue.isEmpty()) {
            final int i = queue.poll();
            final MechanicalMachine from = machines[i];

            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }

                // Уровневая фильтрация DAG: энергия течёт только НА СЛЕДУЮЩИЙ
                // уровень. Обратное ребро (вал передаёт назад уже питаемому узлу)
                // удваивало бы мощность — отсекаем.
                if (inputPower[j] != null && depth[j] != depth[i] + 1) {
                    continue;
                }

                final Direction dir = MechanicalGroupManager.directionBetween(from, machines[j]);
                if (dir == null) {
                    continue;
                }
                // Энергия идёт только по СТРОГОМУ направлению: выход -> встречный вход.
                if (!MechanicalGroupManager.canTransferPower(from, machines[j], dir)) {
                    continue;
                }

                // Мощность на этом ребре: изолированный скратч (хуки мутируют).
                // Transform узла направленный: в сторону этого ребра
                // (раздатка/коническая на разных выходах дают разное).
                edgeScratch.copyFrom(inputPower[i]);
                edgeScratch.copyFrom(from.transform(inputPower[i], dir));
                MechanicalPower in = edgeScratch;
                for (int h = 0; h < hooks.size(); h++) {
                    in = hooks.get(h).onTransmit(from, machines[j], in, simContext);
                }
                for (int h = 0; h < hooks.size(); h++) {
                    in = hooks.get(h).onReceive(machines[j], in, simContext);
                }

                if (inputPower[j] == null) {
                    // Первое обнаружение j
                    inputPower[j] = MechanicalPower.fromRaw(0, 0);
                    depth[j] = depth[i] + 1;
                    parent[j] = i;
                    queue.add(j);
                }

                // Слияние входов (узел может питаться от нескольких родителей):
                // обороты — max, момент — сумма, направление — от первого входа
                if (in.getSpeedRaw() > inputPower[j].getSpeedRaw()) {
                    inputPower[j].setSpeedRaw(in.getSpeedRaw());
                }
                inputPower[j].plus(0, in.getTorqueRaw());

                edgeFrom[edgeCount] = i;
                edgeTo[edgeCount] = j;
                edgeSpeed[edgeCount] = in.getSpeedRaw();
                edgeTorque[edgeCount] = in.getTorqueRaw();
                edgeCount++;
            }
        }

        // --- Фаза B: demand снизу вверх (по глубине, от листьев) ---
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, 0, n, (a, b) -> Integer.compare(depth[b], depth[a]));

        for (int idx = 0; idx < n; idx++) {
            final int m = order[idx];
            final MechanicalPower in = inputPower[m];

            if (in == null) {
                subtreeTorque[m] = 0;
                continue;
            }

            final MechanicalPower required = machines[m].getRequired();

            // Своя потребность на входе машины (если обороты входа позволяют)
            final long own = in.getSpeedRaw() >= required.getSpeedRaw()
                    ? required.getTorqueRaw() : 0;

            // Требования детей снимаются с ВЫХОДА узла и переводятся на вход:
            // t_in = t_out * (s_out / s_in) — сохранение мощности через transform.
            // У узла с несколькими выходами (раздатка) у каждого ребра свой ratio.
            // Если у ребёнка несколько родителей (слияние), его demand делится:
            // от ребёнка требуется только то, что не покрывают его ДРУГИЕ родители.
            long demandIn = own;
            for (int e = 0; e < edgeCount; e++) {
                if (edgeFrom[e] != m || inputPower[edgeTo[e]] == null) {
                    continue;
                }
                final int child = edgeTo[e];

                // Подпитка ребёнка от других родителей (без нас)
                long otherSupply = 0;
                for (int p = 0; p < edgeCount; p++) {
                    if (edgeTo[p] == child && edgeFrom[p] != m) {
                        otherSupply += edgeTorque[p];
                    }
                }

                final float ratio = in.getSpeedRaw() != 0
                        ? (float) edgeSpeed[e] / (float) in.getSpeedRaw()
                        : 1.0F;
                demandIn += Math.round(Math.max(0, subtreeTorque[child] - otherSupply) * ratio);
            }

            subtreeTorque[m] = demandIn;
        }

        // --- Фаза C: состояния сверху вниз (по глубине, от корней) ---
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, 0, n, (a, b) -> Integer.compare(depth[a], depth[b]));

        for (int idx = 0; idx < n; idx++) {
            final int m = order[idx];
            final MechanicalMachine machine = machines[m];
            final MechanicalPower in = inputPower[m];

            if (in == null) {
                // Недостижима от источников: порты не стыкуются ни по одному пути
                states[m] = WorkState.IDLE;
                machine.getReceived().reset();
                machine.setFreePower(0);
                continue;
            }

            machine.setReceived(in);

            // Leaf power: received минус требования детей (перевод в ватты
            // по скорости каждого ребра — у раздатки они разные)
            long childrenWatts = 0;
            for (int e = 0; e < edgeCount; e++) {
                if (edgeFrom[e] == m && inputPower[edgeTo[e]] != null) {
                    childrenWatts += (subtreeTorque[edgeTo[e]]
                            * MechanicalPowerConstants.PI2_60_NUM / MechanicalPowerConstants.PI2_60_DEN)
                            * edgeSpeed[e] / MechanicalPowerConstants.SCALE_2;
                }
            }
            machine.setFreePower(Math.max(0, in.getPower() - childrenWatts));

            // Ветвь выше мертва (все родители без питания) => здесь тоже нет питания
            boolean hasParent = false;
            boolean allParentsDead = true;
            for (int e = 0; e < edgeCount; e++) {
                if (edgeTo[e] == m) {
                    hasParent = true;
                    final WorkState ps = states[edgeFrom[e]];
                    if (ps != WorkState.IDLE && ps != WorkState.INSUFFICIENT_POWER) {
                        allParentsDead = false;
                    }
                }
            }
            if (hasParent && allParentsDead) {
                states[m] = WorkState.IDLE;
                continue;
            }

            if (machine.getOutput() != null) {
                // Источник: перегружен, если дети требуют больше его выдачи
                // (subtreeTorque уже с вычетом подпитки других родителей)
                states[m] = subtreeTorque[m] <= in.getTorqueRaw()
                        ? WorkState.WORKING : WorkState.INSUFFICIENT_POWER;
                continue;
            }

            // Направление вращения
            final byte requiredDirection = machine.getRequiredDirection();
            if (requiredDirection != -1 && in.getDirection() != requiredDirection) {
                states[m] = WorkState.WRONG_DIRECTION;
                continue;
            }

            final MechanicalPower required = machine.getRequired();
            if (in.getSpeedRaw() < required.getSpeedRaw()) {
                states[m] = WorkState.IDLE;
                continue;
            }

            // Своя потребность + требования детей уже сведены к входной стороне
            // в фазе B (с вычетом подпитки от других родителей при слиянии)
            states[m] = subtreeTorque[m] <= in.getTorqueRaw() ? WorkState.WORKING : WorkState.INSUFFICIENT_POWER;
        }

        for (int i = 0; i < n; i++) {
            machines[i].setWorkState(states[i] != null ? states[i] : WorkState.IDLE);
        }

        for (int h = 0; h < hooks.size(); h++) {
            hooks.get(h).onGroupTickEnd(this, simContext);
        }

        queue.clear();
    }

    /**
     * Ватты -> момент при заданной скорости (обратная формуле мощности).
     */
    private static long childrenWattsToTorque(long watts, long speedRaw) {
        if (watts <= 0 || speedRaw <= 0) {
            return 0;
        }
        // P [Вт] = t * s * (2pi/60) / SCALE^2  =>  t = P * SCALE^2 / (s * (2pi/60))
        return watts * MechanicalPowerConstants.SCALE_2 / (speedRaw * MechanicalPowerConstants.PI2_60_NUM / MechanicalPowerConstants.PI2_60_DEN);
    }
}
