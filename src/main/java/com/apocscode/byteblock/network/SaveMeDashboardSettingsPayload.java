package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.ComputerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> Server packet to persist ME Dashboard settings on the authoritative
 * server-side computer state.
 */
public record SaveMeDashboardSettingsPayload(BlockPos pos, String compactData, String fullData)
        implements CustomPacketPayload {

    private static final int MAX_DATA = 32768;

    public static final CustomPacketPayload.Type<SaveMeDashboardSettingsPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "save_me_dashboard_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveMeDashboardSettingsPayload> STREAM_CODEC =
            StreamCodec.of(SaveMeDashboardSettingsPayload::write, SaveMeDashboardSettingsPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SaveMeDashboardSettingsPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.compactData, MAX_DATA);
        buf.writeUtf(payload.fullData, MAX_DATA);
    }

    private static SaveMeDashboardSettingsPayload read(RegistryFriendlyByteBuf buf) {
        return new SaveMeDashboardSettingsPayload(
                buf.readBlockPos(),
                buf.readUtf(MAX_DATA),
                buf.readUtf(MAX_DATA));
    }

    public static void handle(SaveMeDashboardSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof ComputerBlockEntity computer) {
                computer.writeMeDashboardSettings(payload.compactData, payload.fullData);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
