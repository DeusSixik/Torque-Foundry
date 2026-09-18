package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.physics.basic.MachineMaterial;
import dev.sdm.torque_foundry.physics.basic.MachineMaterials;
import net.minecraft.util.StringRepresentable;

/**
 * Материал вала как свойство blockstate: переключается Shift+ПКМ.
 * Определяет безопасные обороты (износ через ShaftWearHook),
 * вклад в инерцию и трение сети.
 */
public enum ShaftMaterial implements StringRepresentable {
    WOOD("wood", MachineMaterials.WOOD),
    BRONZE("bronze", MachineMaterials.BRONZE),
    IRON("iron", MachineMaterials.IRON),
    STEEL("steel", MachineMaterials.STEEL);

    private final String name;

    /** Физический материал машины (лимиты, трение, плотность). */
    public final MachineMaterial machine;

    ShaftMaterial(String name, MachineMaterial machine) {
        this.name = name;
        this.machine = machine;
    }

    /** Следующий по кругу (для Shift+ПКМ). */
    public ShaftMaterial next() {
        final ShaftMaterial[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
