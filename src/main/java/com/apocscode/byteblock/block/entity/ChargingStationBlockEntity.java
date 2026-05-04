package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ChargingStationBlock;
import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.energy.EnergyStorage;

import java.util.List;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Charging Station block entity — stores FE energy internally and
 * transfers it to nearby Robots and Drones every tick.
 *
 * Robots receive FE directly into their EnergyStorage.
 * Drones receive fuel ticks (1 FE = 1 fuel tick conversion).
 */
public class ChargingStationBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private java.util.UUID deviceId = java.util.UUID.randomUUID();
    private static final int MAX_ENERGY = 100_000;
    private static final int MAX_RECEIVE = 1000;  // RF/t input from pipes/cables
    private static final int CHARGE_RATE = 200;   // FE/t output to each entity
    private static final double RANGE = 3.0;
    private static final int FUEL_PER_FE = 2;     // 1 FE = 2 fuel ticks for drones

    private EnergyStorage energyStorage = new EnergyStorage(MAX_ENERGY, MAX_RECEIVE, CHARGE_RATE, 0);

    public ChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHARGING_STATION.get(), pos, state);
    }

    public void serverTick() {
        if (level == null) return;
        // Built-in passive energy generation — the station is always powered.
        // External FE sources (Mekanism cables/cubes, conduits, etc.) fill on top of this.
        energyStorage.receiveEnergy(MAX_RECEIVE, false);

        BluetoothNetwork.register(level, deviceId, worldPosition, 1, BluetoothNetwork.DeviceType.CHARGING_STATION);
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ChargingStationBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(ChargingStationBlock.CONNECTED, connected));
            }
        }
        if (energyStorage.getEnergyStored() < MAX_ENERGY) {
            int space = MAX_ENERGY - energyStorage.getEnergyStored();
            int canReceive = Math.min(space, MAX_RECEIVE);
            if (canReceive > 0) {
                for (Direction d : Direction.values()) {
                    if (canReceive <= 0) break;
                    BlockPos neighborPos = worldPosition.relative(d);
                    IEnergyStorage neighbor = level.getCapability(
                            Capabilities.EnergyStorage.BLOCK, neighborPos, d.getOpposite());
                    if (neighbor == null) {
                        // Fallback: some mods (e.g. Mekanism) register omni-directionally (null face)
                        neighbor = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighborPos, null);
                    }
                    if (neighbor != null && neighbor.canExtract()) {
                        int pulled = neighbor.extractEnergy(canReceive, false);
                        if (pulled > 0) {
                            energyStorage.receiveEnergy(pulled, false);
                            canReceive -= pulled;
                            setChanged();
                        }
                    }
                }
            }
        }
        if (energyStorage.getEnergyStored() <= 0) return;

        AABB area = new AABB(worldPosition).inflate(RANGE);

        // Charge nearby robots
        List<RobotEntity> robots = level.getEntitiesOfClass(RobotEntity.class, area);
        if (level.getGameTime() % 100 == 0) {
            LOGGER.warn("[ByteBlock ChargingStation] pos={} energy={}/{} robots_found={} drones_found={} area={}",
                    worldPosition, energyStorage.getEnergyStored(), MAX_ENERGY, robots.size(),
                    level.getEntitiesOfClass(DroneEntity.class, area).size(), area);
            for (RobotEntity r : robots) {
                LOGGER.warn("  robot at {} energy={}/{}", r.blockPosition(), r.getEnergyStorage().getEnergyStored(), r.getEnergyStorage().getMaxEnergyStored());
            }
        }
        for (RobotEntity robot : robots) {
            if (energyStorage.getEnergyStored() <= 0) break;
            EnergyStorage robotEnergy = robot.getEnergyStorage();
            int space = robotEnergy.getMaxEnergyStored() - robotEnergy.getEnergyStored();
            if (space > 0) {
                int toTransfer = Math.min(CHARGE_RATE, Math.min(space, energyStorage.getEnergyStored()));
                int received = robotEnergy.receiveEnergy(toTransfer, false);
                if (received > 0) {
                    energyStorage.extractEnergy(received, false);
                    robot.markCharging();
                    setChanged();
                }
            }
        }

        // Charge nearby drones (convert FE to fuel ticks)
        List<DroneEntity> drones = level.getEntitiesOfClass(DroneEntity.class, area);
        for (DroneEntity drone : drones) {
            if (energyStorage.getEnergyStored() <= 0) break;
            if (drone.getFuelTicks() < 72000) {
                int fuelSpace = 72000 - drone.getFuelTicks();
                int feNeeded = Math.max(1, fuelSpace / FUEL_PER_FE);
                int feToUse = Math.min(CHARGE_RATE, Math.min(feNeeded, energyStorage.getEnergyStored()));
                if (feToUse > 0) {
                    int fuelToAdd = feToUse * FUEL_PER_FE;
                    drone.addFuel(fuelToAdd);
                    energyStorage.extractEnergy(feToUse, false);
                    drone.markCharging();
                    setChanged();
                }
            }
        }
    }

    public EnergyStorage getEnergyStorage() { return energyStorage; }
    public int getEnergyStored() { return energyStorage.getEnergyStored(); }
    public int getMaxEnergy() { return MAX_ENERGY; }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.literal("Charging Station");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId,
            net.minecraft.world.entity.player.Inventory inv,
            net.minecraft.world.entity.player.Player player) {
        return new com.apocscode.byteblock.menu.ChargingStationMenu(containerId, inv, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putInt("Energy", energyStorage.getEnergyStored());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("Energy")) {
            int stored = tag.getInt("Energy");
            energyStorage = new EnergyStorage(MAX_ENERGY, MAX_RECEIVE, CHARGE_RATE, stored);
        }
    }
}
