package com.apocscode.byteblock.compat;

import appeng.api.AECapabilities;

import com.apocscode.byteblock.init.ModBlockEntities;

import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers the {@code IN_WORLD_GRID_NODE_HOST} block capability for ByteBlock's
 * block entities so AE2 cables can visually connect to them.
 *
 * <p>This class directly references AE2 API types. It is only safe to load — and
 * its {@link #register} method only safe to call — when AE2 is present at runtime.</p>
 */
public final class Ae2CapabilityRegistrar {

    private Ae2CapabilityRegistrar() {}

    /**
     * Registers the AE2 grid-node-host capability for the Universal Peripheral and
     * ByteChest block entities.
     *
     * @param event the NeoForge {@link RegisterCapabilitiesEvent}
     */
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                ModBlockEntities.PERIPHERAL.get(),
                (be, ignored) -> be.getAe2Bridge()
        );
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                ModBlockEntities.BYTE_CHEST.get(),
                (be, ignored) -> be.getAe2Bridge()
        );
    }
}
