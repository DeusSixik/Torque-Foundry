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
 * Предмет-вал: вставка в шасси. Шасси с валом становится проходным
 * сегментом вала (IN_OUT по оси FACING) из заданного материала.
 *
 * <p>Материал определяет трение, инерцию и безопасные обороты
 * (износ выше лимита режет момент — ShaftWearHook обрабатывает и
 * вал-вставки шасси).
 */
public class ShaftPartItem extends Item {

    public ShaftPartItem(Properties properties) {
        super(properties);
    }

    public static PhysicsMaterial materialOf(ItemStack stack) {
        final String name = stack.get(TFComponents.SHAFT_MATERIAL.get());
        return materialByName(name);
    }

    public static ItemStack create(PhysicsMaterial material) {
        final ItemStack stack = new ItemStack(TFItems.SHAFT_PART.get());
        stack.set(TFComponents.SHAFT_MATERIAL.get(), material.name());
        return stack;
    }

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

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        final PhysicsMaterial material = materialOf(stack);
        tooltip.add(Component.translatable("item.torque_foundry.shaft_part.material",
                material.name()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.torque_foundry.shaft_part.rating",
                material.maxSafeSpeedRpm(),
                String.format(java.util.Locale.ROOT, "%.0f",
                        material.defaultMaxSafeTorqueNm())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.torque_foundry.shaft_part.use")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
