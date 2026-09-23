package dev.sdm.torque_foundry.api.events.physics;

/**
 * Payload {@link PhysicsEvents#CYCLE_SUBMITTED}/{@link PhysicsEvents#CYCLE_COMPLETED}.
 *
 * <p>Поток-источник: СЕРВЕРНЫЙ тред (единственные события физики, где
 * разрешён полный доступ к серверу). Экземпляр переиспользуется: не хранить
 * ссылку за пределами слушателя.
 */
public final class CycleEvent {

    private int groupCount;
    private int slots;

    /**
     * Сколько групп участвовало в цикле.
     */
    public int groupCount() {
        return groupCount;
    }

    /**
     * На сколько слотов пула легла раскладка (<= числа групп).
     */
    public int slots() {
        return slots;
    }

    /* package-private */ void set(int groupCount, int slots) {
        this.groupCount = groupCount;
        this.slots = slots;
    }
}
