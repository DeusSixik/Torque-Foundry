package dev.sdm.torque_foundry.core.block;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class TFBlocks {

    public static final RegistrarManager MANAGER = RegistrarManager.get(TorqueFoundry.MOD_ID);

    public static final Registrar<Block> BLOCKS = MANAGER.get(Registries.BLOCK);
    public static final Registrar<Item> ITEMS = MANAGER.get(Registries.ITEM);

    public static final RegistrySupplier<GeneratorBlock> GENERATOR = BLOCKS.register(
            id("generator"),
            () -> new GeneratorBlock(props(), GeneratorBlock.DEFAULT_OUTPUT)
    );

    public static final RegistrySupplier<ShaftBlock> SHAFT = BLOCKS.register(
            id("shaft"),
            // noOcclusion: блок рисуется BER'ом и не является полным кубом —
            // соседние блоки должны рисовать свои грани, упирающиеся в него
            () -> new ShaftBlock(props().noOcclusion())
    );

    public static final RegistrySupplier<ConsumerBlock> CONSUMER = BLOCKS.register(
            id("consumer"),
            () -> new ConsumerBlock(props(), ConsumerBlock.DEFAULT_REQUIRED)
    );

    public static final RegistrySupplier<BlockItem> GENERATOR_ITEM = ITEMS.register(
            id("generator"), () -> new BlockItem(GENERATOR.get(), new Item.Properties())
    );

    public static final RegistrySupplier<BlockItem> SHAFT_ITEM = ITEMS.register(
            id("shaft"), () -> new BlockItem(SHAFT.get(), new Item.Properties())
    );

    public static final RegistrySupplier<BlockItem> CONSUMER_ITEM = ITEMS.register(
            id("consumer"), () -> new BlockItem(CONSUMER.get(), new Item.Properties())
    );

    public static final CreativeModeTab MAIN_TAB = CreativeTabRegistry.create(builder -> builder
            .title(Component.translatable("itemGroup.torque_foundry.main"))
            .icon(() -> new ItemStack(GENERATOR_ITEM.get()))
            .displayItems((parameters, output) -> {
                output.accept(GENERATOR_ITEM.get());
                output.accept(SHAFT_ITEM.get());
                output.accept(CONSUMER_ITEM.get());
            })
    );

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of()
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TorqueFoundry.MOD_ID, path);
    }

    public static void register() {
        // Static initialization registers everything via Architectury RegistrarManager.
    }

    private TFBlocks() {
    }
}
