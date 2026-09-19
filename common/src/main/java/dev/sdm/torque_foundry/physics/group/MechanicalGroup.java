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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
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
    private Integer[] orderBuf = new Integer[0];
    private int[] edgeFromBuf = new int[0];
    private int[] edgeToBuf = new int[0];
    private long[] edgeSpeedBuf = new long[0];
    private long[] edgeTorqueBuf = new long[0];
    private boolean[] jammedBuf = new boolean[0];
    private final Long2IntMap posToIndexBuf = new Long2IntOpenHashMap();
    private final SimulationContext simContext = new SimulationContext(this, 0);
    private final Queue<Integer> queueBuf = new ArrayDeque<>();
    private final RotationalPower edgeScratch = RotationalPower.fromRaw(0, 0);

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
        this.groupId = INDEX_GENERATOR.getAndIncrement();
        ;
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
        final RotationalPower[] inputPower = ensureObjects(inputPowerBuf, n, RotationalPower[]::new);
        inputPowerBuf = inputPower;
        final Integer[] order = ensureObjects(orderBuf, n, Integer[]::new);
        orderBuf = order;

        // Рёбра DAG (parent -> child) + скорость/момент на каждом ребре
        // (после transform родителя и хуков: у раздатки/конической
        // на разных выходах мощность разная).
        final int maxEdges = n > 1 ? n * (n - 1) : 1;
        int[] edgeFrom = ensureInt(edgeFromBuf, maxEdges);
        edgeFromBuf = edgeFrom;
        int[] edgeTo = ensureInt(edgeToBuf, maxEdges);
        edgeToBuf = edgeTo;
        long[] edgeSpeed = ensureLong(edgeSpeedBuf, maxEdges);
        edgeSpeedBuf = edgeSpeed;
        long[] edgeTorque = ensureLong(edgeTorqueBuf, maxEdges);
        edgeTorqueBuf = edgeTorque;
        int edgeCount = 0;

        Arrays.fill(parent, 0, n, -1);
        Arrays.fill(inputPower, 0, n, null);
        Arrays.fill(depth, 0, n, 0);

        final Queue<Integer> queue = queueBuf;
        queue.clear();

        // --- Фаза A: граф мощности (BFS от источников, слияние входов) ---
        // Скорость источника = ТЕКУЩИЕ обороты сети (инерция: без скачков).
        for (int i = 0; i < n; i++) {
            RotationalPower output = machines[i].getOutput();
            if (output == null) {
                continue;
            }

            // Хук: ослабление источника (износ, топливо, температура)
            for (int h = 0; h < hooks.size(); h++) {
                output = hooks.get(h).onSourceOutput(machines[i], output, simContext);
            }

            if (inputPower[i] == null) {
                inputPower[i] = RotationalPower.fromRaw(0, 0);
            }
            inputPower[i].copyFrom(output);
            inputPower[i].setSpeedRaw(currentSpeedRaw);
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
                RotationalPower in = edgeScratch;
                for (int h = 0; h < hooks.size(); h++) {
                    in = hooks.get(h).onTransmit(from, machines[j], in, simContext);
                }
                for (int h = 0; h < hooks.size(); h++) {
                    in = hooks.get(h).onReceive(machines[j], in, simContext);
                }

                if (inputPower[j] == null) {
                    // Первое обнаружение j
                    inputPower[j] = RotationalPower.fromRaw(0, 0);
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

        // --- Фаза B: динамика оборотов сети (инерция/трение/нагрузка) ---
        long targetSpeedRaw = 0;
        long sourceTorqueRaw = 0;
        boolean hasSource = false;
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
            if (in != null || machine.getReceived().getSpeedRaw() > 0) {
                totalInertia += machine.getInertia();
                final long friction = machine.getFrictionTorque(currentSpeedRaw);
                frictionTorque += friction;
                // Симуляционный тик: трение греет, конвекция остужает
                machine.onSimulationTick(friction, currentSpeedRaw);
            }

            if (in != null) {
                final RotationalPower output = machine.getOutput();
                if (output != null) {
                    hasSource = true;
                    if (output.getSpeedRaw() > targetSpeedRaw) {
                        targetSpeedRaw = output.getSpeedRaw();
                    }
                    sourceTorqueRaw += output.getTorqueRaw();
                } else if (currentSpeedRaw >= machine.getRequired().getSpeedRaw()
                        || machine.getWorkState() == WorkState.JAMMED) {
                    // Потребитель, чьи обороты достаточны, нагружает сеть.
                    // Заклинившая машина продолжает давить (статическое трение) —
                    // клин не даёт цепи раскрутиться обратно.
                    loadTorque += machine.getRequired().getTorqueRaw();
                }
            }
        }

        final long netTorque = (hasSource ? sourceTorqueRaw : 0) - frictionTorque - loadTorque;
        // boggingDown = сеть не тянет (момент отрицателен). Разделяем два случая:
        final boolean boggingDown = hasSource && netTorque < 0;
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

        // --- Фаза B2: demand снизу вверх (по глубине, от листьев) ---
        // Нужна для freePower/leaf power и для проверки перегруза источников.
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, 0, n, (a, b) -> Integer.compare(depth[b], depth[a]));

        for (int idx = 0; idx < n; idx++) {
            final int m = order[idx];
            final RotationalPower in = inputPower[m];

            if (in == null) {
                subtreeTorque[m] = 0;
                continue;
            }

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
                if (edgeFrom[e] == m && inputPower[edgeTo[e]] != null) {
                    childrenOut += subtreeTorque[edgeTo[e]];
                }
            }

            subtreeTorque[m] = own + childrenOut;
        }

        // --- Фаза C: состояния сверху вниз (по глубине, от корней) ---
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, 0, n, (a, b) -> Integer.compare(depth[a], depth[b]));

        for (int idx = 0; idx < n; idx++) {
            final int m = order[idx];
            final MechanicalMachine machine = machines[m];
            final RotationalPower in = inputPower[m];

            if (in == null) {
                // Недостижима от источников: машина не powered.
                // Если раньше крутилась (received > 0) — выбегает по инерции,
                // замедляясь трением своего материала. Если никогда не
                // получала мощность — стоит на месте.
                final long lastSpeed = machine.getReceived().getSpeedRaw();
                if (lastSpeed > 0) {
                    final long friction = machine.getFrictionTorque(lastSpeed);
                    final long decel = PhysicsMath.accelStep(friction, (long) machine.getInertia());
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

            machine.setReceived(in);

            // Leaf power: received минус требования детей (перевод в ватты
            // по скорости каждого ребра — у раздатки они разные)
            long childrenWatts = 0;
            for (int e = 0; e < edgeCount; e++) {
                if (edgeFrom[e] == m && inputPower[edgeTo[e]] != null) {
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

            // Клин держится, пока потребность машины не удовлетворима:
            // скорость обнулилась, но источник продолжает давить моментом
            // меньше требуемого — машина остаётся перегруженной, и фаза D
            // заклинивает её заново (иначе клин стирался бы через тик).
            if (machine.getWorkState() == WorkState.JAMMED
                    && required.getTorqueRaw() > 0
                    && in.getTorqueRaw() < required.getTorqueRaw()) {
                states[m] = WorkState.INSUFFICIENT_POWER;
                continue;
            }

            final byte requiredDirection = machine.getRequiredDirection();
            if (requiredDirection != -1 && in.getDirection() != requiredDirection) {
                states[m] = WorkState.WRONG_DIRECTION;
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

        // --- Фаза D: заклинивание (жёсткая сцепка) ---
        // Потребителю не хватило момента -> он блокируется и тащит за собой
        // ВСЮ цепь вверх до источников: валы, промежуточные машины и
        // производители глохнут под нагрузкой.
        final boolean[] jammed;
        if (jammedBuf.length >= n) {
            jammed = jammedBuf;
        } else {
            jammed = new boolean[n];
            jammedBuf = jammed;
        }
        Arrays.fill(jammed, 0, n, false);

        final List<MechanicalMachine> jammedMachines = new ArrayList<>();
        final List<MechanicalMachine> jammedProducers = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (inputPower[i] != null && states[i] == WorkState.INSUFFICIENT_POWER) {
                jammed[i] = true;
                states[i] = WorkState.JAMMED;
                jammedMachines.add(machines[i]);
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
                if (edgeTo[e] != c || inputPower[edgeFrom[e]] == null) {
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
                if (inputPower[i] != null && machines[i].getOutput() != null) {
                    jammedProducers.add(machines[i]);
                }
            }

            for (int h = 0; h < hooks.size(); h++) {
                hooks.get(h).onJam(this, jammedMachines, jammedProducers, simContext);
            }
        }

        for (int i = 0; i < n; i++) {
            final WorkState state = states[i] != null ? states[i] : WorkState.IDLE;
            machines[i].setWorkState(state);
            if (state == WorkState.INSUFFICIENT_POWER || state == WorkState.JAMMED) {
                machines[i].getSimulationState().countOverloadTick();
            }
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
        return PhysicsMath.torqueForWatts(watts, speedRaw);
    }
}
