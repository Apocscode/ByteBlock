package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;
import com.apocscode.byteblock.entity.UnicycleRobotEntity;
import com.apocscode.byteblock.init.ModItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: pack up a robot or drone back into its spawn egg,
 * preserving all inventory, energy, OS filesystem, paint, and other state in
 * the item's ENTITY_DATA component so it fully restores when re-placed.
 */
public record PackUpEntityPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PackUpEntityPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "pack_up_entity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PackUpEntityPayload> STREAM_CODEC =
        StreamCodec.of(PackUpEntityPayload::write, PackUpEntityPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, PackUpEntityPayload p) {
        buf.writeInt(p.entityId);
    }

    private static PackUpEntityPayload read(RegistryFriendlyByteBuf buf) {
        return new PackUpEntityPayload(buf.readInt());
    }

    public static void handle(PackUpEntityPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity entity = player.level().getEntity(payload.entityId);
            if (entity == null || !entity.isAlive()) return;
            // Require player to be within 10 blocks
            if (player.distanceToSqr(entity) > 100) return;

            boolean isRobot = entity instanceof RobotEntity;
            boolean isDrone = entity instanceof DroneEntity;
            if (!isRobot && !isDrone) return;

            // Serialize full entity state.
            // saveWithoutId omits the "id" field, but DataComponents.ENTITY_DATA
            // uses CODEC_WITH_ID which requires it — add it manually.
            CompoundTag nbt = entity.saveWithoutId(new CompoundTag());
            nbt.putString("id", net.minecraft.world.entity.EntityType
                    .getKey(entity.getType()).toString());
            nbt.remove("Pos");
            nbt.remove("Rotation");
            nbt.remove("Motion");

            // Pick the matching spawn egg item.
            ItemStack egg;
            if (entity instanceof UnicycleRobotEntity) {
                egg = new ItemStack(ModItems.UNICYCLE_ROBOT_SPAWN_EGG.get());
            } else if (isRobot) {
                egg = new ItemStack(ModItems.ROBOT_SPAWN_EGG.get());
            } else {
                egg = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
            }
            // Embed the entity state so it restores on re-place.
            egg.set(DataComponents.ENTITY_DATA, CustomData.of(nbt));

            // Give to player; drop at feet if inventory is full.
            if (!player.getInventory().add(egg)) {
                player.drop(egg, false);
            }

            // Remove entity — stillValid() will return false and auto-close the GUI.
            entity.discard();
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
