package com.apocscode.byteblock.computer.peripheral;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Peripheral adapter for all CreateLogicLink block entities.
 *
 * <p>Handles five block types:
 * <ul>
 *   <li>{@code LogicLinkBlockEntity}         → type {@code "logiclink"}</li>
 *   <li>{@code LogicSensorBlockEntity}        → type {@code "logicsensor"}</li>
 *   <li>{@code CreativeLogicMotorBlockEntity} → type {@code "creative_logic_motor"}</li>
 *   <li>{@code LogicDriveBlockEntity}         → type {@code "logic_drive"}</li>
 *   <li>{@code RedstoneControllerBlockEntity} → type {@code "redstone_controller"}</li>
 * </ul>
 *
 * All access is reflective — ByteBlock compiles and runs with or without
 * LogicLink on the classpath.
 */
public class LogicLinkPeripheralAdapter implements IPeripheralAdapter {

    // ══════════════════════════════════════════════════════════════════════
    // Reflection caches — populated lazily on first use
    // ══════════════════════════════════════════════════════════════════════

    private static volatile boolean cacheReady = false;

    // BE classes
    private static Class<?> clsHubBE;       // LogicLinkBlockEntity
    private static Class<?> clsSensorBE;    // LogicSensorBlockEntity
    private static Class<?> clsMotorBE;     // CreativeLogicMotorBlockEntity
    private static Class<?> clsDriveBE;     // LogicDriveBlockEntity
    private static Class<?> clsRedstoneBE;  // RedstoneControllerBlockEntity
    private static Class<?> clsTrainCtrlBE; // TrainControllerBlockEntity

    // Hub BE — direct methods
    private static Method mIsLinked;
    private static Method mGetNetworkSummary;
    private static Method mGetNetworkFrequency;
    private static Method mGetHubRange;
    private static Method mSetHubRange;
    private static Method mGetHubLabel;
    private static Method mSetHubLabel;

    // InventorySummary (Create)
    private static Method mGetStacks;
    private static Field  fBisStack;
    private static Field  fBisCount;

    // Hub Peripheral — reflective constructor + methods
    private static Constructor<?> ctorHub;
    private static Method mGetLinks;
    private static Method mGetSensors;
    private static Method mGetDevices;
    private static Method mGetGauges;
    private static Method mGetPosition;
    private static Method mGetRemoteSensorData;
    private static Method mGetRemoteMotorInfo;
    private static Method mEnableRemote;
    private static Method mSetRemoteSpeed;
    private static Method mSetRemoteModifier;
    private static Method mSetRemoteReversed;
    private static Method mSetDeviceLabel;
    private static Method mGetDeviceLabel;
    private static Method mCycleTrackSwitch;
    private static Method mGetTrainBlockData;
    private static Method mSetRemoteRedstoneOutput;
    private static Method mGetRemoteRedstoneInput;
    private static Method mGetRemoteRedstoneChannels;
    private static Method mSetAllRemoteRedstoneOutputs;
    private static Method mRequestItem;
    private static Method mRequestItems;
    private static Method mGetAllRemoteSensorData;
    private static Method mGetTrackSwitchState;

    // Hub BE — refresh
    private static Method mRefreshNetworkSummary;

    // Sensor BE — direct methods
    private static Method mSensorGetCachedData;
    private static Method mSensorGetTargetPos;
    private static Method mSensorIsLinked;
    private static Method mSensorGetNetworkFrequency;
    private static Method mSensorRefresh;

    // Motor BE — direct methods
    private static Method mMotorIsEnabled;
    private static Method mMotorSetEnabled;
    private static Method mMotorGetSpeed;
    private static Method mMotorSetSpeed;
    private static Method mMotorGetActualSpeed;
    private static Method mMotorGetStressCapacity;
    private static Method mMotorGetStressUsage;
    private static Method mMotorIsSequenceRunning;
    private static Method mMotorGetSequenceSize;
    private static Method mMotorClearSequence;
    private static Method mMotorAddRotateStep;
    private static Method mMotorAddWaitStep;
    private static Method mMotorAddSpeedStep;
    private static Method mMotorRunSequence;
    private static Method mMotorStopSequence;

    // Drive BE — direct methods
    private static Method mDriveIsEnabled;
    private static Method mDriveSetEnabled;
    private static Method mDriveGetModifier;
    private static Method mDriveSetModifier;
    private static Method mDriveIsReversed;
    private static Method mDriveSetReversed;
    private static Method mDriveGetInputSpeed;
    private static Method mDriveGetOutputSpeed;
    private static Method mDriveClearSequence;
    private static Method mDriveAddRotateStep;
    private static Method mDriveAddWaitStep;
    private static Method mDriveAddModifierStep;
    private static Method mDriveRunSequence;
    private static Method mDriveStopSequence;
    private static Method mDriveIsSequenceRunning;
    private static Method mDriveGetSequenceSize;

    // Redstone Controller BE — direct methods
    private static Method mRedstoneSetOutput;
    private static Method mRedstoneGetInput;
    private static Method mRedstoneGetOutput;
    private static Method mRedstoneRemoveChannel;
    private static Method mRedstoneGetChannelList;
    private static Method mRedstoneSetAllOutputs;
    private static Method mRedstoneClearChannels;

    // Train Controller — reflective peripheral proxy
    private static Constructor<?> ctorTrainCtrl;
    private static Method mTcGetTrains;
    private static Method mTcGetTrain;
    private static Method mTcGetStations;
    private static Method mTcGetSignals;
    private static Method mTcGetObservers;
    private static Method mTcGetNetworkOverview;
    private static Method mTcGetTrainCount;
    private static Method mTcGetRefreshInterval;
    private static Method mTcSetRefreshInterval;
    private static Method mTcRefresh;

    // ══════════════════════════════════════════════════════════════════════
    // IPeripheralAdapter implementation
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public String getModId() { return "logiclink"; }

    @Override
    public java.util.List<String> getTypes(BlockEntity be) {
        if (!ensureCache()) return java.util.List.of("logiclink");
        if (clsHubBE       != null && clsHubBE.isInstance(be))       return java.util.List.of("logiclink",          "hub",     "storage");
        if (clsSensorBE    != null && clsSensorBE.isInstance(be))    return java.util.List.of("logicsensor",        "sensor");
        if (clsMotorBE     != null && clsMotorBE.isInstance(be))     return java.util.List.of("creative_logic_motor","motor",   "kinetic");
        if (clsDriveBE     != null && clsDriveBE.isInstance(be))     return java.util.List.of("logic_drive",        "drive",   "kinetic");
        if (clsRedstoneBE  != null && clsRedstoneBE.isInstance(be))  return java.util.List.of("redstone_controller","redstone");
        if (clsTrainCtrlBE != null && clsTrainCtrlBE.isInstance(be)) return java.util.List.of("train_controller",   "trains",  "network");
        return java.util.List.of("logiclink");
    }

    @Override
    public java.util.List<String> getCapabilities(BlockEntity be) {
        if (!ensureCache()) return java.util.List.of("wireless");
        if (clsHubBE       != null && clsHubBE.isInstance(be))       return java.util.List.of("storage", "inventory", "wireless", "network");
        if (clsSensorBE    != null && clsSensorBE.isInstance(be))    return java.util.List.of("sensor",  "wireless");
        if (clsMotorBE     != null && clsMotorBE.isInstance(be))     return java.util.List.of("kinetics","motor", "rotation");
        if (clsDriveBE     != null && clsDriveBE.isInstance(be))     return java.util.List.of("kinetics","drive", "rotation");
        if (clsRedstoneBE  != null && clsRedstoneBE.isInstance(be))  return java.util.List.of("redstone","bundled");
        if (clsTrainCtrlBE != null && clsTrainCtrlBE.isInstance(be)) return java.util.List.of("trains",  "network");
        return java.util.List.of("wireless");
    }

    @Override
    public String getLabel(BlockEntity be) {
        if (!ensureCache() || mGetHubLabel == null) return null;
        if (clsHubBE != null && clsHubBE.isInstance(be)) {
            try {
                String label = (String) mGetHubLabel.invoke(be);
                return (label != null && !label.isEmpty()) ? label : null;
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public boolean canAdapt(BlockEntity be) {
        if (!ensureCache()) return false;
        return (clsHubBE      != null && clsHubBE.isInstance(be))
            || (clsSensorBE   != null && clsSensorBE.isInstance(be))
            || (clsMotorBE    != null && clsMotorBE.isInstance(be))
            || (clsDriveBE    != null && clsDriveBE.isInstance(be))
            || (clsRedstoneBE != null && clsRedstoneBE.isInstance(be))
            || (clsTrainCtrlBE != null && clsTrainCtrlBE.isInstance(be));
    }

    @Override
    public String getType(BlockEntity be) {
        if (!ensureCache()) return "unknown";
        if (clsHubBE      != null && clsHubBE.isInstance(be))      return "logiclink";
        if (clsSensorBE   != null && clsSensorBE.isInstance(be))   return "logicsensor";
        if (clsMotorBE    != null && clsMotorBE.isInstance(be))     return "creative_logic_motor";
        if (clsDriveBE    != null && clsDriveBE.isInstance(be))     return "logic_drive";
        if (clsRedstoneBE != null && clsRedstoneBE.isInstance(be)) return "redstone_controller";
        if (clsTrainCtrlBE != null && clsTrainCtrlBE.isInstance(be)) return "train_controller";
        return "unknown";
    }

    @Override
    public LuaTable buildTable(BlockEntity be) {
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(BlockEntity be, com.apocscode.byteblock.computer.JavaOS callingOs) {
        if (!ensureCache()) return new LuaTable();
        if (clsHubBE      != null && clsHubBE.isInstance(be))       return buildHubTable(be, callingOs);
        if (clsSensorBE   != null && clsSensorBE.isInstance(be))    return buildSensorTable(be);
        if (clsMotorBE    != null && clsMotorBE.isInstance(be))      return buildMotorTable(be);
        if (clsDriveBE    != null && clsDriveBE.isInstance(be))      return buildDriveTable(be);
        if (clsRedstoneBE != null && clsRedstoneBE.isInstance(be))  return buildRedstoneTable(be);
        if (clsTrainCtrlBE != null && clsTrainCtrlBE.isInstance(be)) return buildTrainCtrlTable(be, callingOs);
        return new LuaTable();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Hub (logiclink) — Logic Link Hub
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildHubTable(BlockEntity be, com.apocscode.byteblock.computer.JavaOS callingOs) {
        LuaTable t = new LuaTable();

        // isLinked() → boolean
        t.set("isLinked", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((boolean) mIsLinked.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getNetworkFrequency() → string | nil
        t.set("getNetworkFrequency", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object uuid = mGetNetworkFrequency.invoke(be);
                    return uuid != null ? LuaValue.valueOf(uuid.toString()) : LuaValue.NIL;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getHubRange() → int
        t.set("getHubRange", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((int) mGetHubRange.invoke(be)); }
                catch (Exception e) { return LuaValue.valueOf(64); }
            }
        });

        // setHubRange(n)
        t.set("setHubRange", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                try { mSetHubRange.invoke(be, n.checkint()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getHubLabel() → string
        t.set("getHubLabel", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((String) mGetHubLabel.invoke(be)); }
                catch (Exception e) { return LuaValue.valueOf(""); }
            }
        });

        // setHubLabel(label)
        t.set("setHubLabel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue s) {
                try { mSetHubLabel.invoke(be, s.checkjstring()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getNetworkID() — alias of getNetworkFrequency, matches CC:Tweaked LogicLink API
        t.set("getNetworkID", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object uuid = mGetNetworkFrequency.invoke(be);
                    return uuid != null ? LuaValue.valueOf(uuid.toString()) : LuaValue.NIL;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // refresh() — force-refresh cached inventory summary
        t.set("refresh", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mRefreshNetworkSummary != null) {
                    try { mRefreshNetworkSummary.invoke(be); } catch (Exception ignored) {}
                }
                return LuaValue.NONE;
            }
        });

        // list() → [{name, count, displayName}]
        t.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                try {
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary == null) return result;
                    List<?> stacks = (List<?>) mGetStacks.invoke(summary);
                    int i = 1;
                    for (Object bis : stacks) {
                        ItemStack stack = (ItemStack) fBisStack.get(bis);
                        if (stack == null || stack.isEmpty()) continue;
                        int count = (int) fBisCount.get(bis);
                        LuaTable entry = new LuaTable();
                        entry.set("name",        LuaValue.valueOf(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
                        entry.set("count",       LuaValue.valueOf(count));
                        entry.set("displayName", LuaValue.valueOf(
                                stack.getHoverName().getString()));
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // getItemCount(name) → int
        t.set("getItemCount", new OneArgFunction() {
            @Override public LuaValue call(LuaValue name) {
                try {
                    String itemName = name.checkjstring();
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary == null) return LuaValue.valueOf(0);
                    List<?> stacks = (List<?>) mGetStacks.invoke(summary);
                    for (Object bis : stacks) {
                        ItemStack stack = (ItemStack) fBisStack.get(bis);
                        if (stack == null || stack.isEmpty()) continue;
                        if (BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .toString().equals(itemName)) {
                            return LuaValue.valueOf((int) fBisCount.get(bis));
                        }
                    }
                } catch (Exception ignored) {}
                return LuaValue.valueOf(0);
            }
        });

        // getItemTypeCount() → int
        t.set("getItemTypeCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary == null) return LuaValue.valueOf(0);
                    return LuaValue.valueOf(((List<?>) mGetStacks.invoke(summary)).size());
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getTotalItemCount() → int
        t.set("getTotalItemCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary == null) return LuaValue.valueOf(0);
                    long total = 0;
                    for (Object bis : (List<?>) mGetStacks.invoke(summary))
                        total += (int) fBisCount.get(bis);
                    return LuaValue.valueOf((double) total);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getNetworkInfo() → table
        t.set("getNetworkInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                try {
                    boolean linked = (boolean) mIsLinked.invoke(be);
                    info.set("linked", LuaValue.valueOf(linked));
                    Object uuid = mGetNetworkFrequency.invoke(be);
                    info.set("networkId", uuid != null
                            ? LuaValue.valueOf(uuid.toString()) : LuaValue.valueOf("none"));
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary != null) {
                        List<?> stacks = (List<?>) mGetStacks.invoke(summary);
                        long total = 0;
                        for (Object bis : stacks) total += (int) fBisCount.get(bis);
                        info.set("itemTypes",  LuaValue.valueOf(stacks.size()));
                        info.set("totalItems", LuaValue.valueOf(total));
                    } else {
                        info.set("itemTypes",  LuaValue.valueOf(0));
                        info.set("totalItems", LuaValue.valueOf(0));
                    }
                    info.set("hubRange", LuaValue.valueOf((int) mGetHubRange.invoke(be)));
                    info.set("hubLabel", LuaValue.valueOf((String) mGetHubLabel.invoke(be)));
                } catch (Exception ignored) {}
                return info;
            }
        });

        // ── Peripheral-routed methods (network traversal) ──────────────────
        Object peri = null;
        if (ctorHub != null) {
            try { peri = ctorHub.newInstance(be); } catch (Exception ignored) {}
        }
        final Object peripheral = peri;

        addResult0Precomputed(t, "getLinks",    peripheral, mGetLinks);
        addResult0Precomputed(t, "getSensors",  peripheral, mGetSensors);
        addResult0Precomputed(t, "getDevices",  peripheral, mGetDevices);
        addResult0Precomputed(t, "getGauges",   peripheral, mGetGauges);
        addResult0Precomputed(t, "getPosition", peripheral, mGetPosition);

        addResult1S(t, "getRemoteSensorData",      peripheral, mGetRemoteSensorData,         be);
        addResult1S(t, "getRemoteMotorInfo",        peripheral, mGetRemoteMotorInfo,          be);
        addResult1S(t, "getDeviceLabel",            peripheral, mGetDeviceLabel,              be);
        addResult1S(t, "cycleTrackSwitch",          peripheral, mCycleTrackSwitch,            be);
        addResult1S(t, "getTrainBlockData",         peripheral, mGetTrainBlockData,           be);
        addResult1S(t, "getRemoteRedstoneChannels", peripheral, mGetRemoteRedstoneChannels,   be);

        // enableRemote(id, bool)
        t.set("enableRemote", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mEnableRemote == null) return LuaValue.NONE;
                final String id = args.checkjstring(1); final boolean en = args.toboolean(2);
                runOnServer(be, () -> { try { mEnableRemote.invoke(peripheral, id, en); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // setRemoteSpeed(id, speed)
        t.set("setRemoteSpeed", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mSetRemoteSpeed == null) return LuaValue.NONE;
                final String id = args.checkjstring(1); final int spd = args.checkint(2);
                runOnServer(be, () -> { try { mSetRemoteSpeed.invoke(peripheral, id, spd); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // setRemoteModifier(id, modifier)
        t.set("setRemoteModifier", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mSetRemoteModifier == null) return LuaValue.NONE;
                final String id = args.checkjstring(1); final double mod = args.checkdouble(2);
                runOnServer(be, () -> { try { mSetRemoteModifier.invoke(peripheral, id, mod); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // setRemoteReversed(id, reversed)
        t.set("setRemoteReversed", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mSetRemoteReversed == null) return LuaValue.NONE;
                final String id = args.checkjstring(1); final boolean rev = args.toboolean(2);
                runOnServer(be, () -> { try { mSetRemoteReversed.invoke(peripheral, id, rev); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // setDeviceLabel(id, label)
        t.set("setDeviceLabel", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mSetDeviceLabel == null) return LuaValue.NONE;
                final String id = args.checkjstring(1); final String lbl = args.checkjstring(2);
                runOnServer(be, () -> { try { mSetDeviceLabel.invoke(peripheral, id, lbl); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // setAllRemoteRedstoneOutputs(id, power)
        t.set("setAllRemoteRedstoneOutputs", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mSetAllRemoteRedstoneOutputs == null) return LuaValue.NONE;
                final String id = args.checkjstring(1); final int pwr = args.checkint(2);
                runOnServer(be, () -> { try { mSetAllRemoteRedstoneOutputs.invoke(peripheral, id, pwr); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // getRemoteRedstoneInput(id, item1, item2) → int (server-thread only — peripheral does Level access)
        t.set("getRemoteRedstoneInput", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mGetRemoteRedstoneInput == null)
                    return LuaValue.valueOf(0);
                if (!isServerThread(be)) return LuaValue.valueOf(0);
                try {
                    Object r = mGetRemoteRedstoneInput.invoke(peripheral,
                            args.checkjstring(1), args.checkjstring(2), args.checkjstring(3));
                    return javaToLua(r);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // setRemoteRedstoneOutput(id, item1, item2, power)
        t.set("setRemoteRedstoneOutput", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mSetRemoteRedstoneOutput == null) return LuaValue.NONE;
                final String id = args.checkjstring(1), s1 = args.checkjstring(2), s2 = args.checkjstring(3);
                final int pwr = args.checkint(4);
                runOnServer(be, () -> { try { mSetRemoteRedstoneOutput.invoke(peripheral, id, s1, s2, pwr); } catch (Exception ignored) {} });
                return LuaValue.NONE;
            }
        });

        // requestItem(itemName, count, address) — fire-and-forget to server thread
        t.set("requestItem", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mRequestItem == null) return LuaValue.FALSE;
                final String name = args.checkjstring(1);
                final int    cnt  = args.checkint(2);
                final String addr = args.checkjstring(3);
                runOnServer(be, () -> { try { mRequestItem.invoke(peripheral, name, cnt, addr); } catch (Exception ignored) {} });
                return LuaValue.TRUE;
            }
        });

        // requestItems(items, address) → boolean — bulk request (fire-and-forget)
        t.set("requestItems", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (peripheral == null || mRequestItems == null) return LuaValue.FALSE;
                try {
                    LuaTable itemsTbl = args.checktable(1);
                    final String address = args.checkjstring(2);
                    java.util.Map<Object, Object> javaItems = new java.util.HashMap<>();
                    LuaValue k = LuaValue.NIL;
                    while (true) {
                        Varargs n = itemsTbl.next(k);
                        if (n.arg1().isnil()) break;
                        k = n.arg1();
                        LuaValue v = n.arg(2);
                        if (v.istable()) {
                            LuaTable entry = v.checktable();
                            java.util.Map<String, Object> javaEntry = new java.util.HashMap<>();
                            LuaValue nm  = entry.get("name");
                            LuaValue cnt = entry.get("count");
                            if (!nm.isnil())  javaEntry.put("name",  nm.tojstring());
                            if (!cnt.isnil()) javaEntry.put("count", cnt.toint());
                            javaItems.put(k.isint() ? Integer.valueOf(k.toint()) : k.tojstring(), javaEntry);
                        }
                    }
                    final java.util.Map<Object, Object> finalItems = javaItems;
                    runOnServer(be, () -> { try { mRequestItems.invoke(peripheral, finalItems, address); } catch (Exception ignored) {} });
                } catch (Exception e) { return LuaValue.FALSE; }
                return LuaValue.TRUE;
            }
        });

        // getAllRemoteSensorData() — pre-computed at table-build time (server thread only)
        {
            LuaValue pre = new LuaTable();
            if (peripheral != null && mGetAllRemoteSensorData != null)
                try { pre = javaToLua(mGetAllRemoteSensorData.invoke(peripheral)); } catch (Exception ignored) {}
            final LuaValue cachedSensorData = pre;
            t.set("getAllRemoteSensorData", new ZeroArgFunction() {
                @Override public LuaValue call() { return cachedSensorData; }
            });
        }

        // getTrackSwitchState(sensorId) → table (server-thread only — peripheral does Level access)
        t.set("getTrackSwitchState", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                if (peripheral == null || mGetTrackSwitchState == null) return LuaValue.NIL;
                if (!isServerThread(be)) return LuaValue.NIL;
                try { return javaToLua(mGetTrackSwitchState.invoke(peripheral, arg.checkjstring())); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getInfo() → {linked, networkId, hubLabel, hubRange, itemTypes, totalItems, position}
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("logiclink"));
                try { info.set("linked",     LuaValue.valueOf((boolean) mIsLinked.invoke(be))); } catch (Exception ignored) {}
                try {
                    Object uuid = mGetNetworkFrequency.invoke(be);
                    info.set("networkId", uuid != null ? LuaValue.valueOf(uuid.toString()) : LuaValue.valueOf("none"));
                } catch (Exception ignored) {}
                try { info.set("hubLabel", LuaValue.valueOf((String) mGetHubLabel.invoke(be))); } catch (Exception ignored) {}
                try { info.set("hubRange", LuaValue.valueOf((int) mGetHubRange.invoke(be))); } catch (Exception ignored) {}
                try {
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary != null) {
                        List<?> stacks = (List<?>) mGetStacks.invoke(summary);
                        long total = 0;
                        for (Object bis : stacks) total += (int) fBisCount.get(bis);
                        info.set("itemTypes",  LuaValue.valueOf(stacks.size()));
                        info.set("totalItems", LuaValue.valueOf((double) total));
                    }
                } catch (Exception ignored) {}
                BlockPos pos = be.getBlockPos();
                LuaTable posT = new LuaTable();
                posT.set("x", LuaValue.valueOf(pos.getX()));
                posT.set("y", LuaValue.valueOf(pos.getY()));
                posT.set("z", LuaValue.valueOf(pos.getZ()));
                info.set("position", posT);
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Sensor (logicsensor) — Logic Sensor
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildSensorTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // getData() → table | nil
        t.set("getData", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mSensorGetCachedData == null) return LuaValue.NIL;
                try { return javaToLua(mSensorGetCachedData.invoke(be)); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getTargetPos() → {x, y, z}
        t.set("getTargetPos", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mSensorGetTargetPos == null) return LuaValue.NIL;
                try {
                    Object pos = mSensorGetTargetPos.invoke(be);
                    if (!(pos instanceof BlockPos bp)) return LuaValue.NIL;
                    LuaTable tbl = new LuaTable();
                    tbl.set("x", LuaValue.valueOf(bp.getX()));
                    tbl.set("y", LuaValue.valueOf(bp.getY()));
                    tbl.set("z", LuaValue.valueOf(bp.getZ()));
                    return tbl;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getPosition() → {x, y, z}
        t.set("getPosition", new ZeroArgFunction() {
            @Override public LuaValue call() {
                BlockPos pos = be.getBlockPos();
                LuaTable tbl = new LuaTable();
                tbl.set("x", LuaValue.valueOf(pos.getX()));
                tbl.set("y", LuaValue.valueOf(pos.getY()));
                tbl.set("z", LuaValue.valueOf(pos.getZ()));
                return tbl;
            }
        });

        // getTargetPosition() — README naming alias for getTargetPos
        t.set("getTargetPosition", t.get("getTargetPos"));

        // isLinked() → boolean
        t.set("isLinked", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mSensorIsLinked == null) return LuaValue.FALSE;
                try { return LuaValue.valueOf((boolean) mSensorIsLinked.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getNetworkID() → string | nil
        t.set("getNetworkID", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mSensorGetNetworkFrequency == null) return LuaValue.NIL;
                try {
                    Object uuid = mSensorGetNetworkFrequency.invoke(be);
                    return uuid != null ? LuaValue.valueOf(uuid.toString()) : LuaValue.NIL;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // refresh() — force refresh of cached sensor data (best-effort)
        t.set("refresh", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mSensorRefresh != null) {
                    try { mSensorRefresh.invoke(be); } catch (Exception ignored) {}
                }
                return LuaValue.NONE;
            }
        });

        // getTargetData() → fresh data table (alias of getData; sensor caches updates each tick)
        t.set("getTargetData", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mSensorRefresh != null) {
                    try { mSensorRefresh.invoke(be); } catch (Exception ignored) {}
                }
                if (mSensorGetCachedData == null) return LuaValue.NIL;
                try { return javaToLua(mSensorGetCachedData.invoke(be)); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getInfo() → {linked, networkId, targetPos, position, data}
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("logicsensor"));
                if (mSensorIsLinked != null)
                    try { info.set("linked", LuaValue.valueOf((boolean) mSensorIsLinked.invoke(be))); } catch (Exception ignored) {}
                if (mSensorGetNetworkFrequency != null)
                    try {
                        Object uuid = mSensorGetNetworkFrequency.invoke(be);
                        info.set("networkId", uuid != null ? LuaValue.valueOf(uuid.toString()) : LuaValue.valueOf("none"));
                    } catch (Exception ignored) {}
                if (mSensorGetTargetPos != null)
                    try {
                        Object pos = mSensorGetTargetPos.invoke(be);
                        if (pos instanceof BlockPos bp) {
                            LuaTable tp = new LuaTable();
                            tp.set("x", LuaValue.valueOf(bp.getX()));
                            tp.set("y", LuaValue.valueOf(bp.getY()));
                            tp.set("z", LuaValue.valueOf(bp.getZ()));
                            info.set("targetPos", tp);
                        }
                    } catch (Exception ignored) {}
                if (mSensorGetCachedData != null)
                    try { info.set("data", javaToLua(mSensorGetCachedData.invoke(be))); } catch (Exception ignored) {}
                BlockPos p = be.getBlockPos();
                LuaTable posT = new LuaTable();
                posT.set("x", LuaValue.valueOf(p.getX()));
                posT.set("y", LuaValue.valueOf(p.getY()));
                posT.set("z", LuaValue.valueOf(p.getZ()));
                info.set("position", posT);
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Motor (creative_logic_motor) — Creative Logic Motor
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildMotorTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // isEnabled() → boolean
        t.set("isEnabled", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((boolean) mMotorIsEnabled.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // setEnabled(bool)
        t.set("setEnabled", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                try { mMotorSetEnabled.invoke(be, v.toboolean()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // enable() — alias for setEnabled(true)
        t.set("enable", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { mMotorSetEnabled.invoke(be, true); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // disable() — alias for setEnabled(false)
        t.set("disable", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { mMotorSetEnabled.invoke(be, false); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // stop() — set speed to 0 and disable
        t.set("stop", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { mMotorSetSpeed.invoke(be, 0); } catch (Exception ignored) {}
                try { mMotorSetEnabled.invoke(be, false); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // isRunning() — enabled and speed != 0
        t.set("isRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    boolean en = (boolean) mMotorIsEnabled.invoke(be);
                    int spd = (int) mMotorGetSpeed.invoke(be);
                    return LuaValue.valueOf(en && spd != 0);
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getSpeed() → int  (getMotorSpeed)
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((int) mMotorGetSpeed.invoke(be)); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // setSpeed(n)  (setMotorSpeed)
        t.set("setSpeed", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                try { mMotorSetSpeed.invoke(be, n.checkint()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getActualSpeed() → number
        t.set("getActualSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mMotorGetActualSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getStressCapacity() → number
        t.set("getStressCapacity", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mMotorGetStressCapacity == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf(((Number) mMotorGetStressCapacity.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getStressUsage() → number
        t.set("getStressUsage", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mMotorGetStressUsage == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf(((Number) mMotorGetStressUsage.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // isSequenceRunning() → boolean
        t.set("isSequenceRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mMotorIsSequenceRunning == null) return LuaValue.FALSE;
                try { return LuaValue.valueOf((boolean) mMotorIsSequenceRunning.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getSequenceSize() → int
        t.set("getSequenceSize", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mMotorGetSequenceSize == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf((int) mMotorGetSequenceSize.invoke(be)); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getInfo() → table (full status, mirrors LogicLinkPeripheral.getRemoteMotorInfo)
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("creative_motor"));
                try { info.set("enabled",        LuaValue.valueOf((boolean) mMotorIsEnabled.invoke(be))); } catch (Exception ignored) {}
                try { info.set("targetSpeed",    LuaValue.valueOf((int) mMotorGetSpeed.invoke(be))); }      catch (Exception ignored) {}
                try { info.set("actualSpeed",    LuaValue.valueOf(((Number) mMotorGetActualSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                if (mMotorGetStressCapacity != null)
                    try { info.set("stressCapacity", LuaValue.valueOf(((Number) mMotorGetStressCapacity.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                if (mMotorGetStressUsage != null)
                    try { info.set("stressUsage",    LuaValue.valueOf(((Number) mMotorGetStressUsage.invoke(be)).doubleValue())); }    catch (Exception ignored) {}
                if (mMotorIsSequenceRunning != null)
                    try { info.set("sequenceRunning", LuaValue.valueOf((boolean) mMotorIsSequenceRunning.invoke(be))); } catch (Exception ignored) {}
                if (mMotorGetSequenceSize != null)
                    try { info.set("sequenceSize",    LuaValue.valueOf((int) mMotorGetSequenceSize.invoke(be))); }      catch (Exception ignored) {}
                return info;
            }
        });

        // ── Sequence API ───────────────────────────────────────────────────
        t.set("clearSequence", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mMotorClearSequence == null) return LuaValue.NONE;
                try { mMotorClearSequence.invoke(be); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("addRotateStep", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (mMotorAddRotateStep == null) return LuaValue.NONE;
                try { mMotorAddRotateStep.invoke(be, (float) args.checkdouble(1), args.checkint(2)); }
                catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("addWaitStep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                if (mMotorAddWaitStep == null) return LuaValue.NONE;
                try { mMotorAddWaitStep.invoke(be, n.checkint()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("addSpeedStep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                if (mMotorAddSpeedStep == null) return LuaValue.NONE;
                try { mMotorAddSpeedStep.invoke(be, n.checkint()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("runSequence", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                if (mMotorRunSequence == null) return LuaValue.NONE;
                try { mMotorRunSequence.invoke(be, v.toboolean()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("stopSequence", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mMotorStopSequence == null) return LuaValue.NONE;
                try { mMotorStopSequence.invoke(be); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Drive (logic_drive) — Logic Drive
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildDriveTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // isEnabled() → boolean
        t.set("isEnabled", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((boolean) mDriveIsEnabled.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // setEnabled(bool)
        t.set("setEnabled", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                try { mDriveSetEnabled.invoke(be, v.toboolean()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // enable() — alias for setEnabled(true)
        t.set("enable", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { mDriveSetEnabled.invoke(be, true); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // disable() — alias for setEnabled(false)
        t.set("disable", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { mDriveSetEnabled.invoke(be, false); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getInputSpeed() → number
        t.set("getInputSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mDriveGetInputSpeed == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf(((Number) mDriveGetInputSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getOutputSpeed() → number
        t.set("getOutputSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mDriveGetOutputSpeed == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf(((Number) mDriveGetOutputSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });
        t.set("getModifier", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mDriveGetModifier.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(1.0); }
            }
        });

        // setModifier(n)
        t.set("setModifier", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                try { mDriveSetModifier.invoke(be, n.checkdouble()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // isReversed() → boolean
        t.set("isReversed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((boolean) mDriveIsReversed.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // setReversed(bool)
        t.set("setReversed", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                try { mDriveSetReversed.invoke(be, v.toboolean()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getInfo() → table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("drive"));
                try { info.set("enabled",  LuaValue.valueOf((boolean) mDriveIsEnabled.invoke(be))); }  catch (Exception ignored) {}
                try { info.set("modifier", LuaValue.valueOf(((Number) mDriveGetModifier.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                try { info.set("reversed", LuaValue.valueOf((boolean) mDriveIsReversed.invoke(be))); } catch (Exception ignored) {}
                if (mDriveGetInputSpeed != null)
                    try { info.set("inputSpeed",  LuaValue.valueOf(((Number) mDriveGetInputSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                if (mDriveGetOutputSpeed != null)
                    try { info.set("outputSpeed", LuaValue.valueOf(((Number) mDriveGetOutputSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                if (mDriveIsSequenceRunning != null)
                    try { info.set("sequenceRunning", LuaValue.valueOf((boolean) mDriveIsSequenceRunning.invoke(be))); } catch (Exception ignored) {}
                if (mDriveGetSequenceSize != null)
                    try { info.set("sequenceSize",    LuaValue.valueOf((int) mDriveGetSequenceSize.invoke(be))); } catch (Exception ignored) {}
                return info;
            }
        });

        // ── Sequence API ───────────────────────────────────────────────────
        t.set("clearSequence", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mDriveClearSequence == null) return LuaValue.NONE;
                try { mDriveClearSequence.invoke(be); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("addRotateStep", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (mDriveAddRotateStep == null) return LuaValue.NONE;
                try { mDriveAddRotateStep.invoke(be, (float) args.checkdouble(1), (float) args.checkdouble(2)); }
                catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("addWaitStep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                if (mDriveAddWaitStep == null) return LuaValue.NONE;
                try { mDriveAddWaitStep.invoke(be, n.checkint()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("addModifierStep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                if (mDriveAddModifierStep == null) return LuaValue.NONE;
                try { mDriveAddModifierStep.invoke(be, (float) n.checkdouble()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("runSequence", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                if (mDriveRunSequence == null) return LuaValue.NONE;
                try { mDriveRunSequence.invoke(be, v.toboolean()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("stopSequence", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mDriveStopSequence == null) return LuaValue.NONE;
                try { mDriveStopSequence.invoke(be); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });
        t.set("isSequenceRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mDriveIsSequenceRunning == null) return LuaValue.FALSE;
                try { return LuaValue.valueOf((boolean) mDriveIsSequenceRunning.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });
        t.set("getSequenceSize", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mDriveGetSequenceSize == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf((int) mDriveGetSequenceSize.invoke(be)); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Train Controller (reflective proxy of TrainControllerPeripheral)
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildTrainCtrlTable(BlockEntity be, com.apocscode.byteblock.computer.JavaOS callingOs) {
        LuaTable t = new LuaTable();
        Object peri = null;
        if (ctorTrainCtrl != null) {
            try { peri = ctorTrainCtrl.newInstance(be); } catch (Exception ignored) {}
        }
        final Object peripheral = peri;

        addResult0Precomputed(t, "getTrains",          peripheral, mTcGetTrains);
        addResult0Precomputed(t, "getStations",        peripheral, mTcGetStations);
        addResult0Precomputed(t, "getSignals",         peripheral, mTcGetSignals);
        addResult0Precomputed(t, "getObservers",       peripheral, mTcGetObservers);
        addResult0Precomputed(t, "getNetworkOverview", peripheral, mTcGetNetworkOverview);
        addResult1S(t, "getTrain",                     peripheral, mTcGetTrain, be);

        // getTrainCount, getRefreshInterval — pre-computed at table-build time
        {
            LuaValue preTc = LuaValue.valueOf(0);
            if (peripheral != null && mTcGetTrainCount != null)
                try { preTc = LuaValue.valueOf((int) mTcGetTrainCount.invoke(peripheral)); } catch (Exception ignored) {}
            final LuaValue cachedTrainCount = preTc;
            t.set("getTrainCount", new ZeroArgFunction() {
                @Override public LuaValue call() { return cachedTrainCount; }
            });
        }
        {
            LuaValue preRi = LuaValue.valueOf(20);
            if (peripheral != null && mTcGetRefreshInterval != null)
                try { preRi = LuaValue.valueOf((int) mTcGetRefreshInterval.invoke(peripheral)); } catch (Exception ignored) {}
            final LuaValue cachedRefreshInterval = preRi;
            t.set("getRefreshInterval", new ZeroArgFunction() {
                @Override public LuaValue call() { return cachedRefreshInterval; }
            });
        }

        t.set("setRefreshInterval", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                if (peripheral != null && mTcSetRefreshInterval != null) {
                    final int val = n.checkint();
                    runOnServer(be, () -> { try { mTcSetRefreshInterval.invoke(peripheral, val); } catch (Exception ignored) {} });
                }
                return LuaValue.NONE;
            }
        });

        t.set("refresh", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (peripheral != null && mTcRefresh != null) {
                    runOnServer(be, () -> { try { mTcRefresh.invoke(peripheral); } catch (Exception ignored) {} });
                }
                return LuaValue.NONE;
            }
        });

        // getPosition() — convenience
        t.set("getPosition", new ZeroArgFunction() {
            @Override public LuaValue call() {
                BlockPos pos = be.getBlockPos();
                LuaTable tbl = new LuaTable();
                tbl.set("x", LuaValue.valueOf(pos.getX()));
                tbl.set("y", LuaValue.valueOf(pos.getY()));
                tbl.set("z", LuaValue.valueOf(pos.getZ()));
                return tbl;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Redstone Controller
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildRedstoneTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // setOutput(item1, item2, power)
        t.set("setOutput", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (mRedstoneSetOutput == null) return LuaValue.NONE;
                try { mRedstoneSetOutput.invoke(be, args.checkjstring(1), args.checkjstring(2), args.checkint(3)); }
                catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getInput(item1, item2) → int
        t.set("getInput", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (mRedstoneGetInput == null) return LuaValue.valueOf(0);
                try { return javaToLua(mRedstoneGetInput.invoke(be, args.checkjstring(1), args.checkjstring(2))); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getChannels() → [{item1, item2, mode, power}]
        t.set("getChannels", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mRedstoneGetChannelList == null) return new LuaTable();
                try { return javaToLua(mRedstoneGetChannelList.invoke(be)); }
                catch (Exception e) { return new LuaTable(); }
            }
        });

        // setAllOutputs(power)
        t.set("setAllOutputs", new OneArgFunction() {
            @Override public LuaValue call(LuaValue n) {
                if (mRedstoneSetAllOutputs == null) return LuaValue.NONE;
                try { mRedstoneSetAllOutputs.invoke(be, n.checkint()); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getOutput(item1, item2) → int
        t.set("getOutput", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (mRedstoneGetOutput == null) return LuaValue.valueOf(0);
                try { return javaToLua(mRedstoneGetOutput.invoke(be, args.checkjstring(1), args.checkjstring(2))); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // removeChannel(item1, item2)
        t.set("removeChannel", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (mRedstoneRemoveChannel == null) return LuaValue.NONE;
                try { mRedstoneRemoveChannel.invoke(be, args.checkjstring(1), args.checkjstring(2)); }
                catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // clearChannels()
        t.set("clearChannels", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mRedstoneClearChannels == null) return LuaValue.NONE;
                try { mRedstoneClearChannels.invoke(be); } catch (Exception ignored) {}
                return LuaValue.NONE;
            }
        });

        // getPosition() → {x, y, z}
        t.set("getPosition", new ZeroArgFunction() {
            @Override public LuaValue call() {
                BlockPos pos = be.getBlockPos();
                LuaTable tbl = new LuaTable();
                tbl.set("x", LuaValue.valueOf(pos.getX()));
                tbl.set("y", LuaValue.valueOf(pos.getY()));
                tbl.set("z", LuaValue.valueOf(pos.getZ()));
                return tbl;
            }
        });

        // getInfo() → {channels, position}
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("redstone_controller"));
                if (mRedstoneGetChannelList != null)
                    try { info.set("channels", javaToLua(mRedstoneGetChannelList.invoke(be))); } catch (Exception ignored) {}
                BlockPos p = be.getBlockPos();
                LuaTable posT = new LuaTable();
                posT.set("x", LuaValue.valueOf(p.getX()));
                posT.set("y", LuaValue.valueOf(p.getY()));
                posT.set("z", LuaValue.valueOf(p.getZ()));
                info.set("position", posT);
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache initializer
    // ══════════════════════════════════════════════════════════════════════

    private static boolean ensureCache() {
        if (cacheReady) return clsHubBE != null;
        synchronized (LogicLinkPeripheralAdapter.class) {
            if (cacheReady) return clsHubBE != null;
            org.slf4j.Logger log = com.apocscode.byteblock.ByteBlock.LOGGER;

            // ── Mandatory: Hub BE class. If this fails, LogicLink isn't on the
            //    classpath and we cannot adapt anything. Everything else is optional.
            try {
                clsHubBE = Class.forName("com.apocscode.logiclink.block.LogicLinkBlockEntity");
                log.info("[LogicLinkAdapter] Loaded Hub BE class OK");
            } catch (Throwable t) {
                log.warn("[LogicLinkAdapter] Hub BE class missing — LogicLink not loaded? {}", t.toString());
                clsHubBE = null;
                cacheReady = true;
                return false;
            }

            // ── Optional BE classes ────────────────────────────────────────
            clsSensorBE    = safeClass("com.apocscode.logiclink.block.LogicSensorBlockEntity");
            clsMotorBE     = safeClass("com.apocscode.logiclink.block.CreativeLogicMotorBlockEntity");
            clsDriveBE     = safeClass("com.apocscode.logiclink.block.LogicDriveBlockEntity");
            clsRedstoneBE  = safeClass("com.apocscode.logiclink.block.RedstoneControllerBlockEntity");
            clsTrainCtrlBE = safeClass("com.apocscode.logiclink.block.TrainControllerBlockEntity");

            // ── Hub BE direct methods (all individually safe) ──────────────
            mIsLinked              = safeMethod(clsHubBE, "isLinked");
            mGetNetworkSummary     = safeMethod(clsHubBE, "getNetworkSummary");
            mGetNetworkFrequency   = safeMethod(clsHubBE, "getNetworkFrequency");
            mGetHubRange           = safeMethod(clsHubBE, "getHubRange");
            mSetHubRange           = safeMethod(clsHubBE, "setHubRange", int.class);
            mGetHubLabel           = safeMethod(clsHubBE, "getHubLabel");
            mSetHubLabel           = safeMethod(clsHubBE, "setHubLabel", String.class);
            mRefreshNetworkSummary = safeMethod(clsHubBE, "refreshNetworkSummary");

            // ── InventorySummary (Create) ──────────────────────────────────
            try {
                Class<?> clsInventorySummary =
                        Class.forName("com.simibubi.create.content.logistics.packager.InventorySummary");
                Class<?> clsBigItemStack =
                        Class.forName("com.simibubi.create.content.logistics.BigItemStack");
                mGetStacks = safeMethod(clsInventorySummary, "getStacks");
                try { fBisStack = clsBigItemStack.getField("stack"); fBisStack.setAccessible(true); }
                catch (Throwable t) { log.warn("[LogicLinkAdapter] BigItemStack.stack field missing: {}", t.toString()); }
                try { fBisCount = clsBigItemStack.getField("count"); fBisCount.setAccessible(true); }
                catch (Throwable t) { log.warn("[LogicLinkAdapter] BigItemStack.count field missing: {}", t.toString()); }
            } catch (Throwable t) {
                log.warn("[LogicLinkAdapter] Create InventorySummary/BigItemStack not found: {}", t.toString());
            }

            // ── Hub Peripheral (reflective ctor + methods) ─────────────────
            Class<?> clsHubPeri = safeClass("com.apocscode.logiclink.peripheral.LogicLinkPeripheral");
            if (clsHubPeri != null) {
                try { ctorHub = clsHubPeri.getConstructor(clsHubBE); }
                catch (Throwable t) { log.warn("[LogicLinkAdapter] LogicLinkPeripheral ctor missing: {}", t.toString()); }
                mGetLinks                    = safeMethod(clsHubPeri, "getLinks");
                mGetSensors                  = safeMethod(clsHubPeri, "getSensors");
                mGetDevices                  = safeMethod(clsHubPeri, "getDevices");
                mGetGauges                   = safeMethod(clsHubPeri, "getGauges");
                mGetPosition                 = safeMethod(clsHubPeri, "getPosition");
                mGetRemoteSensorData         = safeMethod(clsHubPeri, "getRemoteSensorData", String.class);
                mGetRemoteMotorInfo          = safeMethod(clsHubPeri, "getRemoteMotorInfo",  String.class);
                mEnableRemote                = safeMethod(clsHubPeri, "enableRemote",        String.class, boolean.class);
                mSetRemoteSpeed              = safeMethod(clsHubPeri, "setRemoteSpeed",      String.class, int.class);
                mSetRemoteModifier           = safeMethod(clsHubPeri, "setRemoteModifier",   String.class, double.class);
                mSetRemoteReversed           = safeMethod(clsHubPeri, "setRemoteReversed",   String.class, boolean.class);
                mSetDeviceLabel              = safeMethod(clsHubPeri, "setDeviceLabel",      String.class, String.class);
                mGetDeviceLabel              = safeMethod(clsHubPeri, "getDeviceLabel",      String.class);
                mCycleTrackSwitch            = safeMethod(clsHubPeri, "cycleTrackSwitch",    String.class);
                mGetTrainBlockData           = safeMethod(clsHubPeri, "getTrainBlockData",   String.class);
                mSetRemoteRedstoneOutput     = safeMethod(clsHubPeri, "setRemoteRedstoneOutput",
                        String.class, String.class, String.class, int.class);
                mGetRemoteRedstoneInput      = safeMethod(clsHubPeri, "getRemoteRedstoneInput",
                        String.class, String.class, String.class);
                mGetRemoteRedstoneChannels   = safeMethod(clsHubPeri, "getRemoteRedstoneChannels", String.class);
                mSetAllRemoteRedstoneOutputs = safeMethod(clsHubPeri, "setAllRemoteRedstoneOutputs",
                        String.class, int.class);
                mRequestItem                 = safeMethod(clsHubPeri, "requestItem",
                        String.class, int.class, String.class);
                mRequestItems                = safeMethod(clsHubPeri, "requestItems",
                        Map.class, String.class);
                mGetAllRemoteSensorData      = safeMethod(clsHubPeri, "getAllRemoteSensorData");
                mGetTrackSwitchState         = safeMethod(clsHubPeri, "getTrackSwitchState", String.class);
            } else {
                log.warn("[LogicLinkAdapter] LogicLinkPeripheral class not found (network/remote methods disabled)");
            }

            // ── Sensor BE direct methods ───────────────────────────────────
            if (clsSensorBE != null) {
                mSensorGetCachedData       = safeMethod(clsSensorBE, "getCachedData");
                mSensorGetTargetPos        = safeMethod(clsSensorBE, "getTargetPos");
                mSensorIsLinked            = safeMethod(clsSensorBE, "isLinked");
                mSensorGetNetworkFrequency = safeMethod(clsSensorBE, "getNetworkFrequency");
                mSensorRefresh             = safeMethod(clsSensorBE, "refresh");
                if (mSensorRefresh == null) mSensorRefresh = safeMethod(clsSensorBE, "refreshSensorData");
                if (mSensorRefresh == null) mSensorRefresh = safeMethod(clsSensorBE, "updateCachedData");
            }

            // ── Motor BE direct methods ────────────────────────────────────
            if (clsMotorBE != null) {
                mMotorIsEnabled         = safeMethod(clsMotorBE, "isEnabled");
                mMotorSetEnabled        = safeMethod(clsMotorBE, "setEnabled",       boolean.class);
                mMotorGetSpeed          = safeMethod(clsMotorBE, "getMotorSpeed");
                mMotorSetSpeed          = safeMethod(clsMotorBE, "setMotorSpeed",    int.class);
                mMotorGetActualSpeed    = safeMethod(clsMotorBE, "getActualSpeed");
                mMotorGetStressCapacity = safeMethod(clsMotorBE, "getStressCapacityValue");
                mMotorGetStressUsage    = safeMethod(clsMotorBE, "getStressUsageValue");
                mMotorIsSequenceRunning = safeMethod(clsMotorBE, "isSequenceRunning");
                mMotorGetSequenceSize   = safeMethod(clsMotorBE, "getSequenceSize");
                mMotorClearSequence     = safeMethod(clsMotorBE, "clearSequence");
                mMotorAddRotateStep     = safeMethod(clsMotorBE, "addRotateStep", float.class, int.class);
                mMotorAddWaitStep       = safeMethod(clsMotorBE, "addWaitStep",   int.class);
                mMotorAddSpeedStep      = safeMethod(clsMotorBE, "addSpeedStep",  int.class);
                mMotorRunSequence       = safeMethod(clsMotorBE, "runSequence",   boolean.class);
                mMotorStopSequence      = safeMethod(clsMotorBE, "stopSequence");
            }

            // ── Drive BE direct methods ────────────────────────────────────
            if (clsDriveBE != null) {
                mDriveIsEnabled         = safeMethod(clsDriveBE, "isMotorEnabled");
                mDriveSetEnabled        = safeMethod(clsDriveBE, "setMotorEnabled",  boolean.class);
                mDriveGetModifier       = safeMethod(clsDriveBE, "getSpeedModifier");
                mDriveSetModifier       = safeMethod(clsDriveBE, "setSpeedModifier", float.class);
                mDriveIsReversed        = safeMethod(clsDriveBE, "isReversed");
                mDriveSetReversed       = safeMethod(clsDriveBE, "setReversed",      boolean.class);
                mDriveGetInputSpeed     = safeMethod(clsDriveBE, "getInputSpeed");
                mDriveGetOutputSpeed    = safeMethod(clsDriveBE, "getOutputSpeed");
                mDriveClearSequence     = safeMethod(clsDriveBE, "clearSequence");
                mDriveAddRotateStep     = safeMethod(clsDriveBE, "addRotateStep",   float.class, float.class);
                mDriveAddWaitStep       = safeMethod(clsDriveBE, "addWaitStep",     int.class);
                mDriveAddModifierStep   = safeMethod(clsDriveBE, "addModifierStep", float.class);
                mDriveRunSequence       = safeMethod(clsDriveBE, "runSequence",     boolean.class);
                mDriveStopSequence      = safeMethod(clsDriveBE, "stopSequence");
                mDriveIsSequenceRunning = safeMethod(clsDriveBE, "isSequenceRunning");
                mDriveGetSequenceSize   = safeMethod(clsDriveBE, "getSequenceSize");
            }

            // ── Redstone Controller BE direct methods ──────────────────────
            if (clsRedstoneBE != null) {
                mRedstoneSetOutput      = safeMethod(clsRedstoneBE, "setOutput",
                        String.class, String.class, int.class);
                mRedstoneGetInput       = safeMethod(clsRedstoneBE, "getInput",
                        String.class, String.class);
                mRedstoneGetChannelList = safeMethod(clsRedstoneBE, "getChannelList");
                mRedstoneSetAllOutputs  = safeMethod(clsRedstoneBE, "setAllOutputs", int.class);
                mRedstoneGetOutput      = safeMethod(clsRedstoneBE, "getOutput",     String.class, String.class);
                mRedstoneRemoveChannel  = safeMethod(clsRedstoneBE, "removeChannel", String.class, String.class);
                mRedstoneClearChannels  = safeMethod(clsRedstoneBE, "clearChannels");
            }

            // ── Train Controller (reflective peripheral proxy) ────────────
            if (clsTrainCtrlBE != null) {
                Class<?> clsTcPeri = safeClass("com.apocscode.logiclink.peripheral.TrainControllerPeripheral");
                if (clsTcPeri != null) {
                    try { ctorTrainCtrl = clsTcPeri.getConstructor(clsTrainCtrlBE); }
                    catch (Throwable t) { log.warn("[LogicLinkAdapter] TrainControllerPeripheral ctor missing: {}", t.toString()); }
                    mTcGetTrains          = safeMethod(clsTcPeri, "getTrains");
                    mTcGetTrain           = safeMethod(clsTcPeri, "getTrain", String.class);
                    mTcGetStations        = safeMethod(clsTcPeri, "getStations");
                    mTcGetSignals         = safeMethod(clsTcPeri, "getSignals");
                    mTcGetObservers       = safeMethod(clsTcPeri, "getObservers");
                    mTcGetNetworkOverview = safeMethod(clsTcPeri, "getNetworkOverview");
                    mTcGetTrainCount      = safeMethod(clsTcPeri, "getTrainCount");
                    mTcGetRefreshInterval = safeMethod(clsTcPeri, "getRefreshInterval");
                    mTcSetRefreshInterval = safeMethod(clsTcPeri, "setRefreshInterval", int.class);
                    mTcRefresh            = safeMethod(clsTcPeri, "refresh");
                }
            }

            log.info("[LogicLinkAdapter] Cache built: hub={} sensor={} motor={} drive={} redstone={} train={}",
                    clsHubBE != null, clsSensorBE != null, clsMotorBE != null,
                    clsDriveBE != null, clsRedstoneBE != null, clsTrainCtrlBE != null);
            cacheReady = true;
            return true;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper: table-building shortcuts
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Zero-arg peripheral method → result, pre-computed on the calling thread (must be server thread).
     * The result is captured at table-build time; the closure just returns it.
     * This avoids Level.getChunk() deadlocks when the method is called from a Lua coroutine thread.
     */
    private static void addResult0Precomputed(LuaTable t, String name, Object peri, Method m) {
        LuaValue precomputed;
        if (peri == null || m == null) {
            precomputed = new LuaTable();
        } else {
            try { precomputed = javaToLua(m.invoke(peri)); }
            catch (Exception e) { precomputed = new LuaTable(); }
        }
        final LuaValue result = precomputed;
        t.set(name, new ZeroArgFunction() {
            @Override public LuaValue call() { return result; }
        });
    }

    /** Dispatch a Runnable to the server thread. Safe to call from any thread. Fire-and-forget. */
    private static void runOnServer(BlockEntity be, Runnable r) {
        if (be.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)
            sl.getServer().execute(r);
    }

    /** Returns true if the current thread is the Minecraft server thread. */
    private static boolean isServerThread(BlockEntity be) {
        if (be.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)
            return sl.getServer().isSameThread();
        return false;
    }

    /** Zero-arg peripheral method → result. */
    private static void addResult0(LuaTable t, String name, Object peri, Method m) {
        t.set(name, new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (peri == null || m == null) return new LuaTable();
                try { return javaToLua(m.invoke(peri)); }
                catch (Exception e) { return new LuaTable(); }
            }
        });
    }

    /** Single-String-arg peripheral method → result (server-thread only; returns NIL if on coroutine thread). */
    private static void addResult1S(LuaTable t, String name, Object peri, Method m, BlockEntity be) {
        t.set(name, new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                if (peri == null || m == null) return LuaValue.NIL;
                if (!isServerThread(be)) return LuaValue.NIL;
                try { return javaToLua(m.invoke(peri, arg.checkjstring())); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper: Java → LuaValue
    // ══════════════════════════════════════════════════════════════════════

    static LuaValue javaToLua(Object obj) {
        if (obj == null)                    return LuaValue.NIL;
        if (obj instanceof Boolean  b)      return LuaValue.valueOf(b);
        if (obj instanceof Integer  i)      return LuaValue.valueOf(i);
        if (obj instanceof Long     l)      return LuaValue.valueOf(l);
        if (obj instanceof Double   d)      return LuaValue.valueOf(d);
        if (obj instanceof Float    f)      return LuaValue.valueOf(f.doubleValue());
        if (obj instanceof Number   n)      return LuaValue.valueOf(n.doubleValue());
        if (obj instanceof String   s)      return LuaValue.valueOf(s);
        if (obj instanceof java.util.UUID u)return LuaValue.valueOf(u.toString());
        if (obj instanceof Map<?, ?> map) {
            LuaTable t = new LuaTable();
            for (Map.Entry<?, ?> e : map.entrySet())
                t.set(LuaValue.valueOf(e.getKey().toString()), javaToLua(e.getValue()));
            return t;
        }
        if (obj instanceof List<?> list) {
            LuaTable t = new LuaTable();
            int i = 1;
            for (Object item : list) t.set(i++, javaToLua(item));
            return t;
        }
        return LuaValue.valueOf(obj.toString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reflection helpers
    // ══════════════════════════════════════════════════════════════════════

    private static Class<?> safeClass(String name) {
        try { return Class.forName(name); } catch (Exception e) { return null; }
    }

    private static Method safeMethod(Class<?> cls, String name, Class<?>... params) {
        try { return cls.getMethod(name, params); } catch (Exception e) { return null; }
    }
}
