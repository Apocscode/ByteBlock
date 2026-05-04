package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.DriveBlockEntity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

public class DrivePeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public String getType(net.minecraft.world.level.block.entity.BlockEntity be) {
        return "drive";
    }

    @Override
    public java.util.List<String> getTypes(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("drive", "storage", "peripheral");
    }

    @Override
    public java.util.List<String> getCapabilities(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("drive", "storage", "inventory", "wireless");
    }

    @Override
    public String getStableId(net.minecraft.world.level.block.entity.BlockEntity be) {
        return ((DriveBlockEntity) be).getDeviceId().toString();
    }

    @Override
    public boolean canAdapt(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof DriveBlockEntity;
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be,
                               com.apocscode.byteblock.computer.JavaOS callingOs) {
        DriveBlockEntity drive = (DriveBlockEntity) be;
        LuaTable table = new LuaTable();

        net.neoforged.neoforge.items.wrapper.InvWrapper wrapper =
                new net.neoforged.neoforge.items.wrapper.InvWrapper(drive.getContainer());
        GenericPeripheralAdapter.addInventoryMethods(table, drive, wrapper, callingOs);

        table.set("isDiskPresent", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(drive.hasDisk()); }
        });
        table.set("hasDisk", table.get("isDiskPresent"));
        table.set("getDiskLabel", new ZeroArgFunction() {
            @Override public LuaValue call() {
                String label = drive.getDiskLabel();
                return label == null || label.isBlank() ? LuaValue.NIL : LuaValue.valueOf(label);
            }
        });
        table.set("setDiskLabel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                drive.setDiskLabel(value.checkjstring());
                return LuaValue.TRUE;
            }
        });
        table.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 1;
                for (String path : drive.listDiskFiles()) {
                    out.set(i++, LuaValue.valueOf(path));
                }
                return out;
            }
        });
        table.set("read", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                String content = drive.readFromDisk(value.checkjstring());
                return content == null ? LuaValue.NIL : LuaValue.valueOf(content);
            }
        });
        table.set("write", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue path, LuaValue content) {
                drive.writeToDisk(path.checkjstring(), content.checkjstring());
                return LuaValue.TRUE;
            }
        });

        return table;
    }
}