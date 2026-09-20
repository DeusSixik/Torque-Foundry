package dev.sdm.torque_foundry.physics.group;


import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.PhysicsMath;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.hook.PhysicsHook;
import dev.sdm.torque_foundry.physics.hook.PhysicsHooks;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Группа машин = жёстко сцепленная сеть. Физика считается как DAG передачи
 * мощности: от источников (Output) по направленным рёбрам output->input
 * к потребителям (Input), где каждый узел может ТРАНСФОРМИРОВАТЬ мощность.
 * <p>
 * Сеть инерционная: обороты сети (currentSpeedRaw) — интеграл момента
 * (разгон/торможение через PhysicsMath.tickSpeed). Материал деталей
 * задаёт трение и инерцию. Перегрузка сети клинит всю цепь (фаза D).
 * <p>
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

    /**
     * Счётчик физических тиков группы (для SimulationContext).
     */
    protected long simTick;

    /**
     * ТЕКУЩИЕ обороты сети (milli-RPM, на базовом уровне — уровне источников).
     * Инерционная величина: раскручивается и выбегает плавно
     * (см. PhysicsMath.tickSpeed). Все скорости ветвей — производные.
     */
    protected long currentSpeedRaw;

    /**
     * Текущие обороты сети в RPM (для UI/рендера).
     */
    public double getCurrentSpeedRpm() {
        return currentSpeedRaw / 1000.0;
    }

    /**
     * Текущие обороты сети в milli-RPM (для тестов).
     */
    public long currentSpeedForTest() {
        return currentSpeedRaw;
    }

    // --- Scratch-буферы тика (mutable-архитектура: ноль аллокаций в тике) ---
    // Переиспользуются между тиками, растут при росте группы.
    private int[] parentBuf = new int[0];
    private int[] depthBuf = new int[0];
    private long[] subtreeTorqueBuf = new long[0];
    private WorkState[] stateBuf = new WorkState[0];
    private RotationalPower[] inputPowerBuf = new RotationalPower[0];
    private boolean[] hasPowerBuf = new boolean[0];
    private int[] orderBuf = new int[0];
    private int[] queueBuf = new int[0];
    private int queueHead;
    private int queueTail;
    private int[] edgeFromBuf = new int[0];
    private int[] edgeToBuf = new int[0];
    private long[] edgeSpeedBuf = new long[0];
    private long[] edgeTorqueBuf = new long[0];
    private boolean[] jammedBuf = new boolean[0];
    private boolean[] conflictBuf = new boolean[0];
    private final Long2IntMap posToIndexBuf = new Long2IntOpenHashMap();
    /** Направления соседей (кеш Direction.values(): values() клонирует массив). */
    private static final Direction[] NEIGHBOR_DIRS = Direction.values();
    private final SimulationContext simContext = new SimulationContext(this, 0);
    private final RotationalPower edgeScratch = RotationalPower.fromRaw(0, 0);
    private final List<MechanicalMachine> jammedMachinesBuf = new ArrayList<>();
    private final List<MechanicalMachine> jammedProducersBuf = new ArrayList<>();

    /**
     * Гистерезис конфликта направлений: клин только при УСТОЙЧИВОМ конфликте —
     * N тиков подряд. Одиночный/мигающий конфликт не клинит (лекарство от
     * дребезга клин<->не-клин при net≈0, тот же класс, что в маргинальном
     * дефиците).
     */
    private int directionConflictTicks = 0;
    private static final int DIRECTION_JAM_TICKS = 20;

    private static int[] ensureInt(int[] buffer, int size) {
        return buffer.length >= size ? buffer : new int[size];
    }

    private static long[] ensureLong(long[] buffer, int size) {
        return buffer.length >= size ? buffer : new long[size];
    }

    private static boolean[] ensureBoolean(boolean[] buffer, int size) {
        return buffer.length >= size ? buffer : new boolean[size];
    }

    private static RotationalPower[] ensurePower(RotationalPower[] buffer, int size) {
        if (buffer.length >= size) {
            return buffer;
        }
        final RotationalPower[] grown = new RotationalPower[size];
        System.arraycopy(buffer, 0, grown, 0, buffer.length);
        for (int i = buffer.length; i < size; i++) {
            grown[i] = RotationalPower.fromRaw(0, 0);
        }
        return grown;
    }

    /**
     * Сортировка индексов по глубине без боксинга: вставками для малых групп,
     * быстрая сортировка для больших. Ноль аллокаций в тике.
     *
     * @param order  перестановка индексов 0..n-1 (мутируется на месте)
     * @param depth  глубина узла
     * @param n      число узлов
     * @param ascending true — от корней к листьям, false — от листьев к корням
     */
    private static void sortByDepth(int[] order, int[] depth, int n, boolean ascending) {
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (n < 2) {
            return;
        }
        quickSortByDepth(order, depth, 0, n - 1, ascending);
        // Вставки добивают почти отсортированные хвосты (типичный DAG)
        for (int i = 1; i < n; i++) {
            final int key = order[i];
            final int keyDepth = depth[key];
            int j = i - 1;
            while (j >= 0 && compareDepth(depth[order[j]], keyDepth, ascending) > 0) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
    }

    private static int compareDepth(int a, int b, boolean ascending) {
        return ascending ? Integer.compare(a, b) : Integer.compare(b, a);
    }

    private static void quickSortByDepth(int[] order, int[] depth, int lo, int hi, boolean ascending) {
        while (lo < hi) {
            // Медиана трёх как пивот — устойчиво на вырожденных глубинах
            final int mid = lo + ((hi - lo) >> 1);
            if (compareDepth(depth[order[mid]], depth[order[lo]], ascending) < 0) {
                swap(order, lo, mid);
            }
            if (compareDepth(depth[order[hi]], depth[order[lo]], ascending) < 0) {
                swap(order, lo, hi);
            }
            if (compareDepth(depth[order[hi]], depth[order[mid]], ascending) < 0) {
                swap(order, mid, hi);
            }
            final int pivot = depth[order[mid]];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (compareDepth(depth[order[i]], pivot, ascending) < 0) {
                    i++;
                }
                while (compareDepth(depth[order[j]], pivot, ascending) > 0) {
                    j--;
                }
                if (i <= j) {
                    swap(order, i, j);
                    i++;
                    j--;
                }
            }
            // Хвостовой вызов на меньшую половину — глубина стека O(log n)
            if (j - lo < hi - i) {
                if (lo < j) {
                    quickSortByDepth(order, depth, lo, j, ascending);
                }
                lo = i;
            } else {
                if (i < hi) {
                    quickSortByDepth(order, depth, i, hi, ascending);
                }
                hi = j;
            }
        }
    }

    private static void swap(int[] order, int a, int b) {
        final int tmp = order[a];
        order[a] = order[b];
        order[b] = tmp;
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
        this.groupId = INDEX_GENERATOR.getAndIncrement();
        ;
    }

    public long getGroupId() {
        return groupId;
    }

    public MechanicalMachine getMachine(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
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
                newGroup.machines[i].setGroupElementIndex(i);
            }
            // Новая сеть стартует с текущих оборотов и состояний машин:
            // тепло/SimulationState живут в машинах и переезжают сами,
            // скорость выбега — из текущей, а не с нуля.
            newGroup.currentSpeedRaw = this.currentSpeedRaw;
            newGroup.simTick = this.simTick;
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
     * Физический тик группы: древовидная симуляция передачи кинетической энергии.
     * <p>
     * Фаза A — граф мощности: BFS от источников по направленным рёбрам
     * output->input. Скорости ветвей считаются от ТЕКУЩИХ оборотов
     * сети (инерция: без мгновенных скачков).
     * Фаза B — динамика: обороты сети = интеграл момента
     * (PhysicsMath.tickSpeed): тяга источников против трения
     * и нагрузки потребителей.
     * Фаза B2 — demand снизу вверх: момент поддерева каждого узла.
     * Фаза C — состояния сверху вниз: перегруженная сеть — INSUFFICIENT_POWER
     * у потребителей, валы жёстко крутятся вместе с сетью.
     * Фаза D — заклинивание: ветка без питания блокирует цепь до источников
     * (JAMMED), хук onJam находит производителей.
     * <p>
     * Выполняется в потоке физики (см. PhysicsPipeline), не на серверном треде.
     */
    public void computeTick() {
        new TickDriver().run();
    }

    /**
     * Состояние одного тика: все scratch-буферы берутся из пула группы,
     * живут только внутри тика. Вынесено из computeTick(), чтобы фазы
     * читались по отдельности, а не одной простынёй на 500 строк.
     */
    private final class TickDriver {
        final MechanicalMachine[] machines = MechanicalGroup.this.machines;
        final int n = size;
        final List<PhysicsHook> hooks = PhysicsHooks.getHooks();
        final int[] parent;
        final int[] depth;
        final long[] subtreeTorque;
        final WorkState[] states;
        final RotationalPower[] inputPower;
        final boolean[] hasPower;
        final int[] order;
        final int[] queue;
        final int[] edgeFrom;
        final int[] edgeTo;
        final long[] edgeSpeed;
        final long[] edgeTorque;
        final boolean[] conflict;
        final boolean[] jammed;
        int edgeCount;
        boolean directionJam;
        boolean boggingDown;

        TickDriver() {
            parent = parentBuf = ensureInt(parentBuf, n);
            depth = depthBuf = ensureInt(depthBuf, n);
            subtreeTorque = subtreeTorqueBuf = ensureLong(subtreeTorqueBuf, n);
            states = stateBuf = ensureObjects(stateBuf, n, WorkState[]::new);
            inputPower = inputPowerBuf = ensurePower(inputPowerBuf, n);
            hasPower = hasPowerBuf = ensureBoolean(hasPowerBuf, n);
            order = orderBuf = ensureInt(orderBuf, n);
            queue = queueBuf = ensureInt(queueBuf, n > 0 ? n : 1);
            final int maxEdges = n > 1 ? n * (n - 1) : 1;
            edgeFrom = edgeFromBuf = ensureInt(edgeFromBuf, maxEdges);
            edgeTo = edgeToBuf = ensureInt(edgeToBuf, maxEdges);
            edgeSpeed = edgeSpeedBuf = ensureLong(edgeSpeedBuf, maxEdges);
            edgeTorque = edgeTorqueBuf = ensureLong(edgeTorqueBuf, maxEdges);
            conflict = conflictBuf = ensureBoolean(conflictBuf, n);
            jammed = jammedBuf = ensureBoolean(jammedBuf, n);
        }

        void run() {
            simContext.setTick(++simTick);
            for (int h = 0; h < hooks.size(); h++) {
                hooks.get(h).onGroupTickStart(thisGroup(), simContext);
            }

            Arrays.fill(parent, 0, n, -1);
            Arrays.fill(hasPower, 0, n, false);
            Arrays.fill(depth, 0, n, 0);
            Arrays.fill(conflict, 0, n, false);
            Arrays.fill(jammed, 0, n, false);
            edgeCount = 0;
            queueHead = 0;
            queueTail = 0;

            buildPosIndex();
            phaseA_powerGraph();
            phaseB_dynamics();
            phaseB2_demand();
            phaseC_states();
            phaseD_jam();

            for (int i = 0; i < n; i++) {
                final WorkState state = states[i] != null ? states[i] : WorkState.IDLE;
                machines[i].setWorkState(state);
                if (state == WorkState.INSUFFICIENT_POWER || state == WorkState.JAMMED) {
                    machines[i].getSimulationState().countOverloadTick();
                }
            }

            for (int h = 0; h < hooks.size(); h++) {
                hooks.get(h).onGroupTickEnd(thisGroup(), simContext);
            }
        }

        private MechanicalGroup thisGroup() {
            return MechanicalGroup.this;
        }

        private void buildPosIndex() {
            // --- Пространственный индекс: позиция блока -> индекс машины ---
            // Строится за O(n) в начале тика; BFS фазы A идёт по 6 соседям
            // за O(1) вместо скана всех n. Без позиции — пропускаем
            // (headless-машины вне мира участвуют только как источники/сток).
            final Long2IntMap posToIndex = posToIndexBuf;
            posToIndex.clear();
            for (int i = 0; i < n; i++) {
                final BlockPos pos = machines[i].getBlockPos();
                if (pos != null) {
                    posToIndex.putIfAbsent(pos.asLong(), i);
                }
            }
        }

        /**
         * Фаза A — граф мощности: BFS от источников по направленным рёбрам
         * output->input. Скорости ветвей считаются от ТЕКУЩИХ оборотов
         * сети (инерция: без мгновенных скачков).
         */
        private void phaseA_powerGraph() {
            // Скорость источника = ТЕКУЩИЕ обороты сети (инерция: без скачков).
            for (int i = 0; i < n; i++) {
            RotationalPower output = machines[i].getOutput();
            if (output == null) {
                continue;
            }

            // Тик источника: потери -> тепло, тепловой derate момента
            machines[i].onSourceTick(PhysicsMath.watts(output.getTorqueRaw(), currentSpeedRaw));

            // Хук: ослабление источника (износ, топливо, температура)
            for (int h = 0; h < hooks.size(); h++) {
                output = hooks.get(h).onSourceOutput(machines[i], output, simContext);
            }

            if (!hasPower[i]) {
                hasPower[i] = true;
            }
            inputPower[i].copyFrom(output);
            // Тепловой derate: перегретый источник режет паспортный момент
            inputPower[i].setTorqueRaw(
                    Math.round(output.getTorqueRaw() * machines[i].getOutputFactor()));
            inputPower[i].setSpeedRaw(currentSpeedRaw);
            queue[queueTail++] = i;
        }

        while (queueHead < queueTail) {
            final int i = queue[queueHead++];
            final MechanicalMachine from = machines[i];
            final BlockPos fromPos = from.getBlockPos();

            // Нет позиции — соседей не найти: машина участвует только
            // своим источником/стоком (см. индекс выше).
            if (fromPos == null) {
                continue;
            }

            for (int d = 0; d < NEIGHBOR_DIRS.length; d++) {
                final Direction dir = NEIGHBOR_DIRS[d];
                final BlockPos neighborPos = fromPos.relative(dir);
                if (!posToIndexBuf.containsKey(neighborPos.asLong())) {
                    continue;
                }
                final int j = posToIndexBuf.get(neighborPos.asLong());
                // Уровневая фильтрация DAG: энергия течёт только НА СЛЕДУЮЩИЙ
                // уровень. Обратное ребро (вал передаёт назад уже питаемому узлу)
                // удваивало бы мощность — отсекаем.
                if (hasPower[j] && depth[j] != depth[i] + 1) {
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
                applyEfficiency(from, edgeScratch);
                RotationalPower in = edgeScratch;
                for (int h = 0; h < hooks.size(); h++) {
                    in = hooks.get(h).onTransmit(from, machines[j], in, simContext);
                }
                for (int h = 0; h < hooks.size(); h++) {
                    in = hooks.get(h).onReceive(machines[j], in, simContext);
                }

                if (!hasPower[j]) {
                    // Первое обнаружение j: сброс stale-значений пула прошлого
                    // тика, иначе слияние ниже сложится с мусором.
                    hasPower[j] = true;
                    inputPower[j].setTorqueRaw(0);
                    inputPower[j].setSpeedRaw(0);
                    inputPower[j].setDirection(in.getDirection());
                    depth[j] = depth[i] + 1;
                    parent[j] = i;
                    queue[queueTail++] = j;
                }

                // Слияние входов (узел может питаться от нескольких родителей):
                // обороты — max, момент — ЗНАКОВАЯ сумма: встречные источники
                // ГАСЯТ друг друга (момент уходит в тепло узла), а не складываются.
                // Направление узла задаёт первый родитель; более сильный
                // встречный поток переориентирует узел.
                if (inputPower[j].getDirection() == in.getDirection()) {
                    if (in.getSpeedRaw() > inputPower[j].getSpeedRaw()) {
                        inputPower[j].setSpeedRaw(in.getSpeedRaw());
                    }
                    inputPower[j].plus(0, in.getTorqueRaw());
                } else {
                    // КОНФЛИКТ НАПРАВЛЕНИЙ (см. док, «Перегруз», случай 3)
                    inputPower[j].plus(0, -in.getTorqueRaw());
                    if (inputPower[j].getTorqueRaw() < 0) {
                        inputPower[j].setDirection(in.getDirection());
                        inputPower[j].setTorqueRaw(-inputPower[j].getTorqueRaw());
                        if (in.getSpeedRaw() > inputPower[j].getSpeedRaw()) {
                            inputPower[j].setSpeedRaw(in.getSpeedRaw());
                        }
                    }
                    conflict[j] = true;
                }

                edgeFrom[edgeCount] = i;
                edgeTo[edgeCount] = j;
                edgeSpeed[edgeCount] = in.getSpeedRaw();
                edgeTorque[edgeCount] = in.getTorqueRaw();
                edgeCount++;
            }
        }

        // Гистерезис конфликта направлений: клин только при N тиках подряд
        boolean hasConflict = false;
        for (int i = 0; i < n; i++) {
            if (conflict[i]) {
                hasConflict = true;
                break;
            }
        }
        directionConflictTicks = hasConflict ? directionConflictTicks + 1 : 0;
        directionJam = directionConflictTicks >= DIRECTION_JAM_TICKS;
        }

        /**
         * Фаза B — динамика: обороты сети = интеграл момента
         * (PhysicsMath.tickSpeed): тяга источников против трения
         * и нагрузки потребителей.
         */
        private void phaseB_dynamics() {
            long targetSpeedRaw = 0;
            long sourceTorqueRaw = 0;
            boolean hasSource = false;
            // Опорное направление сети = направление первого источника:
            // встречные источники ВЫЧИТАЮТСЯ из тяги (конфликт направлений)
            byte refDirection = -1;
            double totalInertia = 0;
            long frictionTorque = 0;
            long loadTorque = 0;

            for (int i = 0; i < n; i++) {
                final MechanicalMachine machine = machines[i];
                final RotationalPower in = inputPower[i];

                // Трение и инерция — только у ВРАЩАЮЩЕЙСЯ механики: машины
                // с питанием сейчас или ещё выбегающие по инерции. Мёртвые
                // ветви (за потребителем, куда мощность не доходит) сеть
                // не нагружают — они механически с ней не связаны.
                if (hasPower[i] || machine.getReceived().getSpeedRaw() > 0) {
                    totalInertia += machine.getInertia() + machine.getExtraInertia();
                    final long friction = machine.getFrictionTorque(currentSpeedRaw);
                    frictionTorque += friction;
                    // Симуляционный тик: трение греет, конвекция остужает
                    machine.onSimulationTick(friction, currentSpeedRaw);
                }

                if (hasPower[i]) {
                    final RotationalPower output = machine.getOutput();
                    if (output != null) {
                        hasSource = true;
                        // Цель сети — паспортные обороты источника; тяга — DERATED
                        // вход (фаза A урезала момент по температуре: перегретый
                        // генератор реально слабее)
                        if (output.getSpeedRaw() > targetSpeedRaw) {
                            targetSpeedRaw = output.getSpeedRaw();
                        }
                        if (refDirection == -1) {
                            refDirection = in.getDirection();
                        }
                        // Знаковая сумма: встречный источник гасит тягу
                        if (in.getDirection() == refDirection) {
                            sourceTorqueRaw += in.getTorqueRaw();
                        } else {
                            sourceTorqueRaw -= in.getTorqueRaw();
                        }
                    } else if (currentSpeedRaw >= machine.getRequired().getSpeedRaw()
                            || machine.getWorkState() == WorkState.JAMMED) {
                        // Потребитель, чьи обороты достаточны, нагружает сеть.
                        // Заклинившая давит СТРАГИВАНИЕМ (статическое трение
                        // заклиненного механизма) — клин не даёт раскрутиться.
                        loadTorque += Math.max(
                                machine.getRequired().getTorqueRaw(),
                                machine.getBreakawayTorqueRaw());
                    }
                    // Холостой ход: вращающаяся машина ест момент даже без
                    // полезной работы (трение рабочего органа, вентиляция)
                    if (currentSpeedRaw > 0) {
                        loadTorque += machine.getIdleTorqueRaw();
                    }
                }
            }

            final long netTorque = (hasSource ? sourceTorqueRaw : 0) - frictionTorque - loadTorque;
            // boggingDown = сеть не тянет (момент отрицателен). Разделяем два случая:
            // жёсткая перегрузка — мгновенный стоп ниже; маргинальный дефицит —
            // плавная пила. Флаг переживает фазу для B2 (поле драйвера).
            boggingDown = hasSource && netTorque < 0;
            // Жёсткая перегрузка: потребители требуют момента БОЛЬШЕ, чем источники
            // дают в принципе (ещё без трения) — вал упирается и мгновенно клинит.
            final boolean infeasible = hasSource && loadTorque > sourceTorqueRaw;

            if (infeasible) {
                currentSpeedRaw = 0;
            } else {
                // Маргинальный дефицит (трение съело запас на высоких оборотах):
                // плавное торможение — скорость сползает ниже порога потребителя,
                // тот отключается, и сеть выходит на пилу вокруг порога.
                // Мгновенное обнуление здесь давало бы вечный цикл
                // разгон -> порог -> ноль -> разгон.
                currentSpeedRaw = PhysicsMath.tickSpeed(
                        currentSpeedRaw, targetSpeedRaw, netTorque, (long) Math.max(1.0, totalInertia));
            }
        }

        /**
         * Фаза B2 — demand снизу вверх (по глубине, от листьев):
         * момент поддерева каждого узла. Нужна для freePower/leaf power
         * и для проверки перегруза источников.
         */
        private void phaseB2_demand() {
            sortByDepth(order, depth, n, false);

            for (int idx = 0; idx < n; idx++) {
            final int m = order[idx];

            if (!hasPower[m]) {
                subtreeTorque[m] = 0;
                continue;
            }
            final RotationalPower in = inputPower[m];

            final RotationalPower required = machines[m].getRequired();

            // Своя потребность на входе машины. При перегрузе (boggingDown)
            // заклинившая машина давит ПОЛНЫМ весом независимо от оборотов.
            final long own = boggingDown
                    ? required.getTorqueRaw()
                    : (in.getSpeedRaw() >= required.getSpeedRaw()
                    ? required.getTorqueRaw() : 0);

            // Требования детей снимаются с ВЫХОДА узла и переводятся на вход:
            // t_in = t_out * (s_out / s_in) — сохранение мощности через transform.
            long childrenOut = 0;
            for (int e = 0; e < edgeCount; e++) {
                if (edgeFrom[e] == m && hasPower[edgeTo[e]]) {
                    childrenOut += subtreeTorque[edgeTo[e]];
                }
            }

            subtreeTorque[m] = own + childrenOut;
            }
        }

        /**
         * Фаза C — состояния сверху вниз (по глубине, от корней):
         * перегруженная сеть — INSUFFICIENT_POWER у потребителей,
         * валы жёстко крутятся вместе с сетью.
         */
        private void phaseC_states() {
            sortByDepth(order, depth, n, true);

            for (int idx = 0; idx < n; idx++) {
                final int m = order[idx];
                final MechanicalMachine machine = machines[m];

                if (!hasPower[m]) {
                    // Недостижима от источников: машина не powered.
                    // Если раньше крутилась (received > 0) — выбегает по инерции,
                    // замедляясь трением своего материала. Если никогда не
                    // получала мощность — стоит на месте.
                    final long lastSpeed = machine.getReceived().getSpeedRaw();
                    if (lastSpeed > 0) {
                        final long friction = machine.getFrictionTorque(lastSpeed);
                        final long inertia = Math.max(1L, (long) machine.getInertia());
                        final long decel = PhysicsMath.accelStep(friction, inertia);
                        final long coasted = Math.max(0, lastSpeed - decel);
                        machine.getReceived().setSpeedRaw(coasted);
                        machine.getReceived().setTorqueRaw(0);
                        states[m] = coasted > 0 ? WorkState.WORKING : WorkState.IDLE;
                    } else {
                        machine.getReceived().reset();
                        states[m] = WorkState.IDLE;
                    }
                    machine.setFreePower(0);
                    continue;
                }

                final RotationalPower in = inputPower[m];
                machine.setReceived(in);

                // Leaf power: received минус требования детей (перевод в ватты
                // по скорости каждого ребра — у раздатки они разные)
                long childrenWatts = 0;
                for (int e = 0; e < edgeCount; e++) {
                    if (edgeFrom[e] == m && hasPower[edgeTo[e]]) {
                        childrenWatts += PhysicsMath.watts(subtreeTorque[edgeTo[e]], edgeSpeed[e]);
                    }
                }
                final long freeWatts = Math.max(0, in.getPower() - childrenWatts);
                machine.setFreePower(freeWatts);

                // Показатели симуляции: мгновенная мощность тика
                machine.getSimulationState().setTickPower(in.getPower(), childrenWatts, freeWatts);

                // Балансовый хук (маховик и др. буферы)
                machine.onNetworkTick(in.getPower(), childrenWatts);

                // Ветвь выше мертва (все родители без питания) => здесь тоже нет питания
                boolean hasParent = false;
                boolean allParentsDead = true;
                for (int e = 0; e < edgeCount; e++) {
                    if (edgeTo[e] == m) {
                        hasParent = true;
                        final WorkState ps = states[edgeFrom[e]];
                        // Мёртв только родитель без питания (IDLE). Перегруженный
                        // (INSUFFICIENT_POWER) родитель всё ещё вращает вал — дети
                        // обязаны оценить свою достаточность сами, иначе перегруз
                        // не дойдёт до потребителя и клин не сработает.
                        if (ps != WorkState.IDLE) {
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
                final RotationalPower required = machine.getRequired();

                // Клин держится, пока потребность машины (полезная или момент
                // страгивания) не удовлетворима: скорость обнулилась, но источник
                // продолжает давить моментом меньше требуемого — машина остаётся
                // перегруженной, и фаза D заклинивает её заново (иначе клин
                // стирался бы через тик).
                final long holdTorque = Math.max(required.getTorqueRaw(), machine.getBreakawayTorqueRaw());
                if (machine.getWorkState() == WorkState.JAMMED
                        && holdTorque > 0
                        && in.getTorqueRaw() < holdTorque) {
                    states[m] = WorkState.INSUFFICIENT_POWER;
                    continue;
                }

                final byte requiredDirection = machine.getRequiredDirection();
                if (requiredDirection != -1 && in.getDirection() != requiredDirection) {
                    states[m] = WorkState.WRONG_DIRECTION;
                    continue;
                }

                // Страгивание: входного момента меньше нужного для троганья —
                // машина не стартует и клинит цепь (фаза D разносит клин вверх)
                if (machine.getBreakawayTorqueRaw() > 0
                        && in.getTorqueRaw() < machine.getBreakawayTorqueRaw()) {
                    states[m] = WorkState.INSUFFICIENT_POWER;
                    continue;
                }

                if (in.getSpeedRaw() < required.getSpeedRaw()) {
                    states[m] = WorkState.IDLE;
                    continue;
                }

                // Своя потребность + требования детей уже сведены к входной стороне
                // в фазе B2 (с вычетом подпитки от других родителей при слиянии).
                // Машина-буфер (маховик) покрывает дефицит из запаса — работает,
                // пока резерв есть.
                // Пассивные сегменты (валы) жёстко сцеплены с сетью и вращаются
                // вместе с ней: перегрузка потребителя не "останавливает" сегмент
                // передачи — фейлится только машина с собственной потребностью.
                if (subtreeTorque[m] <= in.getTorqueRaw()
                        || (machine.coversDeficitFromBuffer() && machine.hasBufferReserve())
                        || machine.isPassive()) {
                    states[m] = WorkState.WORKING;
                } else {
                    states[m] = WorkState.INSUFFICIENT_POWER;
                }
            }
        }

        /**
         * Фаза D — заклинивание (жёсткая сцепка): потребителю не хватило
         * момента -> он блокируется и тащит за собой ВСЮ цепь вверх
         * до источников. Хук onJam находит производителей.
         */
        private void phaseD_jam() {
            // jammed уже обнулён в run(); списки переиспользуются.
            final List<MechanicalMachine> jammedMachines = jammedMachinesBuf;
            jammedMachines.clear();
            final List<MechanicalMachine> jammedProducers = jammedProducersBuf;
            jammedProducers.clear();

            for (int i = 0; i < n; i++) {
                if (hasPower[i] && states[i] == WorkState.INSUFFICIENT_POWER) {
                    jammed[i] = true;
                    states[i] = WorkState.JAMMED;
                    jammedMachines.add(machines[i]);
                }
            }

            // Конфликт направлений держится N тиков подряд — сеть встаёт клином
            // целиком (встречные потоки связывают механику, энергия уходит в тепло)
            if (directionJam) {
                for (int i = 0; i < n; i++) {
                    if (hasPower[i] && !jammed[i]) {
                        jammed[i] = true;
                        states[i] = WorkState.JAMMED;
                        jammedMachines.add(machines[i]);
                    }
                }
            }

            // Распространение вверх (order = depth asc, идём с конца: дети раньше
            // родителей): заклинивший узел блокирует всех своих родителей.
            for (int idx = n - 1; idx >= 0; idx--) {
                final int c = order[idx];
                if (!jammed[c]) {
                    continue;
                }

                for (int e = 0; e < edgeCount; e++) {
                    if (edgeTo[e] != c || !hasPower[edgeFrom[e]]) {
                        continue;
                    }
                    final int p = edgeFrom[e];
                    if (!jammed[p]) {
                        jammed[p] = true;
                        states[p] = WorkState.JAMMED;
                        jammedMachines.add(machines[p]);
                    }
                }
            }

            // Производители заклинившей сети (для хуков: реакция на клин)
            if (!jammedMachines.isEmpty()) {
                for (int i = 0; i < n; i++) {
                    if (hasPower[i] && machines[i].getOutput() != null) {
                        jammedProducers.add(machines[i]);
                    }
                }

                for (int h = 0; h < hooks.size(); h++) {
                    hooks.get(h).onJam(thisGroup(), jammedMachines, jammedProducers, simContext);
                }
            }
        }
    }

    /**
     * КПД передачи через машину: момент на выходе умножается на η,
     * потерянная мощность (1-η) уходит в тепло узла
     * (P_loss = P_in · (1-η), за тик E += P_loss / 20).
     */
    private static void applyEfficiency(MechanicalMachine from, RotationalPower edge) {
        final double eta = from.getEfficiency();
        if (eta >= 1.0) {
            return;
        }
        final long edgeWatts = PhysicsMath.watts(edge.getTorqueRaw(), edge.getSpeedRaw());
        final long lossWatts = Math.round(edgeWatts * (1.0 - eta));
        edge.setTorqueRaw(Math.round(edge.getTorqueRaw() * eta));
        if (lossWatts > 0) {
            from.getSimulationState().addHeatJ(lossWatts / 20.0);
        }
    }
}
