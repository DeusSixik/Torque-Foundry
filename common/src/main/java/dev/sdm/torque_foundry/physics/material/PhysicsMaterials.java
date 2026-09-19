package dev.sdm.torque_foundry.physics.material;

/**
 * Пресеты материалов механических деталей.
 *
 * <p>Физические константы задаются архетипами {@code Builder.wood()/bronze()/
 * castIron()/steel()} (типовые справочные значения при 20 °C); зависимые
 * величины (G, τ_y, σ₋₁) и прокси игровой динамики (инерция, трение,
 * лимит оборотов) выводятся автоматически.
 *
 * <p>Дерево — дешёвое, лёгкое, тихое, но хрупкое и низкооборотное.
 * Сталь — жёсткое, прочное, высокооборотное.
 */
public final class PhysicsMaterials {

    /**
     * Дуб вдоль волокон: лёгкий, «мягкий», анизотропный (G кручения задан явно).
     */
    public static final PhysicsMaterial WOOD = PhysicsMaterial.Builder.wood("Wood").build();

    /**
     * Бронза: тяжёлая, пластичная, низкое трение по стали (подшипники).
     */
    public static final PhysicsMaterial BRONZE = PhysicsMaterial.Builder.bronze("Bronze").build();

    /**
     * Серый чугун: хрупкий, демпфирует вибрации.
     */
    public static final PhysicsMaterial IRON = PhysicsMaterial.Builder.castIron("Iron").build();

    /**
     * Конструкционная сталь: жёсткая, прочная, материал передач.
     */
    public static final PhysicsMaterial STEEL = PhysicsMaterial.Builder.steel("Steel").build();

    /**
     * Материал по умолчанию.
     */
    public static final PhysicsMaterial DEFAULT = IRON;

    private PhysicsMaterials() {
    }
}
