package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

import java.util.Arrays;

/**
 * Слот пайплайна: подмножество групп одного физического тика.
 * Массив групп переиспользуется между тиками (рост — только при
 * росте суммарной нагрузки), ноль аллокаций в установившемся режиме.
 *
 * <p>Инвариант I1 («Concurrency in Torque Foundry.md», §3): слот целиком
 * исполняется ОДНОЙ задачей пула — группа не может тикаться двумя
 * потоками, поскольку попадает ровно в один слот одной раскладки.
 *
 * <p>Выполняется в воркере пула физики, не на серверном треде.
 */
public final class PhysicsTick {

    private static final int INITIAL_CAPACITY = 4;

    private MechanicalGroup[] groups = new MechanicalGroup[INITIAL_CAPACITY];
    private int count;

    /**
     * Очистить слот перед раскладкой нового тика (серверный тред).
     */
    public void reset() {
        count = 0;
    }

    /**
     * Добавить группу в слот; между reset и compute (серверный тред).
     */
    public void append(MechanicalGroup group) {
        if (count == groups.length) {
            groups = Arrays.copyOf(groups, Math.max(INITIAL_CAPACITY, count * 2));
        }
        groups[count++] = group;
    }

    /**
     * Тик всех групп слота: по очереди, целиком, в одном потоке-воркере.
     * Публикация снапшотов — в конце тика каждой группы (её замок).
     */
    public void compute() {
        for (int i = 0; i < count; i++) {
            groups[i].computeTick();
        }
    }

    /**
     * Число групп в слоте (диагностика/тесты).
     */
    public int size() {
        return count;
    }
}
