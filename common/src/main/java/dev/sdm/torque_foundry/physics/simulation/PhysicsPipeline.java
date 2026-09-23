package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.api.events.physics.PhysicsEventDispatcher;
import dev.sdm.torque_foundry.api.physics.PhysicsTasks;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Конвейер физического тика на пуле воркеров (Ф3, «Concurrency in Torque
 * Foundry.md», §9.3).
 *
 * <p>Серверный тред ({@link #tick()}):
 * <ol>
 *   <li>дожидается тасков прошлого тика ({@code Future.get} — без спин-лока);</li>
 *   <li>дренирует mailbox-заявки (физика спит, группы свободны);</li>
 *   <li>снимает снапшот групп и раскладывает их по слотам балансировкой
 *       по размеру (жадный LPT);</li>
 *   <li>сабмитит слоты в пул и ВОЗВРАЩАЕТСЯ — физика тика N идёт параллельно
 *       остальному серверному тику (лаг чтений — 1 тик, инвариант I6).</li>
 * </ol>
 *
 * <p>Состояние пайплайна трогает только серверный тред (tick/stop из
 * SERVER_POST/SERVER_STOPPING) — синхронизация полей не нужна.
 *
 * <p>Отказ одного слота (исключение в compute) изолирован: остальные слоты
 * тикнутся, ошибка логируется с причиной — серверный тик не виснет.
 */
public final class PhysicsPipeline {

    private final ExecutorService executor;
    private final PhysicsTick[] physicsTicks;

    /**
     * Scratch раскладки: нагрузка слота / слот группы (server thread only).
     */
    private final long[] slotLoads;
    private int[] slotOf = new int[0];

    /**
     * Таски текущего тика; читаются только серверным тредом.
     */
    private Future<?>[] pending = new Future<?>[0];
    private int pendingCount;

    /** Параметры последнего запущенного цикла (для события COMPLETED). */
    private int lastGroupCount;
    private int lastSlots;

    public PhysicsPipeline(int numberThreads, ExecutorService executor) {
        if (numberThreads <= 0) {
            throw new IllegalArgumentException("numberThreads must be positive");
        }
        this.executor = executor;
        this.physicsTicks = new PhysicsTick[numberThreads];
        this.slotLoads = new long[numberThreads];
        for (int i = 0; i < numberThreads; i++) {
            this.physicsTicks[i] = new PhysicsTick();
        }
    }

    /**
     * Серверная сторона тика: синхронизация с прошлым тиком, дренаж заявок,
     * раскладка и запуск нового. Читающие (BE, синк клиенту) работают с
     * снапшотами групп — для них данные всегда согласованны.
     */
    public void tick() {
        // 1. таски прошлого тика обязаны завершиться до перераскладки.
        //    По завершении — событие COMPLETED (данные снапшотов цикла).
        awaitCycle();

        // 2. mailbox-заявки: физика спит — группы не тикаются, задачи
        //    исполняются владельцами безопасно и детерминированно.
        //    Заявки, пришедшие во время дренажа, ждут следующего тика.
        PhysicsTasks.drainPending();

        // 3. снапшот групп на этот серверный тик
        final MechanicalGroup[] groups = MechanicalGroupManager.getGroupsPrimitive();
        if (groups.length == 0) {
            return;
        }

        // 4. раскладка по слотам + запуск
        final int slots = distribute(groups);
        lastGroupCount = groups.length;
        lastSlots = slots;
        submit(slots);
        PhysicsEventDispatcher.fireCycleSubmitted(groups.length, slots);
    }

    /**
     * Завершение: дождаться последнего запущенного тика.
     * Пул останавливает владелец ({@code ExecutorService.shutdownNow}).
     */
    public void stop() {
        awaitCycle();
    }

    /**
     * Барьер цикла: дождаться тасков и, если что-то ждали, стрельнуть
     * CYCLE_COMPLETED (данные снапшотов цикла согласованы). Общий для
     * tick() и stop(): завершение цикла — семантика барьера, а не тика.
     */
    private void awaitCycle() {
        final boolean hadPending = pendingCount > 0;
        awaitAll();
        if (hadPending) {
            PhysicsEventDispatcher.fireCycleCompleted(lastGroupCount, lastSlots);
        }
    }

    /**
     * Раскладка групп по слотам: жадная балансировка по размеру группы
     * (each-to-least-loaded, O(g·t) без сортировки). Детерминирована:
     * при равной нагрузке берётся слот с меньшим индексом.
     *
     * @return число используемых слотов (<= числа групп)
     */
    private int distribute(MechanicalGroup[] groups) {
        final int slots = Math.min(groups.length, physicsTicks.length);
        if (slotOf.length < groups.length) {
            slotOf = new int[groups.length];
        }
        balance(groups, slots, slotLoads, slotOf);
        for (int s = 0; s < slots; s++) {
            physicsTicks[s].reset();
        }
        for (int g = 0; g < groups.length; g++) {
            physicsTicks[slotOf[g]].append(groups[g]);
        }
        return slots;
    }

    /**
     * Балансировка: каждая группа — в слот с минимальной суммарной
     * нагрузкой ({@code getSize()} групп). Отдельный статический метод —
     * чистая функция, тестируется напрямую.
     */
    static void balance(MechanicalGroup[] groups, int slots, long[] slotLoads, int[] slotOf) {
        Arrays.fill(slotLoads, 0, slots, 0L);
        for (int g = 0; g < groups.length; g++) {
            int best = 0;
            for (int s = 1; s < slots; s++) {
                if (slotLoads[s] < slotLoads[best]) {
                    best = s;
                }
            }
            slotOf[g] = best;
            slotLoads[best] += groups[g].getSize();
        }
    }

    private void submit(int slots) {
        if (pending.length < slots) {
            pending = new Future<?>[slots];
        }
        for (int i = 0; i < slots; i++) {
            final PhysicsTick slot = physicsTicks[i];
            try {
                pending[i] = executor.submit(slot::compute);
            } catch (RejectedExecutionException e) {
                // Пул остановлен в гонке с остановкой сервера: слот пропускаем.
                pending[i] = null;
                TorqueFoundry.LOGGER.error("Physics slot #{} rejected (executor down)", i);
            }
        }
        pendingCount = slots;
    }

    /**
     * Дождаться всех тасков последнего тика. Блокирующе (Future.get —
     * park вместо спин-лока прежнего await). Ошибка слота логируется
     * с причиной и НЕ останавливает ожидание остальных.
     */
    private void awaitAll() {
        for (int i = 0; i < pendingCount; i++) {
            final Future<?> future = pending[i];
            if (future == null) {
                continue;
            }
            try {
                future.get();
            } catch (InterruptedException e) {
                // Остановка сервера: сигнал прерывания восстанавливаем,
                // остаток тасков не ждём (executor shutdownNow сам отменит).
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                TorqueFoundry.LOGGER.error("Physics slot #{} failed", i, e.getCause());
            } catch (CancellationException e) {
                TorqueFoundry.LOGGER.warn("Physics slot #{} cancelled (shutdown)", i);
            }
        }
        pendingCount = 0;
    }
}
