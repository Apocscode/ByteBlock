package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
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

        return table;
    }
}