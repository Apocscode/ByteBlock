package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

public class RedstoneRelayPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public String getType(net.minecraft.world.level.block.entity.BlockEntity be) {
        return "redstone_relay";
    }

    @Override
    public java.util.List<String> getTypes(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("redstone_relay", "redstone", "peripheral");
    }

    @Override
    public java.util.List<String> getCapabilities(net.minecraft.world.level.block.entity.BlockEntity be) {
        return java.util.List.of("redstone", "bundled_redstone", "wireless");
    }

    @Override
    public String getStableId(net.minecraft.world.level.block.entity.BlockEntity be) {
        return ((RedstoneRelayBlockEntity) be).getDeviceId().toString();
    }

    @Override
    public boolean canAdapt(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof RedstoneRelayBlockEntity;
    }

    @Override
    public LuaTable buildTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        RedstoneRelayBlockEntity relay = (RedstoneRelayBlockEntity) be;
        LuaTable table = new LuaTable();

        table.set("isConnected", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(relay.isConnected()); }
        });
        table.set("getInput", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.valueOf(relay.getInput(side.checkint())); }
        });
        table.set("getOutput", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.valueOf(relay.getOutput(side.checkint())); }
        });
        table.set("getFaceChannel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.valueOf(relay.getFaceChannel(side.checkint())); }
        });
        table.set("isBundledFace", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.valueOf(relay.isBundledFace(side.checkint())); }
        });
        table.set("getBundledMask", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.valueOf(relay.getFaceBundledMask(side.checkint())); }
        });
        table.set("setFaceConfig", new ThreeArgFunction() {
            @Override public LuaValue call(LuaValue side, LuaValue channel, LuaValue bundled) {
                relay.setFaceConfig(side.checkint(), channel.checkint(), bundled.toboolean());
                return LuaValue.TRUE;
            }
        });
        table.set("getFaceChannels", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int[] values = relay.getFaceChannels();
                for (int i = 0; i < values.length; i++) out.set(i + 1, LuaValue.valueOf(values[i]));
                return out;
            }
        });

        return table;
    }
}