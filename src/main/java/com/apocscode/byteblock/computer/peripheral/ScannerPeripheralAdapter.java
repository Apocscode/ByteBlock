package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.ScannerBlockEntity;
import com.apocscode.byteblock.scanner.WorldScanData;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

public class ScannerPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public String getType(net.minecraft.world.level.block.entity.BlockEntity be) {
        return "scanner";
    }

    @Override
    public java.util.List<String> getTypes(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("scanner", "sensor", "peripheral");
    }

    @Override
    public java.util.List<String> getCapabilities(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("scanner", "sensor", "wireless", "blocks", "entities");
    }

    @Override
    public String getStableId(net.minecraft.world.level.block.entity.BlockEntity be) {
        return ((ScannerBlockEntity) be).getDeviceId().toString();
    }

    @Override
    public boolean canAdapt(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof ScannerBlockEntity;
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be,
                               com.apocscode.byteblock.computer.JavaOS callingOs) {
        ScannerBlockEntity scanner = (ScannerBlockEntity) be;
        LuaTable table = new LuaTable();

        table.set("scan", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int radius = args.optint(1, -1);
                if (radius > 0 && radius <= 16) scanner.performImmediateScan(radius);
                else scanner.startScan();
                return LuaValue.TRUE;
            }
        });
        table.set("getRadius", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(scanner.getScanRadius()); }
        });
        table.set("setRadius", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                scanner.setScanRadius(value.checkint());
                return LuaValue.TRUE;
            }
        });
        table.set("getProgress", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(scanner.getScanProgress()); }
        });
        table.set("isScanning", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(scanner.isScanning()); }
        });
        table.set("getBlockCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(scanner.getScanData().getScannedBlockCount()); }
        });
        table.set("getEntityCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(scanner.getScanData().getEntities().size()); }
        });
        table.set("getBlock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String block = scanner.getScanData().getBlock(
                        scanner.getLevel(), args.checkint(1), args.checkint(2), args.checkint(3));
                return block != null ? LuaValue.valueOf(block) : LuaValue.NIL;
            }
        });
        table.set("findBlock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String name = args.checkjstring(1);
                int radius = args.optint(2, scanner.getScanRadius());
                net.minecraft.core.BlockPos found = scanner.getScanData().findBlock(name, scanner.getBlockPos(), radius);
                if (found == null) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(found.getX()),
                        LuaValue.valueOf(found.getY()),
                        LuaValue.valueOf(found.getZ())
                });
            }
        });
        table.set("getEntities", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                int i = 1;
                for (WorldScanData.EntitySnapshot e : scanner.getScanData().getEntities()) {
                    LuaTable entry = new LuaTable();
                    entry.set("type", LuaValue.valueOf(e.type()));
                    entry.set("name", LuaValue.valueOf(e.name()));
                    entry.set("x", LuaValue.valueOf(e.x()));
                    entry.set("y", LuaValue.valueOf(e.y()));
                    entry.set("z", LuaValue.valueOf(e.z()));
                    entry.set("health", LuaValue.valueOf(e.health()));
                    entry.set("maxHealth", LuaValue.valueOf(e.maxHealth()));
                    entry.set("isPlayer", LuaValue.valueOf(e.isPlayer()));
                    entry.set("uuid", LuaValue.valueOf(e.uuid()));
                    result.set(i++, entry);
                }
                return result;
            }
        });

        return table;
    }
}