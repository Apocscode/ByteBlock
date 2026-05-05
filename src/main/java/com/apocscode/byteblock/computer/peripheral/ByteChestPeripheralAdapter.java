package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import net.minecraft.core.Direction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

public class ByteChestPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public String getType(net.minecraft.world.level.block.entity.BlockEntity be) {
        return "byte_chest";
    }

    @Override
    public java.util.List<String> getTypes(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("byte_chest", "inventory", "storage", "peripheral");
    }

    @Override
    public java.util.List<String> getCapabilities(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("inventory", "storage", "wireless");
    }

    @Override
    public String getLabel(net.minecraft.world.level.block.entity.BlockEntity be) {
        String label = ((ByteChestBlockEntity) be).getLabel();
        return label == null || label.isBlank() ? null : label;
    }

    @Override
    public String getStableId(net.minecraft.world.level.block.entity.BlockEntity be) {
        return ((ByteChestBlockEntity) be).getDeviceId().toString();
    }

    @Override
    public boolean canAdapt(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof ByteChestBlockEntity;
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be,
                               com.apocscode.byteblock.computer.JavaOS callingOs) {
        ByteChestBlockEntity chest = (ByteChestBlockEntity) be;
        LuaTable table = new LuaTable();

        net.neoforged.neoforge.items.wrapper.InvWrapper wrapper =
                new net.neoforged.neoforge.items.wrapper.InvWrapper(chest);
        GenericPeripheralAdapter.addInventoryMethods(table, chest, wrapper, callingOs);

        table.set("getLabel", new ZeroArgFunction() {
            @Override public LuaValue call() {
                String label = chest.getLabel();
                return label == null || label.isBlank() ? LuaValue.NIL : LuaValue.valueOf(label);
            }
        });
        table.set("setLabel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                chest.setLabel(value.checkjstring());
                return LuaValue.TRUE;
            }
        });
        table.set("getTint", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(chest.getTint()); }
        });
        table.set("setTint", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                chest.setTint(value.checkint());
                return LuaValue.TRUE;
            }
        });

        // ── Logistics tab control ──────────────────────────────────────────
        // Mirrors the sneak+right-click "Logistics" tab. All setters call
        // chest.applyLogisticsConfig(...) which validates, persists, syncs
        // to the client, and resets the diagnostic-log throttle.
        table.set("getLogistics", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("pullEnabled", LuaValue.valueOf(chest.isPullEnabled()));
                t.set("pushEnabled", LuaValue.valueOf(chest.isPushEnabled()));
                t.set("keep", LuaValue.valueOf(chest.getKeepAmount()));
                t.set("movePerTick", LuaValue.valueOf(chest.getMovePerTick()));
                t.set("pushSide", LuaValue.valueOf(chest.getPushSide().getName()));
                LuaTable filters = new LuaTable();
                String[] ids = chest.getPullFilterIds();
                for (int i = 0; i < ids.length; i++) {
                    filters.set(i + 1, LuaValue.valueOf(ids[i] == null ? "" : ids[i]));
                }
                t.set("filters", filters);
                t.set("filterCount", LuaValue.valueOf(ByteChestBlockEntity.PULL_FILTER_COUNT));
                return t;
            }
        });

        table.set("setPullEnabled", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                applyLogistics(chest, b -> b.pullEnabled = v.toboolean());
                return LuaValue.TRUE;
            }
        });
        table.set("setPushEnabled", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                applyLogistics(chest, b -> b.pushEnabled = v.toboolean());
                return LuaValue.TRUE;
            }
        });
        table.set("setKeep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                applyLogistics(chest, b -> b.keep = v.checkint());
                return LuaValue.TRUE;
            }
        });
        table.set("setMovePerTick", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                applyLogistics(chest, b -> b.movePerTick = v.checkint());
                return LuaValue.TRUE;
            }
        });
        table.set("setPushSide", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                Direction d = parseSide(v.checkjstring());
                applyLogistics(chest, b -> b.pushSide = d);
                return LuaValue.TRUE;
            }
        });
        // setPullFilter(slot, id)  — slot is 1..6, id may be empty/nil to clear.
        table.set("setPullFilter", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue slotV, LuaValue idV) {
                int slot = slotV.checkint();
                if (slot < 1 || slot > ByteChestBlockEntity.PULL_FILTER_COUNT) {
                    throw new org.luaj.vm2.LuaError("slot out of range 1.." + ByteChestBlockEntity.PULL_FILTER_COUNT);
                }
                String id = (idV == null || idV.isnil()) ? "" : idV.tojstring();
                applyLogistics(chest, b -> b.filters[slot - 1] = id);
                return LuaValue.TRUE;
            }
        });
        // setPullFilters({"minecraft:oak_log", "minecraft:stone", ...})
        table.set("setPullFilters", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                LuaTable t = v.checktable();
                String[] ids = new String[ByteChestBlockEntity.PULL_FILTER_COUNT];
                java.util.Arrays.fill(ids, "");
                for (int i = 1; i <= ByteChestBlockEntity.PULL_FILTER_COUNT; i++) {
                    LuaValue e = t.get(i);
                    if (!e.isnil()) ids[i - 1] = e.tojstring();
                }
                applyLogistics(chest, b -> System.arraycopy(ids, 0, b.filters, 0, ids.length));
                return LuaValue.TRUE;
            }
        });
        table.set("clearPullFilters", new ZeroArgFunction() {
            @Override public LuaValue call() {
                applyLogistics(chest, b -> java.util.Arrays.fill(b.filters, ""));
                return LuaValue.TRUE;
            }
        });
        // setLogistics({pullEnabled=, pushEnabled=, keep=, movePerTick=, pushSide=, filters={...}})
        table.set("setLogistics", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                LuaTable t = v.checktable();
                applyLogistics(chest, b -> {
                    LuaValue x;
                    if (!(x = t.get("pullEnabled")).isnil()) b.pullEnabled = x.toboolean();
                    if (!(x = t.get("pushEnabled")).isnil()) b.pushEnabled = x.toboolean();
                    if (!(x = t.get("keep")).isnil()) b.keep = x.checkint();
                    if (!(x = t.get("movePerTick")).isnil()) b.movePerTick = x.checkint();
                    if (!(x = t.get("pushSide")).isnil()) b.pushSide = parseSide(x.tojstring());
                    if (!(x = t.get("filters")).isnil() && x.istable()) {
                        LuaTable ft = x.checktable();
                        for (int i = 0; i < ByteChestBlockEntity.PULL_FILTER_COUNT; i++) {
                            LuaValue e = ft.get(i + 1);
                            b.filters[i] = e.isnil() ? "" : e.tojstring();
                        }
                    }
                });
                return LuaValue.TRUE;
            }
        });

        return table;
    }

    /** Mutable scratch of every logistics field — applied atomically via applyLogisticsConfig. */
    private static final class LogiBuf {
        boolean pullEnabled, pushEnabled;
        int keep, movePerTick;
        Direction pushSide;
        String[] filters;
    }

    private static void applyLogistics(ByteChestBlockEntity chest, java.util.function.Consumer<LogiBuf> mutator) {
        LogiBuf b = new LogiBuf();
        b.pullEnabled = chest.isPullEnabled();
        b.pushEnabled = chest.isPushEnabled();
        b.keep        = chest.getKeepAmount();
        b.movePerTick = chest.getMovePerTick();
        b.pushSide    = chest.getPushSide();
        b.filters     = chest.getPullFilterIds();
        mutator.accept(b);
        chest.applyLogisticsConfig(
                b.pullEnabled,
                b.filters.length > 0 ? b.filters[0] : "",
                b.keep,
                b.pushEnabled,
                b.pushSide,
                b.movePerTick,
                b.filters);
    }

    private static Direction parseSide(String s) {
        if (s == null) return Direction.NORTH;
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "north": return Direction.NORTH;
            case "south": return Direction.SOUTH;
            case "east":  return Direction.EAST;
            case "west":  return Direction.WEST;
            case "up":    return Direction.UP;
            case "down":  return Direction.DOWN;
            default: throw new org.luaj.vm2.LuaError("invalid side '" + s + "' (expected north/south/east/west/up/down)");
        }
    }
}