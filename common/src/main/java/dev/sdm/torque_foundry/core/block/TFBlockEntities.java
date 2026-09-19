package dev.sdm.torque_foundry.core.block;

import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class TFBlockEntities {

    private static final Registrar<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            TFBlocks.MANAGER.get(Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<BlockEntityType<GeneratorBlockEntity>> GENERATOR =
            BLOCK_ENTITY_TYPES.register(TFBlocks.id("generator"),
                    () -> BlockEntityType.Builder.of(GeneratorBlockEntity::new, TFBlocks.GENERATOR.get()).build(null));

    public static final RegistrySupplier<BlockEntityType<ShaftBlockEntity>> SHAFT =
            BLOCK_ENTITY_TYPES.register(TFBlocks.id("shaft"),
                    () -> BlockEntityType.Builder.of(ShaftBlockEntity::new, TFBlocks.SHAFT.get()).build(null));

    public static final RegistrySupplier<BlockEntityType<ConsumerBlockEntity>> CONSUMER =
            BLOCK_ENTITY_TYPES.register(TFBlocks.id("consumer"),
                    () -> BlockEntityType.Builder.of(ConsumerBlockEntity::new, TFBlocks.CONSUMER.get()).build(null));

    public static final RegistrySupplier<BlockEntityType<ChassisBlockEntity>> CHASSIS =
            BLOCK_ENTITY_TYPES.register(TFBlocks.id("chassis"),
                    () -> BlockEntityType.Builder.of(ChassisBlockEntity::new, TFBlocks.CHASSIS.get()).build(null));

    public static void register() {
        // Static initialization registers everything via Architectury RegistrarManager.
    }

    private TFBlockEntities() {
    }
}
