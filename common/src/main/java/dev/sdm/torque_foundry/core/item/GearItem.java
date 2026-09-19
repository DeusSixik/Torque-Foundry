package dev.sdm.torque_foundry.core.item;

import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Предмет-шестерня: число зубьев (из размера при крафте) и физический
 * материал. Параметры живут в DataComponents стака.
 *
 * <p>Рейтинги выводятся из физики материала и размера (см. методы):
 * <ul>
 *   <li>Рейтинг момента: T_max материала на радиус, растущий с зубьями —
 *       крупная шестерня держит больше (больше сечение зуба).</li>
 *   <li>Рейтинг оборотов: безопасные обороты материала (усталость
 *       поверхности зуба).</li>
 * </ul>
 *
 * <p>Зацепление: шестерня входит в зацепление с приводной шестернёй
 * шасси ({@link #DRIVE_TEETH} зубьев) — передаточное число пары =
 * DRIVE_TEETH / зубья шестерни.
 */
public class GearItem extends Item {

    /** Зубья приводной шестерни шасси (входной вал коробки). */
    public static final int DRIVE_TEETH = 8;

    public GearItem(Properties properties) {
        super(properties);
    }

    // --- Компоненты ---

    public static int teethOf(ItemStack stack) {
        final Integer teeth = stack.get(TFComponents.GEAR_TEETH.get());
        return teeth == null ? DRIVE_TEETH : teeth;
    }

    public static PhysicsMaterial materialOf(ItemStack stack) {
        final String name = stack.get(TFComponents.GEAR_MATERIAL.get());
        return materialByName(name);
    }

    public static ItemStack create(int teeth, PhysicsMaterial material) {
        final ItemStack stack = new ItemStack(TFItems.GEAR.get());
        stack.set(TFComponents.GEAR_TEETH.get(), teeth);
        stack.set(TFComponents.GEAR_MATERIAL.get(), material.name());
        return stack;
    }

    /** Материал по имени пресета (дефолт — железо). */
    public static PhysicsMaterial materialByName(String name) {
        for (PhysicsMaterial m : new PhysicsMaterial[]{
                PhysicsMaterials.WOOD, PhysicsMaterials.BRONZE,
                PhysicsMaterials.IRON, PhysicsMaterials.STEEL}) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return PhysicsMaterials.DEFAULT;
    }

    // --- Рейтинги из физики ---

    /** Радиус делительной окружности: растёт с числом зубьев, м. */
    public static double pitchRadiusM(int teeth) {
        return 0.010 + teeth * 0.00125; // 8 зубьев = 0.02 м, 32 = 0.05 м
    }

    /**
     * Рейтинг момента (Н·м): предельный момент материала на радиус
     * делительной окружности (запас 2). Крупная шестерня — прочнее зуб.
     */
    public static double ratedTorqueNm(PhysicsMaterial material, int teeth) {
        return material.maxSafeTorqueNm(pitchRadiusM(teeth), 2.0);
    }

    /** Рейтинг оборотов: безопасные обороты материала (износ зуба). */
    public static int ratedSpeedRpm(PhysicsMaterial material) {
        return material.maxSafeSpeedRpm();
    }

    /** Передаточное число пары с приводной шестернёй: n = DRIVE_TEETH/teeth. */
    public static double ratio(int teeth) {
        return (double) DRIVE_TEETH / Math.max(1, teeth);
    }

    // --- Тултип ---

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        final int teeth = teethOf(stack);
        final PhysicsMaterial material = materialOf(stack);
        final double ratio = ratio(teeth);
        final String ratioText = ratio >= 1.0
                ? String.format(java.util.Locale.ROOT, "1:%.2f", ratio)
                : String.format(java.util.Locale.ROOT, "%.2f:1", 1.0 / ratio);

        tooltip.add(Component.translatable("item.torque_foundry.gear.teeth",
                teeth, DRIVE_TEETH, ratioText).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.torque_foundry.gear.material",
                material.name()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.torque_foundry.gear.rating",
                String.format(java.util.Locale.ROOT, "%.0f", ratedTorqueNm(material, teeth)),
                ratedSpeedRpm(material)).withStyle(ChatFormatting.GRAY));
    }
}
