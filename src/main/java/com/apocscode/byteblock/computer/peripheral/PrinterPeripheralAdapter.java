package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.PrinterBlockEntity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

public class PrinterPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public String getType(net.minecraft.world.level.block.entity.BlockEntity be) {
        return "printer";
    }

    @Override
    public java.util.List<String> getTypes(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("printer", "inventory", "peripheral");
    }

    @Override
    public java.util.List<String> getCapabilities(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("printer", "inventory", "storage", "wireless");
    }

    @Override
    public String getStableId(net.minecraft.world.level.block.entity.BlockEntity be) {
        return ((PrinterBlockEntity) be).getDeviceId().toString();
    }

    @Override
    public boolean canAdapt(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof PrinterBlockEntity;
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be,
                               com.apocscode.byteblock.computer.JavaOS callingOs) {
        PrinterBlockEntity printer = (PrinterBlockEntity) be;
        LuaTable table = new LuaTable();

        net.neoforged.neoforge.items.wrapper.InvWrapper wrapper =
                new net.neoforged.neoforge.items.wrapper.InvWrapper(printer.getContainer());
        GenericPeripheralAdapter.addInventoryMethods(table, printer, wrapper, callingOs);

        table.set("queuePrint", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue title, LuaValue content) {
                printer.queuePrint(title.checkjstring(), content.checkjstring());
                return LuaValue.TRUE;
            }
        });
        table.set("getQueueSize", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(printer.getQueueSize()); }
        });
        table.set("hasMedia", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(!printer.getContainer().getItem(0).isEmpty()); }
        });
        table.set("hasOutput", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(!printer.getContainer().getItem(1).isEmpty()); }
        });

        return table;
    }
}