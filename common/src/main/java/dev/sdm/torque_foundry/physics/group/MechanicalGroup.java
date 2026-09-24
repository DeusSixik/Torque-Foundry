package dev.sdm.torque_foundry.physics.group;


import dev.sdm.torque_foundry.api.events.physics.PhysicsEventDispatcher;
import dev.sdm.torque_foundry.api.physics.GroupSnapshotView;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.PhysicsMath;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.hook.PhysicsHook;
import dev.sdm.torque_foundry.physics.hook.PhysicsHooks;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.SimulationState;
import dev.sdm.torque_foundry.physics.simulation.GroupSnapshot;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

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
     * Обороты сети, milli-RPM (публикация снапшота; живое значение).
     */
    public long getNetSpeedRaw() {
        return currentSpeedRaw;
    }

    /**
     * Счётчик физических тиков группы (id публикуемого снапшота).
     */
    public long getSimTick() {
        return simTick;
    }

    /**
     * Группа в клине (с прошлого тика). Читается только в треде владельца
     * (воркер физики / GroupTask) — на серверном треде значение может быть
     * устаревшим на тик (см. I6).
     */
    public boolean isJammed() {
        return jammedState;
    }

    /**
     * Текущие обороты сети в milli-RPM (для тестов).
     */
    public long currentSpeedForTest() {
        return currentSpeedRaw;
    }

    /**
     * Приведённая к базовому уровню инерция сети с последнего физического
     * тика (фаза B). Нужна слиянию сетей: формула неупругого удара верна
     * ровно настолько, насколько верны инерции обеих сетей (раздел 11.1
     * документа физики). Значение устаревает на тик — для событий топологии
     * это верх границы погрешности; инерция только что добавленного узла
     * стыка учитывается слиянием отдельно.
     */
    protected double lastReducedInertia;

    /**
     * Приведённая инерция сети (для тестов слияния).
     */
    public double reducedInertiaForTest() {
        return lastReducedInertia;
    }

    /**
     * Перенапряжение узла с последнего тика (для тестов контуров).
     */
    public boolean overstrainForTest(int index) {
        return index >= 0 && index < overstrainBuf.length && overstrainBuf[index];
    }

    // --- Scratch-буферы тика (mutable-архитектура: ноль аллокаций в тике) ---
    // Переиспользуются между тиками, растут при росте группы.
    private int[] parentBuf = new int[0];
    private int[] depthBuf = new int[0];
    private long[] subtreeTorqueBuf = new long[0];
    private long[] capTorqueBuf = new long[0];
    private double[] subtreeInertiaBuf = new double[0];
    private double[] uNodeBuf = new double[0];
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
    private boolean[] edgeConnectionBuf = new boolean[0];
    private boolean[] overstrainBuf = new boolean[0];
    private long[] ratioNumBuf = new long[0];
    private long[] ratioDenBuf = new long[0];
    private boolean[] jammedBuf = new boolean[0];
    private boolean[] conflictBuf = new boolean[0];
    private final Long2IntMap posToIndexBuf = new Long2IntOpenHashMap();
    /**
     * Направления соседей (кеш Direction.values(): values() клонирует массив).
     */
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

    /**
     * Группа в клине (для событий JAMMED/UNJAMMED: переходы, не каждый тик).
     */
    private boolean jammedState;

    // --- Снапшот (протокол «Concurrency in Torque Foundry.md», §4) ---

    /**
     * Замок публикации снапшота. Пишет только владелец группы (воркер)
     * в конце тика; читают серверный тред (блокирующе) и рендер (tryLock).
     * Держится микросекунды (копия плоских массивов) — contention нет.
     */
    private final ReentrantLock snapshotLock = new ReentrantLock();

    /**
     * Опубликованный срез («front»). Пишет только владелец под замком.
     */
    private final GroupSnapshot front = new GroupSnapshot();

    private static int[] ensureInt(int[] buffer, int size) {
        return buffer.length >= size ? buffer : new int[size];
    }

    private static long[] ensureLong(long[] buffer, int size) {
        return buffer.length >= size ? buffer : new long[size];
    }

    private static boolean[] ensureBoolean(boolean[] buffer, int size) {
        return buffer.length >= size ? buffer : new boolean[size];
    }

    private static double[] ensureDouble(double[] buffer, int size) {
        return buffer.length >= size ? buffer : new double[size];
    }

    /** НОД: сокращение дробей отношений контуров (раздел 11.2). */
    private static long gcd(long a, long b) {
        while (b != 0) {
            final long t = a % b;
            a = b;
            b = t;
        }
        return a;
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
     * @param order     перестановка индексов 0..n-1 (мутируется на месте)
     * @param depth     глубина узла
     * @param n         число узлов
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

    /**
     * Скоростное отношение ребра: u = s_выход / s_вход. Понижающая ступень
     * (момент x4, обороты /4) даёт u = 0.25. При нулевой скорости сети
     * (старт/клин) отношение скоростей не определено (0/0) — принимается
     * единичным; пороги и нагрузки на стоячей сети всё равно не достигаются.
     *
     * @param childSpeedRaw скорость на стороне ребёнка, milli-RPM
     * @param inputSpeedRaw скорость на входе узла, milli-RPM
     * @return отношение скоростей ребра, &gt; 0
     */
    private static double childRatio(long childSpeedRaw, long inputSpeedRaw) {
        return inputSpeedRaw <= 0 ? 1.0 : (double) childSpeedRaw / inputSpeedRaw;
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

    /**
     * Слияние сетей как НЕУПРУГИЙ УДАР (С1 п.4, раздел 11.1): жёсткое
     * соединение сохраняет момент импульса, но не энергию — разница уходит
     * теплом в узел стыка. Ставить блок между двумя крутящимися линиями
     * больше не создаёт и не уничтожает энергию фактом установки.
     *
     * <p>Реализует расчет по формулам:
     * <pre>
     *   ω   = (J₁·ω₁ + J₂·ω₂·d) / (J₁ + J₂)
     *   ΔQ  = E(J₁,ω₁) + E(J₂,ω₂) − E(J₁+J₂, ω)
     *   E(J,ω) = (J·ω²)/2, ω в рад/с
     * </pre>
     *
     * <p>Где:
     * <ul>
     *   <li><b>J₁, J₂</b> — приведённые инерции сетей с последних тиков
     *       (плюс паспортная инерция самого узла стыка);</li>
     *   <li><b>ω₁, ω₂</b> — обороты сетей, milli-RPM;</li>
     *   <li><b>d</b> — знак направления: +1 со-вращение, −1 встречное
     *       вращение (по направлениям машин-соседей у стыка);</li>
     *   <li><b>ΔQ</b> — разница кинетической энергии, Дж: греет узел стыка
     *       (его зубья/штифт/накладки). Встречное вращение с равными
     *       импульсами сжигает в стыке ВСЮ кинетическую энергию.</li>
     * </ul>
     *
     * <p>Сопоставление с аргументами: {@code J₁/ω₁} — состояние этой группы,
     * {@code J₂/ω₂} — {@code otherGroup}; {@code joint} — узел стыка;
     * {@code hostNeighbor}/{@code guestNeighbor} — машины-соседи стыка по
     * обе стороны (для знака d).
     *
     * <p>Тредовый контракт: вызов из серверного треда (событие топологии).
     * Запас тепла в узел стыка — одно двойное сложение поверх физического
     * тика; потеря параллельного инкремента тепла за тик — приемлемая цена.
     *
     * @param otherGroup    вливаемая группа; после слияния пуста
     * @param joint         машина-узел стыка (новый блок), уже в этой группе
     * @param hostNeighbor  сосед стыка со стороны этой группы, может быть null
     * @param guestNeighbor сосед стыка со стороны вливаемой группы, может быть null
     */
    public void merge(@NotNull MechanicalGroup otherGroup, @NotNull MechanicalMachine joint,
                      @Nullable MechanicalMachine hostNeighbor, @Nullable MechanicalMachine guestNeighbor) {
        if (otherGroup == this || otherGroup.size == 0) {
            return;
        }

        final double j1 = Math.max(1.0, lastReducedInertia) + Math.max(0.0, joint.getInertia());
        final double j2 = Math.max(1.0, otherGroup.lastReducedInertia);
        final double w1 = this.currentSpeedRaw;
        final double w2 = otherGroup.currentSpeedRaw;

        // Знак по направлениям машин-соседей: одинаковый байт — со-вращение,
        // разный — встречное вращение (отрицательный член суммы импульса)
        final byte dHost = hostNeighbor != null ? hostNeighbor.getReceived().getDirection() : 0;
        final byte dGuest = guestNeighbor != null ? guestNeighbor.getReceived().getDirection() : 0;
        final double sign = dHost == dGuest ? 1.0 : -1.0;

        final double momentum = j1 * w1 + sign * j2 * w2;
        final double jTotal = j1 + j2;
        final double newSpeed = Math.abs(momentum) / jTotal;

        // Разница кинетической энергии — в тепло узла стыка (Дж)
        final double deltaQ = PhysicsMath.kineticEnergyNetworkJ(j1, w1)
                + PhysicsMath.kineticEnergyNetworkJ(j2, w2)
                - PhysicsMath.kineticEnergyNetworkJ(jTotal, newSpeed);
        if (deltaQ > 0) {
            joint.getSimulationState().addHeatJ(deltaQ);
        }

        // Перенос машин (прежняя механика) уже с новой скоростью сети
        merge(otherGroup);
        this.currentSpeedRaw = Math.round(newSpeed);
        lastReducedInertia = jTotal;
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
     * Фаза B2 — demand снизу вверх: стоимость поддерева, приведённая
     * к входной стороне узла (t_in = t_out · s_out / s_in / η).
     * Фаза B3 — делёж входного момента по спросу выходов: момент
     * делится, а не копируется; КПД применяется один раз за узел.
     * Фаза C — состояния сверху вниз: перегруженная сеть — INSUFFICIENT_POWER
     * у потребителей, валы жёстко крутятся вместе с сетью.
     * Фаза D — заклинивание: ветка без питания блокирует цепь до источников
     * (JAMMED), хук onJam находит производителей.
     * <p>
     * Выполняется в потоке физики (см. PhysicsPipeline), не на серверном треде.
     */
    public void computeTick() {
        new TickDriver().run();
        publishSnapshot();
    }

    /**
     * Публикация снапшота: копия живого состояния группы в front под замком.
     * Вызывается владельцем (воркером) в конце тика; читатели получают
     * согласованный срез через {@link #copySnapshotTo}. Фазы A–D остаются
     * lock-free — замок берётся один раз после них.
     */
    private void publishSnapshot() {
        snapshotLock.lock();
        try {
            front.publishFrom(this);
        } finally {
            snapshotLock.unlock();
        }
    }

    /**
     * Тест-хук: держит snapshotLock, пока тест не разрешит продолжение.
     * Проверяет неблокирующий путь читателя (tryLock при занятом замке).
     *
     * @param locked  замок захвачен — тест может проверять отказ
     * @param release отпустить замок
     */
    public void holdSnapshotLockForTest(CountDownLatch locked, CountDownLatch release) {
        snapshotLock.lock();
        try {
            locked.countDown();
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            snapshotLock.unlock();
        }
    }

    /**
     * Копия последнего опубликованного снапшота в буфер читателя.
     * Публичное чтение — только через {@code PhysicsReads}; метод открыт
     * пакету фасада (api.physics вызывает его из статических методов).
     *
     * @param into  буфер читателя (переиспользуемый, растёт при росте группы)
     * @param block true — ждать освобождения замка; false — tryLock,
     *              занято → false, буфер читателя НЕ тронут (рендер-путь)
     * @return true — во view лежит согласованный срез ({@code tickId >= 0})
     */
    public boolean copySnapshotTo(GroupSnapshotView into, boolean block) {
        if (block) {
            snapshotLock.lock();
        } else if (!snapshotLock.tryLock()) {
            return false;
        }
        try {
            front.copyTo(into);
            return true;
        } finally {
            snapshotLock.unlock();
        }
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
        final long[] capTorque;
        final double[] subtreeInertia;
        final double[] uNode;
        final WorkState[] states;
        final RotationalPower[] inputPower;
        final boolean[] hasPower;
        final int[] order;
        final int[] queue;
        final int[] edgeFrom;
        final int[] edgeTo;
        final long[] edgeSpeed;
        final long[] edgeTorque;
        final boolean[] edgeConnection;
        final boolean[] overstrain;
        final long[] ratioNum;
        final long[] ratioDen;
        final boolean[] conflict;
        final boolean[] jammed;
        int edgeCount;
        boolean directionJam;
        boolean boggingDown;
        /** Целевые обороты сети (паспорт источника) — для тепла перенапряжения. */
        long targetSpeedRaw;

        TickDriver() {
            parent = parentBuf = ensureInt(parentBuf, n);
            depth = depthBuf = ensureInt(depthBuf, n);
            subtreeTorque = subtreeTorqueBuf = ensureLong(subtreeTorqueBuf, n);
            capTorque = capTorqueBuf = ensureLong(capTorqueBuf, n);
            subtreeInertia = subtreeInertiaBuf = ensureDouble(subtreeInertiaBuf, n);
            uNode = uNodeBuf = ensureDouble(uNodeBuf, n);
            ratioNum = ratioNumBuf = ensureLong(ratioNumBuf, n);
            ratioDen = ratioDenBuf = ensureLong(ratioDenBuf, n);
            overstrain = overstrainBuf = ensureBoolean(overstrainBuf, n);
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
            edgeConnection = edgeConnectionBuf = ensureBoolean(edgeConnectionBuf, maxEdges);
            conflict = conflictBuf = ensureBoolean(conflictBuf, n);
            jammed = jammedBuf = ensureBoolean(jammedBuf, n);
        }

        void run() {
            simContext.setTick(++simTick);
            for (int h = 0; h < hooks.size(); h++) {
                hooks.get(h).onGroupTickStart(thisGroup(), simContext);
            }
            PhysicsEventDispatcher.fireGroupTickStart(thisGroup(), simTick);

            Arrays.fill(parent, 0, n, -1);
            Arrays.fill(hasPower, 0, n, false);
            Arrays.fill(depth, 0, n, 0);
            Arrays.fill(conflict, 0, n, false);
            Arrays.fill(jammed, 0, n, false);
            Arrays.fill(overstrain, 0, n, false);
            edgeCount = 0;
            queueHead = 0;
            queueTail = 0;

            buildPosIndex();
            phaseA_powerGraph();
            phaseB_dynamics();
            phaseB2_demand();
            phaseB3_splitByDemand();
            phaseC_states();
            phaseD_jam();

            for (int i = 0; i < n; i++) {
                final WorkState state = states[i] != null ? states[i] : WorkState.IDLE;
                machines[i].setWorkState(state);
                if (state == WorkState.INSUFFICIENT_POWER || state == WorkState.JAMMED) {
                    machines[i].getSimulationState().countOverloadTick();
                }
            }

            // События переходов клина: только факт смены состояния,
            // не каждый тик (иначе спам на длинном клине).
            int jammedCount = 0;
            for (int i = 0; i < n; i++) {
                if (states[i] == WorkState.JAMMED) {
                    jammedCount++;
                }
            }
            final boolean jammedNow = jammedCount > 0;
            if (jammedNow && !jammedState) {
                PhysicsEventDispatcher.fireGroupJammed(thisGroup(), simTick, jammedCount);
            } else if (!jammedNow && jammedState) {
                PhysicsEventDispatcher.fireGroupUnjammed(thisGroup(), simTick);
            }
            jammedState = jammedNow;

            for (int h = 0; h < hooks.size(); h++) {
                hooks.get(h).onGroupTickEnd(thisGroup(), simContext);
            }
            // END — последним: состояние группы полностью финально
            // (фазы + запись состояний + переходы клина + хуки).
            PhysicsEventDispatcher.fireGroupTickEnd(thisGroup(), simTick);
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
                // Источник стоит на базовом уровне сети; дробь пути источника — 1/1
                uNode[i] = 1.0;
                ratioNum[i] = 1;
                ratioDen[i] = 1;
                inputPower[i].copyFrom(output);
                // Тепловой derate: перегретый источник режет паспортный момент
                inputPower[i].setTorqueRaw(
                        Math.round(output.getTorqueRaw() * machines[i].getOutputFactor()));
                inputPower[i].setSpeedRaw(currentSpeedRaw);

                // Клин и источники (раздел 11.3): сеть стоит клином, но
                // источник продолжает тянуть упор — момент давит в зубья
                // на паспортных оборотах и УХОДИТ В ТЕПЛО узла:
                //   Q += k · T_упора · ω_паспорта · Δt
                // k — из паспорта (heatsUpWhenStalled): мотор греется,
                // водяное колесо — нет. Без этого перегрев генератора от
                // клина не посчитать: мощность τ·ω на нулевой скорости ноль.
                if (currentSpeedRaw == 0 && jammedState && machines[i].heatsUpWhenStalled()) {
                    final double tauNm = inputPower[i].getTorqueRaw() / 1000.0;
                    final double omegaPassport =
                            output.getSpeedRaw() * 2.0 * Math.PI / 60000.0;
                    machines[i].getSimulationState().addHeatJ(tauNm * omegaPassport / 20.0);
                }

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

                // Битый узел не проводит: заклинившая от перегрева ступень и
                // обугленный ремень рвут поток здесь — дети остаются без питания
                // и выбегают по инерции (фаза C).
                if (from.getSimulationState().isBrokenByHeat()) {
                    continue;
                }

                for (int d = 0; d < NEIGHBOR_DIRS.length; d++) {
                    final Direction dir = NEIGHBOR_DIRS[d];
                    final BlockPos neighborPos = fromPos.relative(dir);
                    if (!posToIndexBuf.containsKey(neighborPos.asLong())) {
                        continue;
                    }
                    final int j = posToIndexBuf.get(neighborPos.asLong());
                    // Энергия идёт только по СТРОГОМУ направлению: выход -> встречный вход.
                    if (!MechanicalGroupManager.canTransferPower(from, machines[j], dir)) {
                        continue;
                    }

                    // --- Отношение ребра как точная дробь (раздел 11.2) ---
                    // Зубья целые и точные; скорости джиттерят от остатков
                    // деления — контур из взаимно простых зубцов по скоростям
                    // был бы признан несошедшимся, хотя сходится ровно.
                    final long[] uf = from.getOutputRatioFraction(dir);
                    final long un = uf != null ? uf[0] : 1;
                    final long ud = uf != null ? uf[1] : 1;

                    // Произведение отношений пути к j через это ребро:
                    // R_new = R_i · u, сокращение КРЕСТ-НАКРЕСТ перед
                    // умножением (числитель новой со знаменателем текущей и
                    // наоборот) — множители минимальны, переполнение остаётся
                    // только у контура, который и не сходится ни в какое
                    // разумное отношение; такой считается несогласованным.
                    final long g1 = gcd(ratioNum[i], ud);
                    final long g2 = gcd(un, ratioDen[i]);
                    final long n1 = ratioNum[i] / g1;
                    final long d2 = ud / g1;
                    final long n2 = un / g2;
                    final long d1 = ratioDen[i] / g2;
                    final boolean ratioOverflow =
                            n1 > Long.MAX_VALUE / n2 || d1 > Long.MAX_VALUE / d2;
                    final long rawNum = ratioOverflow ? 0 : n1 * n2;
                    final long rawDen = ratioOverflow ? 0 : d1 * d2;
                    final long g3 = ratioOverflow ? 1 : gcd(rawNum, rawDen);
                    final long newNum = rawNum / g3;
                    final long newDen = rawDen / g3;

                    // Мощность на этом ребре: изолированный скратч (хуки мутируют).
                    // Transform узла направленный: в сторону этого ребра
                    // (раздатка/коническая на разных выходах дают разное).
                    // БЕЗ КПД и БЕЗ дележа: ребро несёт кинематику (скорость,
                    // направление) и ПОТЕНЦИАЛ входа; фактический момент выход
                    // получает в фазе B3 по спросу, КПД применяется один раз
                    // за узел — иначе раздатка копировала бы полную мощность
                    // на каждый выход и теряла η на каждом ребре.
                    edgeScratch.copyFrom(inputPower[i]);
                    edgeScratch.copyFrom(from.transform(inputPower[i], dir));
                    RotationalPower in = edgeScratch;
                    for (int h = 0; h < hooks.size(); h++) {
                        in = hooks.get(h).onTransmit(from, machines[j], in, simContext);
                    }
                    for (int h = 0; h < hooks.size(); h++) {
                        in = hooks.get(h).onReceive(machines[j], in, simContext);
                    }

                    if (!hasPower[j]) {
                        if (ratioOverflow) {
                            // Дробь пути не влезает — контур не сходится
                            overstrain[j] = true;
                            continue;
                        }
                        // Первое обнаружение j: сброс stale-значений пула прошлого
                        // тика, иначе слияние ниже сложится с мусором.
                        hasPower[j] = true;
                        inputPower[j].setTorqueRaw(0);
                        inputPower[j].setSpeedRaw(0);
                        inputPower[j].setDirection(in.getDirection());
                        depth[j] = depth[i] + 1;
                        parent[j] = i;
                        // Скорость узла относительно базового уровня: произведение
                        // отношений по дереву обхода от источника
                        uNode[j] = uNode[i] * childRatio(in.getSpeedRaw(), inputPower[i].getSpeedRaw());
                        // Дробь отношения пути до j
                        ratioNum[j] = newNum;
                        ratioDen[j] = newDen;
                        queue[queueTail++] = j;
                    } else if (ratioOverflow || newNum != ratioNum[j] || newDen != ratioDen[j]) {
                        // Второй путь с ДРУГИМ произведением отношений: узел
                        // обязан крутиться с двумя скоростями сразу — это
                        // ПЕРЕНАПРЯЖЕНИЕ (11.2). Разность моментов греет узел
                        // и ломает зуб (фаза C), клин расходится по сети.
                        overstrain[j] = true;
                        continue;
                    } else if (depth[j] != depth[i] + 1) {
                        // Второй путь с тем же отношением, замыкающий КОЛЬЦО.
                        // Знак проверяется тем же проходом отдельно от величины:
                        // согласованный по величине и конфликтный по знаку —
                        // всё равно нагрузка на зуб (перенапряжение).
                        if (inputPower[j].getDirection() != in.getDirection()) {
                            overstrain[j] = true;
                            continue;
                        }
                        // Физически законен — одна скорость, записанная
                        // несколькими путями. Мощность по кольцу не течёт:
                        // ребро остаётся СВЯЗЬЮ (по нему расходится клин и
                        // виден разрыв) и выбрасывается из суммирования.
                        edgeFrom[edgeCount] = i;
                        edgeTo[edgeCount] = j;
                        edgeSpeed[edgeCount] = in.getSpeedRaw();
                        edgeTorque[edgeCount] = in.getTorqueRaw();
                        edgeConnection[edgeCount] = true;
                        edgeCount++;
                        continue;
                    }
                    // Второй путь на следующий уровень (depth[j] == depth[i]+1,
                    // согласованный) — обычное слияние ветвей: сумма ниже.

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
                    edgeConnection[edgeCount] = false;
                    // Потолок ребра: потенциал после transform и хуков, БЕЗ КПД
                    // и БЕЗ дележа. Фаза B3 выдаёт детям не больше этого потолка:
                    // через него же проявляется износ вала (ShaftWearHook).
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
         * и нагрузки потребителей. Инерция, трение, нагрузка и пороги
         * потребителей приводятся к базовому уровню (уровню источников)
         * через скоростные отношения u по дереву обхода: инерция — u²,
         * моменты — u.
         */
        private void phaseB_dynamics() {
            // --- Приведение инерции к базовому уровню (С1 п.3) ---
            // Снизу вверх по дереву обхода:
            //   J_привед = J_узла + Σ (J_поддерева_ребёнка · u_ребра²),
            // где u_ребра = s_ребёнка / s_узла — скоростное отношение ребра.
            // Энергия ½Jω²: узел, крутящийся медленнее базы в 4 раза
            // (понижающая ступень), вкладывает на базовом уровне в 16 раз
            // меньше — наивная сумма завышала инерцию медленной стороны
            // и занижала быструю. Только рёбра ДЕРЕВА обхода: узел со
            // слиянием входит один раз.
            sortByDepth(order, depth, n, false);
            for (int idx = 0; idx < n; idx++) {
                final int m = order[idx];
                if (!hasPower[m]) {
                    subtreeInertia[m] = 0;
                    continue;
                }
                final MechanicalMachine machine = machines[m];
                double j = machine.getInertia() + machine.getExtraInertia();
                final long sIn = inputPower[m].getSpeedRaw();
                for (int e = 0; e < edgeCount; e++) {
                    if (edgeFrom[e] == m && hasPower[edgeTo[e]] && parent[edgeTo[e]] == m) {
                        final double u = childRatio(edgeSpeed[e], sIn);
                        j += subtreeInertia[edgeTo[e]] * u * u;
                    }
                }
                subtreeInertia[m] = j;
            }

            targetSpeedRaw = 0;
            long sourceTorqueRaw = 0;
            boolean hasSource = false;
            // Опорное направление сети = направление первого источника:
            // встречные источники ВЫЧИТАЮТСЯ из тяги (конфликт направлений)
            byte refDirection = -1;
            double totalInertia = 0;
            long frictionTorque = 0;
            long loadTorque = 0;
            // Заклинившая от перегрева зубчатая ступень в питаемой части:
            // сеть не проворачивается, как при жёсткой перегрузке
            boolean brokenJam = false;

            for (int i = 0; i < n; i++) {
                final MechanicalMachine machine = machines[i];
                final RotationalPower in = inputPower[i];

                if (hasPower[i]
                        && machine.getSimulationState().isBrokenByHeat()
                        && machine.getHeatFailureMode() == MechanicalMachine.HeatFailureMode.JAM) {
                    brokenJam = true;
                }
                if (hasPower[i] && overstrain[i]) {
                    // Перенапряжённый контур: разность моментов заклинивает
                    // узел — сеть не проворачивается
                    brokenJam = true;
                }

                // Механически с сетью связаны только питаемые машины: мёртвые
                // ветви (за потребителем, куда мощность не доходит) сеть не
                // нагружают. Выбегающая машина (питание пропало) тормозит
                // сама — её трение в баланс сети не входит, но узел живёт:
                // остывает и изнашивается на своей скорости.
                if (hasPower[i]) {
                    // Инерция сети — сумма приведённых поддеревьев корней
                    if (parent[i] == -1) {
                        totalInertia += subtreeInertia[i];
                    }

                    // Узел крутится на своей скорости s = s_сети · u. Вязкое
                    // трение f·s на его стороне нагружает сеть сохранением
                    // мощности как f·s·u — квадрат отношения ослабляет трение
                    // медленной стороны и усиливает быстрой.
                    final double u = uNode[i];
                    final long machineSpeed = (long) (currentSpeedRaw * u);
                    final long friction = machine.getFrictionTorque(machineSpeed);
                    frictionTorque += (long) (friction * u);
                    // Симуляционный тик: трение греет, конвекция остужает
                    machine.onSimulationTick(friction, machineSpeed);

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
                    } else if ((double) currentSpeedRaw * u >= machine.getRequired().getSpeedRaw()
                            || machine.getWorkState() == WorkState.JAMMED) {
                        // Потребитель, чьи обороты достаточны, нагружает сеть.
                        // Порог тоже на стороне потребителя: сеть обязана
                        // разогнаться до s_треб / u — за понижающей ступенью
                        // выше паспортных оборотов потребителя. Нагрузка
                        // приводится сохранением мощности: T_сети = T·u —
                        // потребитель за понижающей ступенью грузит сеть
                        // в u раз слабее своего момента. Заклинившая давит
                        // СТРАГИВАНИЕМ — клин не даёт раскрутиться.
                        loadTorque += (long) (Math.max(
                                machine.getRequired().getTorqueRaw(),
                                machine.getBreakawayTorqueRaw()) * u);
                    }
                    // Холостой ход: вращающаяся машина ест момент даже без
                    // полезной работы (трение рабочего органа, вентиляция)
                    if (currentSpeedRaw > 0) {
                        loadTorque += (long) (machine.getIdleTorqueRaw() * u);
                    }
                } else if (machine.getReceived().getSpeedRaw() > 0) {
                    // Выбег: питания нет, но машина механически всё ещё в
                    // сборке (источник убран) — её трение тормозит сеть,
                    // её инерция сеть весит. Отношение u — прошлого тика:
                    // топология не менялась, значения буфера актуальны.
                    final double u = uNode[i];
                    final long machineSpeed = machine.getReceived().getSpeedRaw();
                    final long friction = machine.getFrictionTorque(machineSpeed);
                    frictionTorque += (long) (friction * u);
                    totalInertia += (machine.getInertia() + machine.getExtraInertia()) * u * u;
                    // Узел живёт: трение греет его материал, конвекция остужает
                    machine.onSimulationTick(friction, machineSpeed);
                }
            }

            final long netTorque = (hasSource ? sourceTorqueRaw : 0) - frictionTorque - loadTorque;
            // boggingDown = сеть не тянет (момент отрицателен). Разделяем два случая:
            // жёсткая перегрузка — мгновенный стоп ниже; маргинальный дефицит —
            // плавная пила. Флаг переживает фазу для B2 (поле драйвера).
            boggingDown = hasSource && netTorque < 0;
            // Жёсткая перегрузка: потребители требуют момента БОЛЬШЕ, чем источники
            // дают в принципе (ещё без трения) — вал упирается и мгновенно клинит.
            // Сломанная от перегрева ступень — тот же стоп: зуб выкрошен,
            // провернуть нечего.
            final boolean infeasible = hasSource && loadTorque > sourceTorqueRaw;

            if (infeasible || brokenJam) {
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

            if (!hasSource) {
                // Сеть без источников — сборка едина: скорость сети не может
                // обгонять самый быстрый выбегающий узел. Без капа сеть
                // замерзала: выбегающие машины добирали свой ноль на тик
                // раньше, трение исчезало, а currentSpeedRaw оставался.
                long maxCoast = 0;
                for (int i = 0; i < n; i++) {
                    if (!hasPower[i]) {
                        final long s = machines[i].getReceived().getSpeedRaw();
                        if (s > maxCoast) {
                            maxCoast = s;
                        }
                    }
                }
                if (currentSpeedRaw > maxCoast) {
                    currentSpeedRaw = maxCoast;
                }
            }

            // Приведённая инерция — для слияния сетей (неупругий удар)
            lastReducedInertia = totalInertia;
        }

        /**
         * Фаза B2 — demand снизу вверх (по глубине, от листьев):
         * стоимость поддерева, приведённая к ВХОДНОЙ стороне узла.
         * <p>
         * Требования детей снимаются с выхода узла и переводятся на вход
         * через сохранение мощности: t_in = t_out · (s_out / s_in) / η.
         * КПД входит в стоимость: источник обязан покрыть и потери, поэтому
         * проверка «дети требуют больше выдачи» в фазе C сравнивает
         * величины одной природы. Скорости ребра — из фазы A.
         */
        private void phaseB2_demand() {
            // Порядок desc (листья → корни) поддержан фазой B — сортировка
            // здесь была бы четвёртым проходом за тик без изменения порядка
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

                // Стоимость требований детей на входной стороне узла.
                // Момент нельзя суммировать с разных скоростных уровней:
                // без конверсии понижающая ступень завышала бы спрос,
                // повышающая занижала.
                final double eta = machines[m].getEfficiency();
                long childrenCost = 0;
                for (int e = 0; e < edgeCount; e++) {
                    // Рёбра-связи (согласованные кольца) мощность не проводят
                    if (edgeFrom[e] == m && hasPower[edgeTo[e]] && !edgeConnection[e]) {
                        childrenCost += PhysicsMath.childDemandToInputCost(
                                subtreeTorque[edgeTo[e]], edgeSpeed[e], in.getSpeedRaw(), eta);
                    }
                }

                subtreeTorque[m] = own + childrenCost;
            }
        }

        /**
         * Фаза B3 — делёж входного момента по спросу выходов, сверху вниз.
         * <p>
         * Момент входа делится между выходами пропорционально их спросу
         * (величины приведены к одной скорости, поэтому доли считаются
         * моментами), а не копируется на каждый выход: сумма выданного
         * не превосходит прихода, закон раздела 4 P_in = Σ P_out + P_loss
         * выполняется на каждом узле. КПД узла применяется один раз за узел,
         * потерянная мощность идёт в его тепло (по факту выданного).
         * <p>
         * Дети с несколькими питающими родителями (слияние сетей) не
         * перезаписываются: их вход остаётся суммой фазы A до честного
         * слияния по импульсу (очередь С1, отдельная задача).
         */
        private void phaseB3_splitByDemand() {
            // Входная степень узла: детей с несколькими родителями делёж
            // не трогает. Очередь BFS свободна после фазы A — переиспользуем.
            final int[] parentCount = queue;
            Arrays.fill(parentCount, 0, n, 0);
            for (int e = 0; e < edgeCount; e++) {
                if (hasPower[edgeTo[e]] && hasPower[edgeFrom[e]]) {
                    parentCount[edgeTo[e]]++;
                }
            }

            // Потолок прихода узла: потенциал фазы A ДО дележа. Страгивание
            // и удержание клина сравниваются с ним, а не с выданным спросом:
            // спрос всегда равен потребности машины и сам по себе ничего
            // не говорит о том, что сеть способна дать.
            for (int i = 0; i < n; i++) {
                capTorque[i] = hasPower[i] ? Math.abs(inputPower[i].getTorqueRaw()) : 0;
            }

            sortByDepth(order, depth, n, true);

            for (int idx = 0; idx < n; idx++) {
                final int m = order[idx];
                if (!hasPower[m]) {
                    continue;
                }

                // Суммарный спрос детей, приведённый к входной стороне узла —
                // та же конверсия, что в фазе B2 (с КПД узла).
                final RotationalPower in = inputPower[m];
                final double eta = machines[m].getEfficiency();
                long demandCost = 0;
                int children = 0;
                for (int e = 0; e < edgeCount; e++) {
                    if (edgeFrom[e] == m && hasPower[edgeTo[e]] && !edgeConnection[e]) {
                        demandCost += PhysicsMath.childDemandToInputCost(
                                subtreeTorque[edgeTo[e]], edgeSpeed[e], in.getSpeedRaw(), eta);
                        children++;
                    }
                }
                if (children == 0) {
                    continue;
                }
                if (demandCost <= 0) {
                    // Спроса нет — через узел ничего не течёт: детям достаётся
                    // ноль, а не копия входа, которую рисовала фаза A.
                    for (int e = 0; e < edgeCount; e++) {
                        if (edgeFrom[e] == m && hasPower[edgeTo[e]]
                                && !edgeConnection[e] && parentCount[edgeTo[e]] == 1) {
                            inputPower[edgeTo[e]].setTorqueRaw(0);
                        }
                    }
                    continue;
                }

                // Доля входа: хватает на весь спрос — каждый выход получает
                // свой спрос целиком; не хватает — пропорционально спросу.
                final long inAvail = Math.abs(in.getTorqueRaw());
                final double share = inAvail >= demandCost ? 1.0 : (double) inAvail / demandCost;

                long grantedWatts = 0;
                for (int e = 0; e < edgeCount; e++) {
                    if (edgeFrom[e] != m || !hasPower[edgeTo[e]] || edgeConnection[e]) {
                        continue;
                    }
                    final int c = edgeTo[e];
                    final long demandC = subtreeTorque[c];
                    final long shareC = share >= 1.0 ? demandC : Math.round(demandC * share);
                    // Потолок ребра: после transform и хуков (износ вала режет
                    // именно его). Выдать больше потолка нельзя, даже если
                    // спрос и доля входа позволяют.
                    final long grantC = Math.min(shareC, Math.max(0, edgeTorque[e]));
                    if (parentCount[c] == 1) {
                        // Делёж меняет только момент: скорость и направление
                        // кинематические, их задаёт фаза A.
                        inputPower[c].setTorqueRaw(grantC);
                    }
                    grantedWatts += PhysicsMath.watts(grantC, edgeSpeed[e]);
                }

                // Потери узла — по фактически выданной мощности, один раз за
                // узел: P_loss = P_out · (1-η) / η, за тик E += P_loss / 20.
                if (eta < 1.0 && grantedWatts > 0) {
                    machines[m].getSimulationState().addHeatJ(
                            grantedWatts * (1.0 - eta) / eta / 20.0);
                }
            }
        }

        /**
         * Фаза C — состояния сверху вниз (по глубине, от корней):
         * перегруженная сеть — INSUFFICIENT_POWER у потребителей,
         * валы жёстко крутятся вместе с сетью.
         */
        private void phaseC_states() {
            // Порядок asc (корни → листья) поддержан фазой B3 — повторная
            // сортировка не меняет порядка
            for (int idx = 0; idx < n; idx++) {
                final int m = order[idx];
                final MechanicalMachine machine = machines[m];

                if (!hasPower[m]) {
                    // Недостижима от источников: машина не powered.
                    // Если раньше крутилась (received > 0) — выбегает по инерции,
                    // замедляясь трением своего материала. Если никогда не
                    // получала мощность — стоит на месте. Инерция — ПОЛНАЯ
                    // (материал + ротор): маховик тормозит сам себя так же,
                    // как сеть тормозит его в фазе B.
                    final long lastSpeed = machine.getReceived().getSpeedRaw();
                    if (lastSpeed > 0) {
                        final long friction = machine.getFrictionTorque(lastSpeed);
                        final long inertia = Math.max(1L, (long) (
                                machine.getInertia() + machine.getExtraInertia()));
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
                    if (edgeFrom[e] == m && hasPower[edgeTo[e]] && !edgeConnection[e]) {
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
                // (рёбра-связи не считаются родителями: они мощность не проводят)
                boolean hasParent = false;
                boolean allParentsDead = true;
                for (int e = 0; e < edgeCount; e++) {
                    if (edgeTo[e] == m && !edgeConnection[e]) {
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

                // Перенапряжение (раздел 11.2): контур требует разных скоростей
                // от одной детали — разность моментов греет узел и ломает зуб.
                // Тепло — по моменту упора на требуемой контуром скорости
                // (балансная заглушка до появления прочности зуба, п.17);
                // мощность τ·ω на стоячей сети — ноль, поэтому считаем момент.
                if (overstrain[m]) {
                    final double omega =
                            targetSpeedRaw * uNode[m] * 2.0 * Math.PI / 60000.0;
                    machine.getSimulationState().addHeatJ(
                            capTorque[m] / 1000.0 * omega / 20.0);
                    states[m] = WorkState.JAMMED;
                    continue;
                }

                // Предел температуры: узел теряет ровно то свойство, которым
                // держит нагрузку (раздел 4). Отметка односторонняя — сам не
                // чинится, деталь меняет игрок. Запаздывание на тик после
                // нагрева этого тика допустимо (следующий тик применит отказ).
                final SimulationState sim = machine.getSimulationState();
                if (!sim.isBrokenByHeat()
                        && machine.getHeatFailureMode() != MechanicalMachine.HeatFailureMode.NONE) {
                    final double massKg = machine.getMaterial().nominalMassKg();
                    if (sim.temperatureC(machine.getMaterial(), massKg)
                            >= machine.getMaxTemperatureC()) {
                        sim.markBrokenByHeat();
                    }
                }
                if (sim.isBrokenByHeat()
                        && machine.getHeatFailureMode() == MechanicalMachine.HeatFailureMode.JAM) {
                    // Зуб выкрошен: ступень заклинивает, фаза D разнесёт клин
                    // вверх по питанию. Открытый отказ (ремень) состояние не
                    // меняет — шкив крутится, рвётся само ребро (фаза A).
                    states[m] = WorkState.JAMMED;
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
                // стирался бы через тик). Сравнение с ПОТОЛКОМ прихода (до
                // дележа): спрос всегда равен потребности и с ним сравнивать
                // страгивание бессмысленно.
                final long holdTorque = Math.max(required.getTorqueRaw(), machine.getBreakawayTorqueRaw());
                if (machine.getWorkState() == WorkState.JAMMED
                        && holdTorque > 0
                        && capTorque[m] < holdTorque) {
                    states[m] = WorkState.INSUFFICIENT_POWER;
                    continue;
                }

                final byte requiredDirection = machine.getRequiredDirection();
                if (requiredDirection != -1 && in.getDirection() != requiredDirection) {
                    states[m] = WorkState.WRONG_DIRECTION;
                    continue;
                }

                // Страгивание: потолка прихода меньше нужного для троганья —
                // машина не стартует и клинит цепь (фаза D разносит клин вверх)
                if (machine.getBreakawayTorqueRaw() > 0
                        && capTorque[m] < machine.getBreakawayTorqueRaw()) {
                    states[m] = WorkState.INSUFFICIENT_POWER;
                    continue;
                }

                if (in.getSpeedRaw() < required.getSpeedRaw()) {
                    states[m] = WorkState.IDLE;
                    continue;
                }

                // Своя потребность + требования детей уже сведены к входной стороне
                // в фазе B2 (с вычетом подпитки от других родителей при слиянии).
                // Пассивные сегменты (валы) жёстко сцеплены с сетью и вращаются
                // вместе с ней: перегрузка потребителя не "останавливает" сегмент
                // передачи — фейлится только машина с собственной потребностью.
                // Маховик — обычная инерция сети, буферного обхода дефицита нет.
                if (subtreeTorque[m] <= in.getTorqueRaw() || machine.isPassive()) {
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
                // Сломанная от перегрева ступень и перенапряжённый контур —
                // клин сами по себе, независимо от того, что насчитали фазы B/C
                final boolean overheatJam = machines[i].getSimulationState().isBrokenByHeat()
                        && machines[i].getHeatFailureMode() == MechanicalMachine.HeatFailureMode.JAM;
                if (hasPower[i] && (states[i] == WorkState.INSUFFICIENT_POWER
                        || overstrain[i] || overheatJam)) {
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
}
