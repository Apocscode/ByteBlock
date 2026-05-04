package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: set the Bluetooth channel on a drone or robot entity.
 */
public record SetEntityChannelPayload(int entityId, int channel) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetEntityChannelPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_entity_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetEntityChannelPayload> STREAM_CODEC =
        StreamCodec.of(SetEntityChannelPayload::write, SetEntityChannelPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetEntityChannelPayload p) {
        buf.writeInt(p.entityId);
        buf.writeInt(p.channel);
    }

    private static SetEntityChannelPayload read(RegistryFriendlyByteBuf buf) {
        return new SetEntityChannelPayload(buf.readInt(), buf.readInt());
    }

    public static void handle(SetEntityChannelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity entity = player.level().getEntity(payload.entityId);
            if (entity == null || player.distanceToSqr(entity) > 256) return;
            int ch = Math.max(1, Math.min(65535, payload.channel));
            if (entity instanceof DroneEntity drone) {
                drone.setBluetoothChannel(ch);
            } else if (entity instanceof RobotEntity robot) {
                robot.setBluetoothChannel(ch);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
