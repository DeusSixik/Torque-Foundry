package dev.sdm.torque_foundry.api.events.physics;

import dev.sdm.torque_foundry.api.events.bus.EventPtr;
import dev.sdm.torque_foundry.api.events.bus.FastEventBus;

/**
 * Точки расширения физического конвейера через {@link FastEventBus}.
 *
 * <p><b>ГЛАВНОЕ: физика тикает в отдельном потоке.</b> Группы считаются
 * пулом воркеров «TorqueFoundry-Physics-N» (см. «Concurrency in Torque
 * Foundry.md», §2), параллельно с серверным тредом. Каждое событие
 * помечено тредом-источником:
 * <ul>
 *   <li><b>[PHYSICS THREAD]</b> — вызывает из воркера пула. Внутри
 *       слушателя ЗАПРЕЩЕНО: трогать {@code Level}/{@code BlockEntity}/
 *       блоки (инвариант I3), звать тяжёлый код (тормозишь тик ВСЕХ групп
 *       — инвариант I5), постить mailbox-задачи (дренаж ждёт). Хочешь
 *       дёрнуть мир — поставь {@code PhysicsTasks.post} и сделай это
 *       на серверном тике.</li>
 *   <li><b>[SERVER THREAD]</b> — вызывает на серверном треде, ограничений
 *       нет (в рамках легальности серверного тика).</li>
 * </ul>
 *
 * <p>События физики слышат только серверные слушатели: клиентских пулов
 * физики не существует. Подписка через {@code EventScope.listen} — отписка
 * вместе со скоупом.
 */
public final class PhysicsEvents {

    /**
     * [PHYSICS THREAD] Начало физического тика группы (перед фазой A).
     * Состояние машин ещё прошлого тика — мутации легальны, фазы их
     * прочитают как вход.
     */
    public static final EventPtr<GroupTickEvent> GROUP_TICK_START =
            new EventPtr<>(new FastEventBus.EventType<>("TF:GroupTickStart"));

    /**
     * [PHYSICS THREAD] Конец физического тика группы (после фазы D,
     * до публикации снапшота). Состояние машин финально для этого тика.
     */
    public static final EventPtr<GroupTickEvent> GROUP_TICK_END =
            new EventPtr<>(new FastEventBus.EventType<>("TF:GroupTickEnd"));

    /**
     * [PHYSICS THREAD] Группа заклинила: фаза D впервые за серию оставила
     * JAMMED-машины (переход не-клин → клин). Повторных fires, пока клин
     * держится, нет — только выход через {@link #GROUP_UNJAMMED}.
     */
    public static final EventPtr<GroupJamEvent> GROUP_JAMMED =
            new EventPtr<>(new FastEventBus.EventType<>("TF:GroupJammed"));

    /**
     * [PHYSICS THREAD] Группа вышла из клина (первый тик без JAMMED
     * после клина).
     */
    public static final EventPtr<GroupJamEvent> GROUP_UNJAMMED =
            new EventPtr<>(new FastEventBus.EventType<>("TF:GroupUnjammed"));

    /**
     * [SERVER THREAD] Цикл физики отправлен в пул (после дренажа mailbox,
     * до сабмита слотов). Группы цикла — снапшот менеджера.
     */
    public static final EventPtr<CycleEvent> CYCLE_SUBMITTED =
            new EventPtr<>(new FastEventBus.EventType<>("TF:CycleSubmitted"));

    /**
     * [SERVER THREAD] Цикл физики завершён: все слоты дотикали (барьер
     * пройден в начале следующего tick()/stop()). Данные снапшотов групп
     * соответствуют этому циклу.
     */
    public static final EventPtr<CycleEvent> CYCLE_COMPLETED =
            new EventPtr<>(new FastEventBus.EventType<>("TF:CycleCompleted"));

    private PhysicsEvents() {
    }
}
