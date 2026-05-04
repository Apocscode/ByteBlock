package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.PeripheralBlock;
import com.apocscode.byteblock.compat.Ae2GridNodeBridge;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Universal Peripheral block entity. Detects adjacent blocks
 * and exposes their capabilities (inventory, energy, fluid, etc.)
 * to connected computers.
 *
 * <p>When placed adjacent to an AE2 network block (cable, controller, etc.)
 * it automatically queries the ME network every 40 ticks and broadcasts a
 * compact snapshot over <b>Bluetooth channel 9200</b> so ME Dashboard
 * computers can receive live data wirelessly.</p>
 *
 * <p>Message format — one broadcast per category:
 * <pre>
 *   ae2:energy:{"stored":1234.5,"cap":5000.0,"usage":12.3,"inject":20.0,"on":true}
 *   ae2:items:[{"n":"minecraft:iron_ingot","d":"Iron Ingot","c":128,"craft":false},...]
 *   ae2:fluids:[{"n":"minecraft:water","c":16000},...]
 *   ae2:crafting:[{"item":"Iron Ingot","cpu":"CPU #1","done":10,"total":64,"ns":5000000000},...]
 *   ae2:nodes:42
 * </pre>
 * </p>
 */
public class PeripheralBlockEntity extends BlockEntity {

    /** Bluetooth channel used for AE2 wireless broadcasts. */
    public static final int AE2_BT_CHANNEL = 9200;

    private java.util.UUID deviceId = java.util.UUID.randomUUID();
    private String detectedType = "none";

    // AE2 wireless bridge state
    private BlockEntity cachedMeNode = null;
    private long lastAe2Refresh = -1;
    private static final int AE2_REFRESH_TICKS = 40;

    // AE2 grid node — allows cables to visually connect to this block.
    // Typed as Ae2GridNodeBridge (not Object) — safe because HotSpot resolves
    // field types lazily; callers guard with ModList.get().isLoaded("ae2").
    @javax.annotation.Nullable
    private Ae2GridNodeBridge ae2Bridge = null;

    public PeripheralBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PERIPHERAL.get(), pos, state);
        if (ModList.get().isLoaded("ae2")) {
            ae2Bridge = new Ae2GridNodeBridge(this);
        }
    }

    /** Returns the AE2 grid node host for this block (used by capability registration). */
    @javax.annotation.Nullable
    public Ae2GridNodeBridge getAe2Bridge() { return ae2Bridge; }

    public void serverTick() {
        if (level == null || level.isClientSide()) return;

        BluetoothNetwork.register(level, deviceId, worldPosition, AE2_BT_CHANNEL, BluetoothNetwork.DeviceType.PERIPHERAL);

        // Update CONNECTED blockstate every second
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition)
                    || (cachedMeNode != null);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(PeripheralBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(PeripheralBlock.CONNECTED, connected));
            }
        }

        // AE2 wireless bridge — broadcast ME network data every 40 ticks
        if (ModList.get().isLoaded("ae2")
                && (level.getGameTime() - lastAe2Refresh >= AE2_REFRESH_TICKS || lastAe2Refresh < 0)) {
            lastAe2Refresh = level.getGameTime();
            scanAndBroadcastAe2();
        }
    }

    // ── AE2 Wireless Bridge ───────────────────────────────────────────────

    private void scanAndBroadcastAe2() {
        // Find adjacent AE2 node (re-scan every refresh in case blocks changed)
        cachedMeNode = null;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(worldPosition.relative(dir));
            if (be != null && AE2PeripheralAdapter.isAvailableJava(be)) {
                cachedMeNode = be;
                break;
            }
        }
        if (cachedMeNode == null) return;

        // Energy
        AE2PeripheralAdapter.AEEnergyInfo eng = AE2PeripheralAdapter.queryEnergyJava(cachedMeNode);
        if (eng != null) {
            String msg = "ae2:energy:{\"stored\":" + fmt(eng.stored())
                    + ",\"cap\":" + fmt(eng.capacity())
                    + ",\"usage\":" + fmt(eng.avgUsage())
                    + ",\"inject\":" + fmt(eng.avgInjection())
                    + ",\"on\":" + eng.powered() + "}";
            BluetoothNetwork.broadcast(level, worldPosition, AE2_BT_CHANNEL, msg);
        }

        // Items (cap at 200 entries to keep message size sane)
        List<AE2PeripheralAdapter.AEItemEntry> items = AE2PeripheralAdapter.queryItemsJava(cachedMeNode);
        if (!items.isEmpty()) {
            StringBuilder sb = new StringBuilder("ae2:items:[");
            int limit = Math.min(items.size(), 200);
            for (int i = 0; i < limit; i++) {
                AE2PeripheralAdapter.AEItemEntry e = items.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"n\":\"").append(escJson(e.name()))
                  .append("\",\"d\":\"").append(escJson(e.displayName()))
                  .append("\",\"c\":").append(e.count())
                  .append(",\"craft\":").append(e.craftable()).append('}');
            }
            sb.append(']');
            BluetoothNetwork.broadcast(level, worldPosition, AE2_BT_CHANNEL, sb.toString());
        }

        // Fluids (cap at 100)
        List<AE2PeripheralAdapter.AEFluidEntry> fluids = AE2PeripheralAdapter.queryFluidsJava(cachedMeNode);
        if (!fluids.isEmpty()) {
            StringBuilder sb = new StringBuilder("ae2:fluids:[");
            int limit = Math.min(fluids.size(), 100);
            for (int i = 0; i < limit; i++) {
                AE2PeripheralAdapter.AEFluidEntry e = fluids.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"n\":\"").append(escJson(e.name()))
                  .append("\",\"c\":").append(e.amountMb()).append('}');
            }
            sb.append(']');
            BluetoothNetwork.broadcast(level, worldPosition, AE2_BT_CHANNEL, sb.toString());
        }

        // Crafting jobs
        List<AE2PeripheralAdapter.AECraftingJob> jobs = AE2PeripheralAdapter.queryCraftingJobsJava(cachedMeNode);
        if (!jobs.isEmpty()) {
            StringBuilder sb = new StringBuilder("ae2:crafting:[");
            for (int i = 0; i < jobs.size(); i++) {
                AE2PeripheralAdapter.AECraftingJob j = jobs.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"item\":\"").append(escJson(j.itemName()))
                  .append("\",\"cpu\":\"").append(escJson(j.cpuName()))
                  .append("\",\"done\":").append(j.doneItems())
                  .append(",\"total\":").append(j.totalItems())
                  .append(",\"ns\":").append(j.elapsedNanos()).append('}');
            }
            sb.append(']');
            BluetoothNetwork.broadcast(level, worldPosition, AE2_BT_CHANNEL, sb.toString());
        }

        // Node count
        int nodeCount = AE2PeripheralAdapter.queryNodeCountJava(cachedMeNode);
        BluetoothNetwork.broadcast(level, worldPosition, AE2_BT_CHANNEL, "ae2:nodes:" + nodeCount);
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }

    /** Minimal JSON string escaping — only handles quotes and backslashes. */
    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Type detection (legacy, used for right-click tooltip) ─────────────

    public java.util.UUID getDeviceId() { return deviceId; }

    public boolean isAe2Bridge() { return cachedMeNode != null; }

    /**
     * Scans all 6 adjacent blocks and determines what type of peripheral they represent.
     */
    public String getDetectedType() {
        if (level == null) return "none";
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(dir);
            BlockEntity adjacentBE = level.getBlockEntity(adjacent);
            if (adjacentBE != null) {
                // AE2 check first
                if (ModList.get().isLoaded("ae2") && AE2PeripheralAdapter.isAvailableJava(adjacentBE)) {
                    detectedType = "me_network";
                    return detectedType;
                }
                String name = adjacentBE.getClass().getSimpleName();
                if (name.toLowerCase().contains("chest") || name.toLowerCase().contains("barrel")) {
                    detectedType = "inventory";
                    return detectedType;
                } else if (name.toLowerCase().contains("furnace") || name.toLowerCase().contains("smoker") || name.toLowerCase().contains("blast")) {
                    detectedType = "furnace";
                    return detectedType;
                } else if (adjacentBE instanceof ComputerBlockEntity) {
                    detectedType = "computer";
                    return detectedType;
                } else {
                    detectedType = "generic:" + name;
                    return detectedType;
                }
            }
        }
        detectedType = "none";
        return detectedType;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (ae2Bridge != null && level != null && !level.isClientSide()) {
            ae2Bridge.create(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (ae2Bridge != null) ae2Bridge.destroy();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putString("DetectedType", detectedType);
        if (ae2Bridge != null) ae2Bridge.saveToNBT(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("DetectedType")) detectedType = tag.getString("DetectedType");
        if (ae2Bridge != null) ae2Bridge.loadFromNBT(tag);
    }
}
