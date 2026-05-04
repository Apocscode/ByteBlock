package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.DroneEntity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: set the drone's home position to its current block position.
 */
public record SetDroneHomePayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetDroneHomePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_drone_home"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetDroneHomePayload> STREAM_CODEC =
        StreamCodec.of(SetDroneHomePayload::write, SetDroneHomePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetDroneHomePayload p) {
        buf.writeInt(p.entityId);
    }

    private static SetDroneHomePayload read(RegistryFriendlyByteBuf buf) {
        return new SetDroneHomePayload(buf.readInt());
    }

    public static void handle(SetDroneHomePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity entity = player.level().getEntity(payload.entityId);
            if (entity instanceof DroneEntity drone) {
                if (player.distanceToSqr(entity) <= 256) {
                    drone.setHomePos(drone.blockPosition());
                }
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
