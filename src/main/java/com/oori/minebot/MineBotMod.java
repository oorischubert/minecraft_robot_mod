package com.oori.minebot;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.TypedEntityData;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public final class MineBotMod implements ModInitializer {
    public static final String MOD_ID = "minebot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final int ROBOT_INVENTORY_SIZE = 10;
    public static final int MAX_FUEL_STACK = 64;
    public static final int MOVEMENT_BLOCKS_PER_BLAZE_POWDER = 200;
    public static final int MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER = MOVEMENT_BLOCKS_PER_BLAZE_POWDER * 1_000;
    public static final int LEGACY_BLAZE_TICKS_PER_POWDER = 3_000;
    public static final int WEBSOCKET_PORT = 8_765;
    public static final int MINEBOT_TRACK_RANGE_BLOCKS = 128;
    public static final int INTERACTION_REACH_BLOCKS = 4;
    public static final int CAMERA_TYPE_MAX_VISION_BLOCKS = 50;
    public static final int CAMERA_TYPE_NO_HIT_DISTANCE_BLOCKS = CAMERA_TYPE_MAX_VISION_BLOCKS + 1;

    public static final Block COMPUTER_BLOCK = registerBlock(
        "computer_block",
        ComputerBlock::new,
        AbstractBlock.Settings.copy(Blocks.IRON_BLOCK).strength(4.0F, 6.0F)
    );

    public static final Item COMPUTER_BLOCK_ITEM = registerItem(
        "computer_block",
        settings -> new BlockItem(COMPUTER_BLOCK, settings.useBlockPrefixedTranslationKey()),
        new Item.Settings()
    );

    public static final Block CAMERA_BLOCK = registerBlock(
        "camera_block",
        CameraBlock::new,
        AbstractBlock.Settings.copy(Blocks.IRON_BLOCK).strength(3.5F, 6.0F)
    );

    public static final Item CAMERA_BLOCK_ITEM = registerItem(
        "camera_block",
        settings -> new BlockItem(CAMERA_BLOCK, settings.useBlockPrefixedTranslationKey()),
        new Item.Settings()
    );

    public static final EntityType<MineBotEntity> MINEBOT_ENTITY = Registry.register(
        Registries.ENTITY_TYPE,
        id("minebot"),
        FabricEntityTypeBuilder.createMob()
            .entityFactory(MineBotEntity::new)
            .spawnGroup(SpawnGroup.CREATURE)
            .dimensions(EntityDimensions.fixed(0.6F, 1.95F))
            .trackRangeBlocks(MINEBOT_TRACK_RANGE_BLOCKS)
            .trackedUpdateRate(1)
            .forceTrackedVelocityUpdates(true)
            .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, id("minebot")))
    );

    public static final Item MINEBOT_SPAWN_EGG = registerItem(
        "minebot_spawn_egg",
        settings -> new MineBotSpawnEggItem(
            settings
                .component(DataComponentTypes.ENTITY_DATA, TypedEntityData.create(MINEBOT_ENTITY, new NbtCompound()))
                .useItemPrefixedTranslationKey()
        ),
        new Item.Settings()
    );

    public static final ExtendedScreenHandlerType<MineBotScreenHandler, MineBotScreenOpeningData> MINEBOT_SCREEN_HANDLER =
        Registry.register(
            Registries.SCREEN_HANDLER,
            id("minebot"),
            new ExtendedScreenHandlerType<>(MineBotScreenHandler::new, MineBotScreenOpeningData.PACKET_CODEC)
        );

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(MineBotCameraRequestPayload.ID, MineBotCameraRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().registerLarge(
            MineBotCameraResponsePayload.ID,
            MineBotCameraResponsePayload.CODEC,
            MineBotCameraResponsePayload.MAX_IMAGE_BYTES + 4_096
        );
        MineBotCameraBridge.initialize();
        MineBotChat.initialize();
        FabricDefaultAttributeRegistry.register(MINEBOT_ENTITY, MineBotEntity.createAttributes());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> entries.add(COMPUTER_BLOCK_ITEM));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> entries.add(CAMERA_BLOCK_ITEM));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(MINEBOT_SPAWN_EGG));
        UseBlockCallback.EVENT.register(MineBotSummoning::onUseBlock);
        ServerLifecycleEvents.SERVER_STARTED.register(MineBotWebSocketService::startForServer);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> MineBotCameraAssets.preload());
        ServerLifecycleEvents.SERVER_STOPPING.register(MineBotWebSocketService::stopForServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(MineBotChunkLoader::stopForServer);

        LOGGER.info("MineBot initialized for Fabric {}", FabricLoader.getInstance().getEnvironmentType());
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    private static Block registerBlock(
        String path,
        Function<AbstractBlock.Settings, Block> factory,
        AbstractBlock.Settings settings
    ) {
        RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, id(path));
        return Registry.register(Registries.BLOCK, id(path), factory.apply(settings.registryKey(key)));
    }

    private static Item registerItem(String path, Function<Item.Settings, Item> factory, Item.Settings settings) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, id(path));
        return Registry.register(Registries.ITEM, id(path), factory.apply(settings.registryKey(key)));
    }
}
