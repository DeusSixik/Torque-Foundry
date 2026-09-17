package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.LockSupport;

/**
 * Конвейер физического тика, опережающего серверный.
 *
 * Серверный тред ({@link #tick()}):
 *  1. дожидается завершения прошлого тика физики ({@code await});
 *  2. снимает снапшот групп (под блокировкой менеджера);
 *  3. раскладывает группы по слотам {@link PhysicsTick} и будит поток физики.
 *
 * Поток физики ({@link #physicsLoop()}): вычисляет все незавершённые
 * слоты ({@code group.computeTick()}) и помечает их done.
 *
 * Сейчас один поток — все группы в первом слоте. Разбиение по нагрузке
 * на {@code numberThreads} слотов добавляется в {@link #distribute}.
 */
public final class PhysicsPipeline {

    private final ExecutorService executor;
    private final int numberThreads;
    private final PhysicsTick[] physicsTicks;

    private volatile boolean running = true;
    private volatile boolean workPending = false;
    private volatile Thread physicsThread;

    public PhysicsPipeline(int numberThreads, ExecutorService executor) {
        this.executor = executor;
        this.numberThreads = numberThreads;
        this.physicsTicks = new PhysicsTick[numberThreads];

        for (int i = 0; i < numberThreads; i++) {
            this.physicsTicks[i] = new PhysicsTick();
        }
    }

    /**
     * Запускает поток физики. Один раз при старте сервера.
     */
    public void start() {
        executor.submit(this::physicsLoop);
    }

    public void stop() {
        running = false;
        final Thread thread = physicsThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    /**
     * Серверная сторона тика: синхронизация с потоком физики.
     * Читающие (BE, синк клиенту) могут вызывать только после этого метода,
     * иначе данные прошлого-прошлого тика.
     */
    public void tick() {
        // 1. прошлый тик физики обязан завершиться до чтения состояний
        awaitAll();

        // 2. снапшот групп на этот серверный тик
        final MechanicalGroup[] groups = MechanicalGroupManager.getGroupsPrimitive();
        if (groups.length == 0) {
            return;
        }

        // 3. распределение по слотам
        distribute(groups);

        // 4. будим поток физики
        workPending = true;
        final Thread thread = physicsThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    /**
     * Сейчас один поток: все группы в первый слот.
     * Точка будущего разбиения по нагрузке.
     */
    private void distribute(MechanicalGroup[] groups) {
        physicsTicks[0].initialize(groups);
    }

    private void awaitAll() {
        for (int i = 0; i < physicsTicks.length; i++) {
            physicsTicks[i].await();
        }
    }

    private void physicsLoop() {
        physicsThread = Thread.currentThread();

        while (running) {
            if (!workPending) {
                LockSupport.parkNanos(100_000);
                continue;
            }
            workPending = false;

            try {
                for (int i = 0; i < physicsTicks.length; i++) {
                    final PhysicsTick tick = physicsTicks[i];
                    if (!tick.isDone()) {
                        tick.compute();
                    }
                }
            } catch (Throwable t) {
                TorqueFoundry.LOGGER.error("Physics tick failed", t);
                // слоты, не помеченные done, обязательна await-у повиснуть;
                // принудительно завершаем слот при ошибке
                for (int i = 0; i < physicsTicks.length; i++) {
                    physicsTicks[i].markDone();
                }
            }
        }
    }
}
