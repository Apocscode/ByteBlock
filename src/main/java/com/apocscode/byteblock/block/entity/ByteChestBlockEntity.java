package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ByteChestBlock;
import com.apocscode.byteblock.compat.Ae2GridNodeBridge;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.UUID;

/**
 * ByteChest block entity — 27-slot inventory with Bluetooth device registration.
 *
 * When the Materials Calculator on a connected Computer performs a storage scan,
 * it queries all reachable ByteChests (and optionally AE2 ME networks via ModLinkRegistry)
 * to report which required materials are available and in what quantities.
 */
public class ByteChestBlockEntity extends RandomizableContainerBlockEntity {

    private static final int SLOTS = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private UUID deviceId = UUID.randomUUID();
    /** Player-defined label. Empty string = use default name. */
    private String label = "";
    /** Paint tint as 0xRRGGBB. White (0xFFFFFF) = no visible tint. */
    private int tint = 0xFFFFFF;

    // Logistics settings
    public static final int PULL_FILTER_COUNT = 6;
    private boolean pullEnabled = false;
    /** Legacy single id — kept in sync with pullFilterIds[0] for backward compat. */
    private String pullItemId = "";
    /** Up to 6 item ids the chest will keep stocked from AE2. Empty entries are skipped. */
    private final String[] pullFilterIds = new String[PULL_FILTER_COUNT];
    private int keepAmount = 0;
    private boolean pushEnabled = false;
    private Direction pushSide = Direction.NORTH;
    private int movePerTick = 64;

    {
        java.util.Arrays.fill(pullFilterIds, "");
    }

    /** Client-side set of loaded ByteChests — used by GpsToolOverlay to draw labels/wireframes. */
    public static final java.util.Set<ByteChestBlockEntity> CLIENT_LOADED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // AE2 grid node — allows cables to visually connect to this block.
    @javax.annotation.Nullable
    private Ae2GridNodeBridge ae2Bridge = null;

    public ByteChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BYTE_CHEST.get(), pos, state);
        if (ModList.get().isLoaded("ae2")) {
            ae2Bridge = new Ae2GridNodeBridge(this);
        }
    }

    /** Returns the AE2 grid node host (used by capability registration). */
    @javax.annotation.Nullable
    public Ae2GridNodeBridge getAe2Bridge() { return ae2Bridge; }

    // ── Server tick: keep BT registration alive ───────────────────────────────

    public void serverTick() {
        if (level == null || level.isClientSide()) return;
        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.BYTE_CHEST);
        // Keep label registry in sync each tick (cheap; ConcurrentHashMap put).
        BluetoothNetwork.setChestLabel(deviceId, label);
        // Update BT indicator LED every second
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ByteChestBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(ByteChestBlock.CONNECTED, connected));
            }
            tickLogistics();
        }
    }

    private void tickLogistics() {
        tickPullFromAe2();
        tickPushAdjacent();
    }

    private long lastPullDiagTick = -1000L;
    private long lastPushDiagTick = -1000L;
    private static final org.slf4j.Logger LOGI_LOG = org.slf4j.LoggerFactory.getLogger("ByteChest/Logistics");

    private void tickPullFromAe2() {
        if (!pullEnabled) {
            return;
        }
        if (keepAmount <= 0) {
            if (level != null && level.getGameTime() - lastPullDiagTick >= 100L) {
                lastPullDiagTick = level.getGameTime();
                LOGI_LOG.warn("[ByteChest pull-diag] pos={} pullEnabled=true but keepAmount=0 (set Keep > 0 in Logistics tab)", worldPosition);
            }
            return;
        }

        // Build the active filter list (non-blank entries; legacy single id as fallback).
        java.util.List<String> active = new java.util.ArrayList<>(PULL_FILTER_COUNT);
        for (String id : pullFilterIds) {
            if (id != null && !id.isBlank()) active.add(id);
        }
        if (active.isEmpty() && pullItemId != null && !pullItemId.isBlank()) active.add(pullItemId);
        if (active.isEmpty()) return;

        int budget = Math.max(1, movePerTick);
        int totalMoved = 0;

        for (String id : active) {
            if (budget <= 0) break;
            long have = countItemById(id);
            if (have >= keepAmount) continue;
            int need = (int) Math.min(Integer.MAX_VALUE, keepAmount - have);
            int request = Math.min(need, budget);
            if (request <= 0) continue;

            // 1) Prefer the chest's own grid node (works no matter which side the AE2
            //    cable touches, and even if no neighbouring block is an AE2 host).
            if (ae2Bridge != null) {
                int moved = AE2PeripheralAdapter.extractToInventoryJavaFromHost(
                        ae2Bridge, id, request, this);
                if (moved > 0) {
                    request -= moved;
                    budget -= moved;
                    totalMoved += moved;
                    setChanged();
                }
            }

            // 2) Fallback: probe neighbouring AE2 grid hosts directly.
            for (Direction dir : Direction.values()) {
                if (request <= 0) break;
                BlockEntity be = level.getBlockEntity(worldPosition.relative(dir));
                if (be == null) continue;
                int moved = AE2PeripheralAdapter.extractToInventoryJava(be, id, request, this);
                if (moved > 0) {
                    request -= moved;
                    budget -= moved;
                    totalMoved += moved;
                    setChanged();
                }
            }
        }

        // Diagnostic: if pull is enabled with active filters but nothing moved,
        // log a one-shot reason every 5s so we can see what's wrong.
        if (totalMoved == 0 && level != null && level.getGameTime() - lastPullDiagTick >= 100L) {
            lastPullDiagTick = level.getGameTime();
            logPullDiagnostics(active);
        }
    }

    private void logPullDiagnostics(java.util.List<String> active) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("ByteChest/PullDiag");
        StringBuilder sb = new StringBuilder();
        sb.append("pos=").append(worldPosition)
          .append(" filters=").append(active)
          .append(" keep=").append(keepAmount)
          .append(" budget=").append(movePerTick);
        sb.append(" bridge=").append(ae2Bridge != null);
        if (ae2Bridge != null) {
            String diag = AE2PeripheralAdapter.diagnoseHost(ae2Bridge,
                    active.isEmpty() ? null : active.get(0));
            sb.append(" [").append(diag).append("]");
        }
        // Neighbour AE2 hosts
        sb.append(" neighbours=");
        boolean any = false;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(worldPosition.relative(dir));
            if (be != null && AE2PeripheralAdapter.isAe2GridHost(be)) {
                if (any) sb.append(',');
                sb.append(dir).append("(").append(be.getClass().getSimpleName()).append(")");
                any = true;
            }
        }
        if (!any) sb.append("none");
        // Full storage state if we already had to "have >= keepAmount" for every item, the for loop continues quietly — note that.
        for (String id : active) {
            sb.append(" have(").append(id).append(")=").append(countItemById(id));
        }
        log.warn("[ByteChest pull-diag] {}", sb);
    }

    private void tickPushAdjacent() {
        if (!pushEnabled || movePerTick <= 0) return;
        if (pushSide == null) pushSide = Direction.NORTH;

        BlockPos targetPos = worldPosition.relative(pushSide);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, pushSide.getOpposite());
        if (handler == null) {
            if (level.getGameTime() - lastPushDiagTick >= 100L) {
                lastPushDiagTick = level.getGameTime();
                BlockState ts = level.getBlockState(targetPos);
                String blockName = ts.isAir() ? "air" : ts.getBlock().getName().getString();
                LOGI_LOG.warn("[ByteChest push-diag] pos={} side={} target={} blockAt={} -> NO ItemHandler capability (block can't accept items)",
                        worldPosition, pushSide, targetPos, blockName);
            }
            return;
        }

        int budget = Math.max(1, movePerTick);
        int totalMoved = 0;
        boolean changed = false;
        boolean anyContent = false;

        for (int slot = 0; slot < getContainerSize() && budget > 0; slot++) {
            ItemStack stack = getItem(slot);
            if (stack.isEmpty()) continue;
            anyContent = true;

            int toMove = Math.min(stack.getCount(), budget);
            ItemStack attempt = stack.copyWithCount(toMove);
            ItemStack remainder = ItemHandlerHelper.insertItem(handler, attempt, false);
            int moved = toMove - remainder.getCount();
            if (moved <= 0) continue;

            stack.shrink(moved);
            setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            budget -= moved;
            totalMoved += moved;
            changed = true;
        }

        if (changed) setChanged();

        if (totalMoved == 0 && level.getGameTime() - lastPushDiagTick >= 100L) {
            lastPushDiagTick = level.getGameTime();
            BlockState ts = level.getBlockState(targetPos);
            String blockName = ts.isAir() ? "air" : ts.getBlock().getName().getString();
            String reason = !anyContent
                    ? "chest is EMPTY (nothing to push)"
                    : "target rejected all items (full or filtered)";
            LOGI_LOG.warn("[ByteChest push-diag] pos={} side={} target={} blockAt={} reason={}",
                    worldPosition, pushSide, targetPos, blockName, reason);
        }
    }

    private long countItemById(String itemId) {
        long total = 0;
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = getItem(i);
            if (s.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (itemId.equals(id)) total += s.getCount();
        }
        return total;
    }

    // ── UUID / Label accessors ────────────────────────────────────────────────

    public UUID getDeviceId() { return deviceId; }

    public String getLabel() { return label == null ? "" : label; }

    public void setLabel(String newLabel) {
        this.label = newLabel == null ? "" : newLabel;
        if (this.label.length() > 32) this.label = this.label.substring(0, 32);
        BluetoothNetwork.setChestLabel(deviceId, this.label);
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, st, st, Block.UPDATE_CLIENTS);
        }
    }

    public int getTint() { return tint; }

    public void setTint(int newTint) {
        this.tint = newTint & 0xFFFFFF;
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, st, st, Block.UPDATE_CLIENTS);
        } else if (level != null && level.isClientSide()) {
            // Force model re-tint immediately on client.
            net.minecraft.client.Minecraft.getInstance().levelRenderer
                .blockChanged(level, worldPosition, level.getBlockState(worldPosition),
                              level.getBlockState(worldPosition), 0);
        }
    }

    public boolean isPullEnabled() { return pullEnabled; }
    public String getPullItemId() { return pullItemId == null ? "" : pullItemId; }
    public int getKeepAmount() { return keepAmount; }
    public boolean isPushEnabled() { return pushEnabled; }
    public Direction getPushSide() { return pushSide == null ? Direction.NORTH : pushSide; }
    public int getMovePerTick() { return movePerTick; }

    /** Returns a defensive copy of the 6 filter item ids (empty string = unused). */
    public String[] getPullFilterIds() {
        String[] out = new String[PULL_FILTER_COUNT];
        for (int i = 0; i < PULL_FILTER_COUNT; i++) {
            out[i] = pullFilterIds[i] == null ? "" : pullFilterIds[i];
        }
        return out;
    }

    public void applyLogisticsConfig(boolean pullEnabled, String pullItemId, int keepAmount,
                                     boolean pushEnabled, Direction pushSide, int movePerTick,
                                     String[] filterIds) {
        this.pullEnabled = pullEnabled;
        this.pullItemId = pullItemId == null ? "" : pullItemId.trim();
        if (this.pullItemId.length() > 120) this.pullItemId = this.pullItemId.substring(0, 120);
        this.keepAmount = Math.max(0, keepAmount);
        this.pushEnabled = pushEnabled;
        this.pushSide = pushSide == null ? Direction.NORTH : pushSide;
        this.movePerTick = Math.max(1, Math.min(1024, movePerTick));
        for (int i = 0; i < PULL_FILTER_COUNT; i++) {
            String v = (filterIds != null && i < filterIds.length && filterIds[i] != null)
                    ? filterIds[i].trim() : "";
            if (v.length() > 120) v = v.substring(0, 120);
            this.pullFilterIds[i] = v;
        }
        // Keep legacy single-id mirror in sync with the first filter slot for compat.
        if (this.pullItemId.isEmpty() && !this.pullFilterIds[0].isEmpty()) {
            this.pullItemId = this.pullFilterIds[0];
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, st, st, Block.UPDATE_CLIENTS);
        }
        // Reset diag throttle so first failure logs immediately after save.
        this.lastPullDiagTick = -1000L;
        this.lastPushDiagTick = -1000L;
        LOGI_LOG.info("[ByteChest config-saved] pos={} pull={} keep={} filters={} push={} side={} mpt={}",
                worldPosition, this.pullEnabled, this.keepAmount,
                java.util.Arrays.toString(this.pullFilterIds),
                this.pushEnabled, this.pushSide, this.movePerTick);
    }

    // ── RandomizableContainerBlockEntity implementation ───────────────────────

    @Override
    protected NonNullList<ItemStack> getItems() { return items; }

    @Override
    protected void setItems(NonNullList<ItemStack> pItems) { items = pItems; }

    @Override
    public int getContainerSize() { return SLOTS; }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.byteblock.byte_chest");
    }

    @Override
    public Component getDisplayName() {
        return label.isEmpty() ? getDefaultName() : Component.literal(label);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInv) {
        return ChestMenu.threeRows(containerId, playerInv, this);
    }

    // ── NBT persistence ───────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
        tag.putString("deviceId", deviceId.toString());
        if (!label.isEmpty()) tag.putString("Label", label);
        if (tint != 0xFFFFFF) tag.putInt("Tint", tint);
        tag.putBoolean("PullEnabled", pullEnabled);
        if (!pullItemId.isEmpty()) tag.putString("PullItemId", pullItemId);
        // Persist 6 filter ids (only non-empty entries to keep NBT small).
        CompoundTag filters = new CompoundTag();
        for (int i = 0; i < PULL_FILTER_COUNT; i++) {
            if (pullFilterIds[i] != null && !pullFilterIds[i].isEmpty()) {
                filters.putString(Integer.toString(i), pullFilterIds[i]);
            }
        }
        if (!filters.isEmpty()) tag.put("PullFilters", filters);
        tag.putInt("KeepAmount", keepAmount);
        tag.putBoolean("PushEnabled", pushEnabled);
        tag.putInt("PushSide", getPushSide().get3DDataValue());
        tag.putInt("MovePerTick", movePerTick);
        if (ae2Bridge != null) ae2Bridge.saveToNBT(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
        if (tag.contains("deviceId")) {
            try {
                deviceId = UUID.fromString(tag.getString("deviceId"));
            } catch (IllegalArgumentException ignored) {}
        }
        label = tag.contains("Label") ? tag.getString("Label") : "";
        tint = tag.contains("Tint") ? (tag.getInt("Tint") & 0xFFFFFF) : 0xFFFFFF;
        pullEnabled = tag.getBoolean("PullEnabled");
        pullItemId = tag.contains("PullItemId") ? tag.getString("PullItemId") : "";
        java.util.Arrays.fill(pullFilterIds, "");
        if (tag.contains("PullFilters")) {
            CompoundTag filters = tag.getCompound("PullFilters");
            for (int i = 0; i < PULL_FILTER_COUNT; i++) {
                String key = Integer.toString(i);
                if (filters.contains(key)) pullFilterIds[i] = filters.getString(key);
            }
        }
        // Backfill slot 0 from legacy single id if needed.
        if (pullFilterIds[0].isEmpty() && !pullItemId.isEmpty()) pullFilterIds[0] = pullItemId;
        keepAmount = Math.max(0, tag.getInt("KeepAmount"));
        pushEnabled = tag.getBoolean("PushEnabled");
        pushSide = Direction.from3DDataValue(tag.getInt("PushSide"));
        movePerTick = tag.contains("MovePerTick") ? Math.max(1, Math.min(1024, tag.getInt("MovePerTick"))) : 64;
        if (ae2Bridge != null) ae2Bridge.loadFromNBT(tag);
    }

    // ── Client sync (label needs to render on the chest popup HUD) ────────────

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("Label", label);
        tag.putString("deviceId", deviceId.toString());
        tag.putInt("Tint", tint);
        tag.putBoolean("PullEnabled", pullEnabled);
        if (!pullItemId.isEmpty()) tag.putString("PullItemId", pullItemId);
        CompoundTag filters = new CompoundTag();
        for (int i = 0; i < PULL_FILTER_COUNT; i++) {
            if (pullFilterIds[i] != null && !pullFilterIds[i].isEmpty()) {
                filters.putString(Integer.toString(i), pullFilterIds[i]);
            }
        }
        if (!filters.isEmpty()) tag.put("PullFilters", filters);
        tag.putInt("KeepAmount", keepAmount);
        tag.putBoolean("PushEnabled", pushEnabled);
        tag.putInt("PushSide", getPushSide().get3DDataValue());
        tag.putInt("MovePerTick", movePerTick);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (tag.contains("Label")) label = tag.getString("Label");
        if (tag.contains("Tint")) tint = tag.getInt("Tint") & 0xFFFFFF;
        pullEnabled = tag.getBoolean("PullEnabled");
        pullItemId = tag.contains("PullItemId") ? tag.getString("PullItemId") : "";
        java.util.Arrays.fill(pullFilterIds, "");
        if (tag.contains("PullFilters")) {
            CompoundTag filters = tag.getCompound("PullFilters");
            for (int i = 0; i < PULL_FILTER_COUNT; i++) {
                String key = Integer.toString(i);
                if (filters.contains(key)) pullFilterIds[i] = filters.getString(key);
            }
        }
        if (pullFilterIds[0].isEmpty() && !pullItemId.isEmpty()) pullFilterIds[0] = pullItemId;
        keepAmount = Math.max(0, tag.getInt("KeepAmount"));
        pushEnabled = tag.getBoolean("PushEnabled");
        pushSide = Direction.from3DDataValue(tag.getInt("PushSide"));
        movePerTick = tag.contains("MovePerTick") ? Math.max(1, Math.min(1024, tag.getInt("MovePerTick"))) : 64;
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                              ClientboundBlockEntityDataPacket pkt,
                              HolderLookup.Provider registries) {
        super.onDataPacket(connection, pkt, registries);
        // Force chunk re-tint after server pushes a tint change.
        if (level != null && level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            net.minecraft.client.Minecraft.getInstance().levelRenderer
                .blockChanged(level, worldPosition, st, st, 0);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Client-side load tracking (for GpsToolOverlay) ──────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) CLIENT_LOADED.add(this);
        if (ae2Bridge != null && level != null && !level.isClientSide()) {
            ae2Bridge.create(level, worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && level.isClientSide()) CLIENT_LOADED.remove(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) CLIENT_LOADED.remove(this);
        if (ae2Bridge != null) ae2Bridge.destroy();
    }
}
