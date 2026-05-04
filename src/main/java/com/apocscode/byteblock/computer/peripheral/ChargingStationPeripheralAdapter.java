package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.ChargingStationBlockEntity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;

public class ChargingStationPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public String getType(net.minecraft.world.level.block.entity.BlockEntity be) {
        return "charging_station";
    }

    @Override
    public java.util.List<String> getTypes(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("charging_station", "energy", "peripheral");
    }

    @Override
    public java.util.List<String> getCapabilities(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("energy", "wireless", "charging");
    }

    @Override
    public String getStableId(net.minecraft.world.level.block.entity.BlockEntity be) {
        return ((ChargingStationBlockEntity) be).getDeviceId().toString();
    }

    @Override
    public boolean canAdapt(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof ChargingStationBlockEntity;
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be,
                               com.apocscode.byteblock.computer.JavaOS callingOs) {
        ChargingStationBlockEntity station = (ChargingStationBlockEntity) be;
        LuaTable table = new LuaTable();

        GenericPeripheralAdapter.addEnergyMethods(table, station.getEnergyStorage());

        table.set("getRange", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(station.getRangeBlocks()); }
        });
        table.set("getChargeRate", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(station.getChargeRate()); }
        });
        table.set("getFuelPerFE", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(station.getFuelPerFe()); }
        });

        return table;
    }
}