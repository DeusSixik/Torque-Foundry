package dev.sdm.torque_foundry.core.item;

import dev.sdm.torque_foundry.physics.machine.ShaftGrade;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Предмет вала: несёт тир балансировки (Grade C..S) в DataComponents.
 * Тир определяется качеством станка при крафте (верстак -> C, ... -> S)
 * и превращается в перекос машины при установке.
 */
public class ShaftItem extends BlockItem {

    public ShaftItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Тир балансировки стака (без компонента — базовый Grade C). */
    public static ShaftGrade gradeOf(ItemStack stack) {
        final ShaftGrade grade = stack.get(TFComponents.SHAFT_GRADE.get());
        return grade == null ? ShaftGrade.C : grade;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        final ShaftGrade grade = gradeOf(stack);
        tooltip.add(Component.translatable(
                        "item.torque_foundry.shaft.grade",
                        grade.name(),
                        String.format(java.util.Locale.ROOT, "%.1f", grade.misalignmentDeg()))
                .withStyle(ChatFormatting.GRAY));
    }
}
