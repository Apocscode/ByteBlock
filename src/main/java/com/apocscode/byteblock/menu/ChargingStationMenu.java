package com.apocscode.byteblock.menu;

import com.apocscode.byteblock.block.entity.ChargingStationBlockEntity;
import com.apocscode.byteblock.init.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu for the Charging Station block. Has no slots — purely informational.
 * The screen reads {@link ChargingStationBlockEntity} energy + neighbor compatibility
 * directly each frame.
 */
public class ChargingStationMenu extends AbstractContainerMenu {
    private final ChargingStationBlockEntity station;
    private final BlockPos pos;
    // Indices: 0 = energy low 16 bits, 1 = energy high 16 bits, 2 = maxEnergy low, 3 = maxEnergy high
    private final ContainerData data;
    // Client-side cache: filled by sync packets from the server.
    private final int[] syncCache = new int[4];

    public ChargingStationMenu(int containerId, Inventory playerInv, ChargingStationBlockEntity station) {
        super(ModMenuTypes.CHARGING_STATION.get(), containerId);
        this.station = station;
        this.pos = station.getBlockPos();
        this.data = new ContainerData() {
            @Override public int get(int index) {
                if (index < 0 || index >= 4) return 0;
                // On the server, read live values from the BE every tick so the
                // AbstractContainerMenu sync machinery sends updates to the client.
                net.minecraft.world.level.Level lvl = station.getLevel();
                if (lvl != null && !lvl.isClientSide()) {
                    return switch (index) {
                        case 0 -> station.getEnergyStored() & 0xFFFF;
                        case 1 -> (station.getEnergyStored() >> 16) & 0xFFFF;
                        case 2 -> station.getMaxEnergy() & 0xFFFF;
                        case 3 -> (station.getMaxEnergy() >> 16) & 0xFFFF;
                        default -> 0;
                    };
                }
                // On the client, return the last value pushed by a sync packet.
                return syncCache[index];
            }
            // Called by the vanilla sync machinery on the CLIENT when the server
            // sends an updated value — must store it so get() can return it.
            @Override public void set(int index, int value) {
                if (index >= 0 && index < 4) syncCache[index] = value;
            }
            @Override public int getCount() { return 4; }
        };
        addDataSlots(this.data);
    }

    public static ChargingStationMenu fromNetwork(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        if (!(be instanceof ChargingStationBlockEntity station)) {
            throw new IllegalStateException("ChargingStation block entity not found at " + pos);
        }
        return new ChargingStationMenu(containerId, playerInv, station);
    }

    /** Returns the synced energy stored — safe to call on the client. */
    public int getSyncedEnergyStored() {
        return (data.get(1) << 16) | (data.get(0) & 0xFFFF);
    }

    /** Returns the synced max energy — safe to call on the client. */
    public int getSyncedMaxEnergy() {
        int v = (data.get(3) << 16) | (data.get(2) & 0xFFFF);
        return v == 0 ? 100_000 : v;
    }

    public ChargingStationBlockEntity getStation() { return station; }
    public BlockPos getPos() { return pos; }

    @Override
    public boolean stillValid(Player player) {
        return station.getLevel() != null
                && !station.isRemoved()
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0;
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
