package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.ChargingStationBlock;
import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: assign the nearest charging station as the entity's home charge pad.
 * Scans within 48 blocks of the entity on the server and stores the result.
 */
public record SetChargePadPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetChargePadPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_charge_pad"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetChargePadPayload> STREAM_CODEC =
        StreamCodec.of(SetChargePadPayload::write, SetChargePadPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetChargePadPayload p) {
        buf.writeInt(p.entityId);
    }

    private static SetChargePadPayload read(RegistryFriendlyByteBuf buf) {
        return new SetChargePadPayload(buf.readInt());
    }

    public static void handle(SetChargePadPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity entity = player.level().getEntity(payload.entityId);
            if (entity == null || player.distanceToSqr(entity) > 256) return; // 16 blocks
            BlockPos origin = entity.blockPosition();
            BlockPos best = findNearestPad(player.level(), origin, 48);
            if (best == null) return;
            if (entity instanceof RobotEntity robot) {
                robot.setChargePad(best);
            } else if (entity instanceof DroneEntity drone) {
                drone.setChargePad(best);
            }
        });
    }

    private static BlockPos findNearestPad(net.minecraft.world.level.Level level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof ChargingStationBlock) {
                        double d = p.distSqr(origin);
                        if (d < bestDist) { bestDist = d; best = p.immutable(); }
                    }
                }
            }
        }
        return best;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
