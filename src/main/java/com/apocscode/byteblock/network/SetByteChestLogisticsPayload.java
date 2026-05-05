package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: update ByteChest logistics settings (AE2 pull + adjacent push). */
public record SetByteChestLogisticsPayload(
        BlockPos pos,
        boolean pullEnabled,
        String pullItemId,
        int keepAmount,
        boolean pushEnabled,
        int pushSide,
        int movePerTick,
        java.util.List<String> pullFilterIds
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetByteChestLogisticsPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_byte_chest_logistics"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetByteChestLogisticsPayload> STREAM_CODEC =
            StreamCodec.of(SetByteChestLogisticsPayload::write, SetByteChestLogisticsPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetByteChestLogisticsPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeBoolean(p.pullEnabled);
        buf.writeUtf(p.pullItemId, 120);
        buf.writeInt(p.keepAmount);
        buf.writeBoolean(p.pushEnabled);
        buf.writeInt(p.pushSide);
        buf.writeInt(p.movePerTick);
        java.util.List<String> ids = p.pullFilterIds == null ? java.util.List.of() : p.pullFilterIds;
        int n = Math.min(ids.size(), ByteChestBlockEntity.PULL_FILTER_COUNT);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(ids.get(i) == null ? "" : ids.get(i), 120);
        }
    }

    private static SetByteChestLogisticsPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean pull = buf.readBoolean();
        String item = buf.readUtf(120);
        int keep = buf.readInt();
        boolean push = buf.readBoolean();
        int side = buf.readInt();
        int move = buf.readInt();
        int n = Math.min(buf.readVarInt(), ByteChestBlockEntity.PULL_FILTER_COUNT);
        java.util.List<String> ids = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUtf(120));
        return new SetByteChestLogisticsPayload(pos, pull, item, keep, push, side, move, ids);
    }

    public static void handle(SetByteChestLogisticsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (!(level.getBlockEntity(payload.pos) instanceof ByteChestBlockEntity chest)) return;

            Direction side = Direction.from3DDataValue(payload.pushSide);
            int keep = Math.max(0, payload.keepAmount);
            int move = Math.max(1, Math.min(1024, payload.movePerTick));
            String itemId = payload.pullItemId == null ? "" : payload.pullItemId.trim();
            if (itemId.length() > 120) itemId = itemId.substring(0, 120);

            String[] filters = new String[ByteChestBlockEntity.PULL_FILTER_COUNT];
            java.util.Arrays.fill(filters, "");
            if (payload.pullFilterIds != null) {
                for (int i = 0; i < Math.min(payload.pullFilterIds.size(), filters.length); i++) {
                    String v = payload.pullFilterIds.get(i);
                    filters[i] = v == null ? "" : v.trim();
                    if (filters[i].length() > 120) filters[i] = filters[i].substring(0, 120);
                }
            }

            chest.applyLogisticsConfig(payload.pullEnabled, itemId, keep, payload.pushEnabled, side, move, filters);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
