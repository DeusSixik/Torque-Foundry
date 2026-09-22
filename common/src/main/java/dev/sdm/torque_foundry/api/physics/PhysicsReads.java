package dev.sdm.torque_foundry.api.physics;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;

/**
 * Публичный фасад ЧТЕНИЯ состояния физики (см. «Concurrency in Torque
 * Foundry.md», §8.1).
 *
 * <p>Читатель получает не живые машины, а копию опубликованного снапшота
 * группы ({@code front}) в свой {@link GroupSnapshotView}. Копия берётся
 * одним куском под замком группы — срез всегда консистентен (все поля
 * из одного тика, {@code tickId}).
 *
 * <p>Тредовые контракты:
 * <ul>
 *   <li>{@link #copySnapshot} — любой тред, блокирующе (серверный синк);</li>
 *   <li>{@link #tryCopySnapshot} — рендер: не блокирует, при занятом замке
 *       возвращает false и читатель работает с прошлым view (протухание
 *       на 1 кадр незаметно, а ожидание физики — дроп FPS).</li>
 * </ul>
 */
public final class PhysicsReads {

    private PhysicsReads() {
    }

    /**
     * Скопировать последний опубликованный снапшот группы во view читателя.
     *
     * <p>Блокирующе: ждёт, если владелец публикует прямо сейчас (микросекунды).
     *
     * @param groupId id группы (машина знает его через {@code getGroupIndex()})
     * @param into буфер читателя (переиспользуется, растёт при росте группы)
     * @return true — view заполнен; false — группы с таким id нет
     */
    public static boolean copySnapshot(long groupId, GroupSnapshotView into) {
        final var group = MechanicalGroupManager.getGroup(groupId);
        return group != null && group.copySnapshotTo(into, true);
    }

    /**
     * Неблокирующий вариант для рендер-треда: замок группы занят публикацией —
     * false, view НЕ тронут (читайте прошлый кадр).
     *
     * @param groupId id группы
     * @param into буфер читателя
     * @return true — view заполнен свежим (или последним) срезом;
     *     false — группы нет или замок занят
     */
    public static boolean tryCopySnapshot(long groupId, GroupSnapshotView into) {
        final var group = MechanicalGroupManager.getGroup(groupId);
        return group != null && group.copySnapshotTo(into, false);
    }
}
