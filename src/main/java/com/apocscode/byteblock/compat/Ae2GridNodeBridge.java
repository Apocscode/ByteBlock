package com.apocscode.byteblock.compat;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Provides an AE2 {@link IInWorldGridNodeHost} implementation for ByteBlock block entities,
 * enabling AE2 cables to visually attach (render connection arms) to them.
 *
 * <p>This class directly references AE2 API types and is therefore only safe to load
 * when AE2 is present. All callers must guard instantiation with
 * {@code ModList.get().isLoaded("ae2")}.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Construct in block entity constructor (AE2 guard).</li>
 *   <li>Call {@link #loadFromNBT} inside {@code loadAdditional()}.</li>
 *   <li>Call {@link #create} inside {@code onLoad()}.</li>
 *   <li>Call {@link #saveToNBT} inside {@code saveAdditional()}.</li>
 *   <li>Call {@link #destroy} inside {@code setRemoved()}.</li>
 * </ol>
 * </p>
 */
public class Ae2GridNodeBridge implements IInWorldGridNodeHost {

    private final IManagedGridNode mainNode;

    public Ae2GridNodeBridge(BlockEntity owner) {
        // createManagedNode<T>(T owner, IGridNodeListener<T>) — fix T=BlockEntity via explicit cast
        BlockEntity be = owner;
        this.mainNode = GridHelper.createManagedNode(be,
                        (IGridNodeListener<BlockEntity>) (host, node) -> host.setChanged())
                .setInWorldNode(true)
                .setTagName("main")
                .setIdlePowerUsage(0.0);
    }

    /** Activates the grid node. Call from {@code onLoad()} after level and position are set. */
    public void create(Level level, BlockPos pos) {
        mainNode.create(level, pos);
    }

    /** Deactivates and removes the grid node. Call from {@code setRemoved()}. */
    public void destroy() {
        mainNode.destroy();
    }

    /** Restores grid node state from NBT. Call from {@code loadAdditional()}. */
    public void loadFromNBT(CompoundTag tag) {
        mainNode.loadFromNBT(tag);
    }

    /** Persists grid node state to NBT. Call from {@code saveAdditional()}. */
    public void saveToNBT(CompoundTag tag) {
        mainNode.saveToNBT(tag);
    }

    // ── IInWorldGridNodeHost ──────────────────────────────────────────────────

    @Override
    public IGridNode getGridNode(Direction side) {
        return mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction side) {
        return AECableType.SMART;
    }
}
