package com.apocscode.byteblock.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Upgrade Card — inserted into a robot or drone's upgrade slots to
 * extend its capabilities.
 *
 * Range cards expand the operation range (how far the drone/robot can be
 * from a player before it auto-returns home).  Other card types reserve
 * slots for future mechanics (speed, extra inventory, etc.).
 */
public class UpgradeCard extends Item {

    public enum Type {
        RANGE_I   ("Range I",        50),
        RANGE_II  ("Range II",       200),
        RANGE_CREATIVE("Unlimited Range", Integer.MAX_VALUE),
        SPEED     ("Speed",          0),
        INVENTORY ("Inventory+",     0),
        LASER     ("Laser Weapon",    0);

        public final String displayName;
        /** Operation range in blocks. 0 = not a range card. MAX_VALUE = unlimited. */
        public final int range;

        Type(String displayName, int range) {
            this.displayName = displayName;
            this.range = range;
        }

        public boolean isRangeCard() {
            return range > 0;
        }

        public boolean isLaserCard() {
            return this == LASER;
        }
    }

    private final Type type;

    public UpgradeCard(Type type, Item.Properties props) {
        super(props);
        this.type = type;
    }

    public Type getUpgradeType() { return type; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("§7" + type.displayName));
        if (type.isRangeCard()) {
            String rangeText = type.range == Integer.MAX_VALUE ? "Unlimited" : type.range + " blocks";
            lines.add(Component.literal("§8Operation range: §b" + rangeText));
        }
        if (type.isLaserCard()) {
            lines.add(Component.literal("§8Fires a laser at nearby hostiles."));
            lines.add(Component.literal("§8Range: §c16 blocks §8| Damage: §c6 §8| Rate: §c0.5/s"));
        }
        lines.add(Component.literal("§8Insert into drone or robot upgrade slots."));
    }
}
