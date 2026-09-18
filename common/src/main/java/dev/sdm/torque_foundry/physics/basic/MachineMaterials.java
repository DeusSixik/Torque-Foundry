package dev.sdm.torque_foundry.physics.basic;

/**
 * Пресеты материалов механических деталей.
 * Дерево — дешёвое, лёгкое, тихое, но хрупкое и низкооборотное.
 * Сталь — тяжёлое, прочное, высокооборотное, с малым трением.
 */
public final class MachineMaterials {

    public static final MachineMaterial WOOD   = new MachineMaterial("Wood",   120, 0.010, 1.0, 2);
    public static final MachineMaterial BRONZE = new MachineMaterial("Bronze", 180, 0.025, 1.5, 5);
    public static final MachineMaterial IRON   = new MachineMaterial("Iron",   256, 0.030, 2.0, 8);
    public static final MachineMaterial STEEL  = new MachineMaterial("Steel",  400, 0.020, 3.0, 12);

    /** Материал по умолчанию. */
    public static final MachineMaterial DEFAULT = IRON;

    private MachineMaterials() {
    }
}
