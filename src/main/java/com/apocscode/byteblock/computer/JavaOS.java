package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.block.entity.DriveBlockEntity;
import com.apocscode.byteblock.computer.programs.DesktopProgram;
import com.apocscode.byteblock.computer.programs.ShellProgram;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * JavaOS — the operating system kernel for ByteBlock computers.
 * Manages the event queue, process table, file system, terminal, and timers.
 */
public class JavaOS {

    public enum State { BOOT, RUNNING, SHUTDOWN }

    private State state;
    private final TerminalBuffer terminal;
    private final PixelBuffer pixelBuffer;
    private final VirtualFileSystem fileSystem;
    private final UUID computerId;
    private String label;
    private int bluetoothChannel;
    private float textScale = 2.0f;
    /** Last gps_tool:* JSON payload received on channel 9100 (without the prefix). Null if none. */
    private volatile String lastGpsToolBroadcast = null;
    /** Durable key/value app settings persisted through ComputerBlockEntity NBT. */
    private final Map<String, String> persistentData = new LinkedHashMap<>();
    private boolean persistentDataDirty = false;

    // Drive mount system — maps drive letters (D, E, ...) to detected drives
    private final Map<Character, DriveBlockEntity> mountedDrives = new LinkedHashMap<>();

    // World reference (set each tick by block entity)
    private transient net.minecraft.world.level.Level level;
    private transient net.minecraft.core.BlockPos blockPos;

    // Snapshot of nearby block entities, refreshed each server tick on the
    // server thread, then read freely from Lua coroutine threads. Avoids the
    // server-thread/coroutine-thread deadlock that occurs if Lua code tries to
    // walk chunks via Level.getChunk(...) (which dispatches via
    // CompletableFuture.join on the main thread, which is itself blocked
    // resuming the coroutine). Volatile so coroutine threads always see the
    // latest published snapshot.
    private volatile java.util.Map<net.minecraft.core.BlockPos,
            net.minecraft.world.level.block.entity.BlockEntity> peripheralSnapshot
            = java.util.Collections.emptyMap();
    private long lastPeripheralSnapshotTick = -1L;

    // Pre-resolved on the server thread so Lua coroutine threads never call
    // level.getCapability() (which schedules back to the server thread via
    // CompletableFuture, causing the coroutine↔server-thread deadlock).
    private volatile java.util.Map<net.minecraft.core.BlockPos, String> peripheralTypeCache
            = java.util.Collections.emptyMap();
    private volatile java.util.Map<net.minecraft.core.BlockPos, org.luaj.vm2.LuaTable> peripheralTableCache
            = java.util.Collections.emptyMap();
    // IItemHandler for the 6 positions directly adjacent to the computer.
    // Populated server-thread-only so pushItems/pullItems closures never
    // need to call level.getCapability() from the Lua coroutine thread.
    private volatile java.util.Map<net.minecraft.core.BlockPos,
            net.neoforged.neoforge.items.IItemHandler> adjacentItemHandlerCache
            = java.util.Collections.emptyMap();

    // Entity-hosted peripherals (robots/drones) keyed by their UUID.
    // Populated server-thread-only during refreshPeripheralSnapshot().
    private volatile java.util.Map<java.util.UUID, org.luaj.vm2.LuaTable> entityPeripheralTableCache
            = java.util.Collections.emptyMap();

    // Previous peripheral name sets for online/offline event detection.
    private java.util.Set<String> prevPeripheralNames = new java.util.HashSet<>();

    // Optional entity host (set by entity-hosted computers like RobotEntity)
    private transient net.minecraft.world.entity.Entity host;

    // Process management
    private final List<OSProgram> processes;
    private OSProgram foregroundProgram;

    // Event queue
    private final Deque<OSEvent> eventQueue;
    private static final int MAX_EVENTS = 256;

    // Timer system
    private final Map<Integer, Long> timers; // timerId -> tick when it fires
    private int nextTimerId;
    private long tickCount;

    // Alarm system — fires when in-game day-time hour passes target.
    // alarmId -> target hour (0..24, may include wrap to next day).
    private final Map<Integer, Double> alarms = new java.util.LinkedHashMap<>();
    private int nextAlarmId = 1;
    private double prevWorldHour = -1;

    // Boot animation
    private int bootTick;
    private static final int BOOT_DURATION = 40; // 2 seconds
    private boolean rebooting;

    public JavaOS(UUID computerId) {
        this.computerId = computerId;
        this.terminal = new TerminalBuffer();
        this.pixelBuffer = new PixelBuffer();
        this.fileSystem = new VirtualFileSystem();
        this.label = "Computer";
        this.bluetoothChannel = 1;
        this.processes = new ArrayList<>();
        this.eventQueue = new ArrayDeque<>();
        this.timers = new HashMap<>();
        this.nextTimerId = 1;
        this.tickCount = 0;
        this.state = State.BOOT;
        this.bootTick = 0;
        this.rebooting = false;

        installSystemPrograms();
    }

    public void installSystemPrograms() {
        // Write built-in program stubs to /Program Files/
        fileSystem.installSystemFile("/Program Files/shell", "Built-in shell program");
        fileSystem.installSystemFile("/Program Files/edit", "Built-in text editor");
        fileSystem.installSystemFile("/Program Files/explorer", "Built-in file explorer");
        fileSystem.installSystemFile("/Program Files/settings", "Built-in settings");
        fileSystem.installSystemFile("/Program Files/paint", "Built-in paint program");
        fileSystem.installSystemFile("/Program Files/lua", "Built-in Lua 5.2 shell");
        fileSystem.installSystemFile("/Program Files/puzzle", "Built-in puzzle IDE");
        fileSystem.installSystemFile("/Program Files/ide", "Built-in text IDE");
        fileSystem.installSystemFile("/Program Files/calculator", "Built-in calculator");
        fileSystem.installSystemFile("/Program Files/task_manager", "Built-in task manager");
        fileSystem.installSystemFile("/Program Files/bluetooth", "Built-in bluetooth manager");
        fileSystem.installSystemFile("/Program Files/me_dashboard", "Built-in AE2 ME Network dashboard");
        fileSystem.installSystemFile("/Program Files/create_dashboard", "Built-in Create mod machine dashboard");

        // Create default Documents startup file
        if (!fileSystem.exists("/Users/User/Documents/startup")) {
            fileSystem.writeFile("/Users/User/Documents/startup",
                "-- Startup script\nprint(\"Welcome to ByteOS!\")\n");
        }

        // Tiny test program for the IDE
        if (!fileSystem.exists("/Users/User/Documents/test.lua")) {
            fileSystem.writeFile("/Users/User/Documents/test.lua",
                "-- test.lua: IDE feature tester\n"
              + "\n"
              + "local function greet(name)\n"
              + "  print(\"Hello, \" .. name .. \"!\")\n"
              + "end\n"
              + "\n"
              + "local function add(a, b)\n"
              + "  return a + b\n"
              + "end\n"
              + "\n"
              + "-- math test\n"
              + "local x = add(3, 7)\n"
              + "print(\"3 + 7 = \" .. tostring(x))\n"
              + "\n"
              + "-- loop test\n"
              + "for i = 1, 5 do\n"
              + "  greet(\"User\" .. i)\n"
              + "end\n"
              + "\n"
              + "-- table test\n"
              + "local items = {\"pickaxe\", \"torch\", \"cobblestone\"}\n"
              + "for _, item in ipairs(items) do\n"
              + "  print(\"  > \" .. item)\n"
              + "end\n"
              + "\n"
              + "print(\"All tests passed!\")\n");
        }

        // Lamp test script — demonstrates relay API with 3 lamps wired to the relay
        if (!fileSystem.exists("/Users/User/Documents/lamp_test.lua")) {
            fileSystem.writeFile("/Users/User/Documents/lamp_test.lua",
                "-- lamp_test.lua\n"
              + "-- Controls 3 lamps wired to the Redstone Relay.\n"
              + "-- Default sides: top lamp = \"top\", side lamps = \"north\" and \"south\".\n"
              + "-- Change TOP, SIDE1, SIDE2 to match your actual wiring.\n"
              + "\n"
              + "local TOP   = \"top\"\n"
              + "local SIDE1 = \"north\"\n"
              + "local SIDE2 = \"south\"\n"
              + "\n"
              + "print(\"=== Lamp Test ===\")\n"
              + "\n"
              + "if not relay.isConnected() then\n"
              + "  print(\"ERROR: No relay found in Bluetooth range.\")\n"
              + "  print(\"Place the Redstone Relay near this computer.\")\n"
              + "  return\n"
              + "end\n"
              + "\n"
              + "print(\"Relay connected!\")\n"
              + "\n"
              + "-- Show current outputs on all 6 sides\n"
              + "print(\"Current relay outputs:\")\n"
              + "local sides = relay.getSides()\n"
              + "for i = 1, #sides do\n"
              + "  local s   = sides[i]\n"
              + "  local out = relay.getOutput(s)\n"
              + "  local inp = relay.getInput(s)\n"
              + "  print(\"  \" .. s .. \": out=\" .. out .. \"  in=\" .. inp)\n"
              + "end\n"
              + "print(\"\")\n"
              + "\n"
              + "-- Toggle: if all 3 lamps are off, turn them ON; otherwise turn OFF\n"
              + "local t  = relay.getOutput(TOP)\n"
              + "local s1 = relay.getOutput(SIDE1)\n"
              + "local s2 = relay.getOutput(SIDE2)\n"
              + "local allOff = (t == 0 and s1 == 0 and s2 == 0)\n"
              + "local level  = allOff and 15 or 0\n"
              + "\n"
              + "relay.setOutput(TOP,   level)\n"
              + "relay.setOutput(SIDE1, level)\n"
              + "relay.setOutput(SIDE2, level)\n"
              + "\n"
              + "print(\"Set all 3 lamps \" .. (allOff and \"ON  (power=15)\" or \"OFF (power=0)\"))\n"
              + "\n"
              + "-- Verify write\n"
              + "print(\"Verify:\")\n"
              + "print(\"  \" .. TOP   .. \" = \" .. relay.getOutput(TOP))\n"
              + "print(\"  \" .. SIDE1 .. \" = \" .. relay.getOutput(SIDE1))\n"
              + "print(\"  \" .. SIDE2 .. \" = \" .. relay.getOutput(SIDE2))\n"
              + "print(\"\")\n"
              + "print(\"Run lamp_test again to toggle.\")\n");
        }

        // Virtual Button Panel demo + docs for the built-in 16-button panel on this computer.
        if (!fileSystem.exists("/Users/User/Documents/button_demo.lua")) {
            fileSystem.writeFile("/Users/User/Documents/button_demo.lua",
                "-- button_demo.lua\n"
              + "-- Drives this computer's built-in Virtual Button Panel.\n"
              + "-- The computer emits redstone + bundled signals on all 6 sides\n"
              + "-- based on which of the 16 buttons are ON.\n"
              + "\n"
              + "print(\"=== Virtual Button Panel Demo ===\")\n"
              + "\n"
              + "-- Rename the panel and a few buttons\n"
              + "buttons.setPanelLabel(\"Demo Panel\")\n"
              + "buttons.setLabel(0, \"Lamp\")\n"
              + "buttons.setLabel(1, \"Door\")\n"
              + "buttons.setLabel(2, \"Alarm\")\n"
              + "\n"
              + "-- Give them distinct colors (0xRRGGBB)\n"
              + "buttons.setColor(0, 0xFFD700) -- gold\n"
              + "buttons.setColor(1, 0x00AAFF) -- cyan\n"
              + "buttons.setColor(2, 0xFF3030) -- red\n"
              + "\n"
              + "-- Button 2 momentarily pulses (auto-off after 4 ticks)\n"
              + "buttons.setMode(2, \"momentary\")\n"
              + "\n"
              + "-- Button 3 runs a 40-tick (2s) timer when toggled on\n"
              + "buttons.setMode(3, \"timer\")\n"
              + "buttons.setDuration(3, 40)\n"
              + "buttons.setLabel(3, \"2s Timer\")\n"
              + "\n"
              + "-- Toggle the first three buttons on\n"
              + "local mask = buttons.getAll()\n"
              + "if mask == 0 then\n"
              + "  buttons.set(0, true)\n"
              + "  buttons.set(1, true)\n"
              + "  print(\"Buttons 0 + 1 ON. Bundled output = 0x\" .. string.format(\"%04X\", buttons.getAll()))\n"
              + "else\n"
              + "  buttons.setAll(0)\n"
              + "  print(\"All buttons OFF.\")\n"
              + "end\n"
              + "\n"
              + "print(\"Open the Button App in the Start Menu — 'This Computer' will be at the top of the list.\")\n");
        }

        // Built-in documentation for the buttons API
        if (!fileSystem.exists("/Users/User/Documents/docs/buttons.md")) {
            fileSystem.writeFile("/Users/User/Documents/docs/buttons.md",
                "# buttons — Virtual Button Panel API\n"
              + "\n"
              + "Every Computer block carries a built-in 16-button panel. The same panel\n"
              + "that the on-screen Button App drives is exposed to programs through the\n"
              + "global `buttons` table.\n"
              + "\n"
              + "When buttons are ON, the computer block emits redstone (analog) and\n"
              + "bundled-cable signals on all 6 sides, and broadcasts\n"
              + "`button_press:<i>:<0|1>:<Color>` events on its Bluetooth channel.\n"
              + "\n"
              + "## Functions\n"
              + "\n"
              + "| Call                              | Description                           |\n"
              + "|-----------------------------------|---------------------------------------|\n"
              + "| `buttons.set(i, on)`              | Turn button i (0-15) on/off           |\n"
              + "| `buttons.get(i)`                  | Returns boolean                       |\n"
              + "| `buttons.toggle(i)`               | Flip button i                         |\n"
              + "| `buttons.setAll(mask)`            | Set all 16 buttons (16-bit mask)      |\n"
              + "| `buttons.getAll()`                | Read the full mask                    |\n"
              + "| `buttons.setMode(i, mode)`        | \"toggle\" / \"momentary\" / \"timer\" / \"delay\" / \"inverted\" |\n"
              + "| `buttons.setDuration(i, ticks)`   | 1..6000 ticks, used by timer/delay    |\n"
              + "| `buttons.setLabel(i, text)`       | Per-button label (max 16 chars)       |\n"
              + "| `buttons.setColor(i, rgb)`        | 0xRRGGBB, or -1 for default           |\n"
              + "| `buttons.setPanelLabel(text)`     | Rename the panel (max 24 chars)       |\n"
              + "| `buttons.setChannel(n)`           | Bluetooth channel (1..256)            |\n"
              + "\n"
              + "## Button modes\n"
              + "\n"
              + "- **toggle**    — classic on/off (default)\n"
              + "- **momentary** — pulses ON for 4 ticks then auto-off\n"
              + "- **timer**     — stays ON for `duration` ticks then auto-off\n"
              + "- **delay**     — waits `duration` ticks then toggles\n"
              + "- **inverted**  — output is negated (ON means low, OFF means high)\n"
              + "\n"
              + "## Example\n"
              + "\n"
              + "```lua\n"
              + "buttons.setPanelLabel(\"Base Controls\")\n"
              + "buttons.setLabel(0, \"Lamp\")\n"
              + "buttons.setColor(0, 0xFFD700)\n"
              + "buttons.set(0, true)\n"
              + "```\n"
              + "\n"
              + "See also `/Users/User/Documents/button_demo.lua`.\n");
        }

        // Robot demo (only meaningful when this OS is hosted by a RobotEntity;
        // on normal computers the calls return false)
        if (!fileSystem.exists("/Users/User/Documents/robot_demo.lua")) {
            fileSystem.writeFile("/Users/User/Documents/robot_demo.lua",
                "-- robot_demo.lua — queues a small dig-and-move routine\n"
              + "-- Only runs on a RobotEntity. Normal computers: robot.* returns false.\n"
              + "if not robot or not robot.queue then\n"
              + "  print(\"No robot API (not hosted by a robot).\")\n"
              + "  return\n"
              + "end\n"
              + "\n"
              + "print(\"Fuel: \" .. robot.getFuel())\n"
              + "print(\"Facing: \" .. tostring(robot.getFacing()))\n"
              + "\n"
              + "for i = 1, 3 do\n"
              + "  robot.dig()\n"
              + "  robot.forward()\n"
              + "end\n"
              + "robot.turnLeft()\n"
              + "print(\"Queued: \" .. robot.commandsQueued() .. \" commands\")\n");
        }

        // Drone demo — broadcasts BT commands to any drone on the OS channel.
        if (!fileSystem.exists("/Users/User/Documents/drone_demo.lua")) {
            fileSystem.writeFile("/Users/User/Documents/drone_demo.lua",
                "-- drone_demo.lua — sends a patrol pattern via Bluetooth\n"
              + "-- Any DroneEntity tuned to this computer's channel obeys.\n"
              + "local ch = os.getComputerChannel and os.getComputerChannel() or 1\n"
              + "print(\"Broadcasting on channel \" .. ch)\n"
              + "\n"
              + "drone.clear()\n"
              + "drone.waypoint(100, 70, 100)\n"
              + "drone.waypoint(120, 70, 100)\n"
              + "drone.waypoint(120, 70, 120)\n"
              + "drone.waypoint(100, 70, 120)\n"
              + "drone.home()\n"
              + "print(\"Patrol queued.\")\n");
        }

        // Robot API docs
        if (!fileSystem.exists("/Users/User/Documents/docs/robot.md")) {
            fileSystem.writeFile("/Users/User/Documents/docs/robot.md",
                "# robot API\n"
              + "\n"
              + "Available only when the OS is hosted by a RobotEntity. All calls\n"
              + "return `false` / `0` / `nil` on non-robot computers so scripts\n"
              + "can safely feature-detect with `if robot and robot.queue then ...`.\n"
              + "\n"
              + "Commands are appended to an internal queue (max 256) and executed\n"
              + "one per tick while the robot has ≥10 FE stored.\n"
              + "\n"
              + "## Movement / action\n"
              + "| Fn | Effect |\n"
              + "|---|---|\n"
              + "| `robot.forward()` | Step 1 block forward |\n"
              + "| `robot.back()` | Step 1 block backward |\n"
              + "| `robot.up()` / `robot.down()` | Step vertically |\n"
              + "| `robot.turnLeft()` / `robot.turnRight()` | Rotate 90° |\n"
              + "| `robot.dig()` / `robot.digUp()` / `robot.digDown()` | Mine block, drops into inventory |\n"
              + "| `robot.place()` | Place selected slot in front |\n"
              + "| `robot.queue(str)` | Append a raw command string |\n"
              + "| `robot.clear()` | Empty the command queue |\n"
              + "\n"
              + "## Inspection\n"
              + "| Fn | Returns |\n"
              + "|---|---|\n"
              + "| `robot.isBusy()` | `true` if queue non-empty |\n"
              + "| `robot.commandsQueued()` | queue length |\n"
              + "| `robot.getFuel()` | stored FE |\n"
              + "| `robot.getFacing()` | `north`/`south`/`east`/`west` |\n"
              + "| `robot.getPos()` | returns `x, y, z` (multi-return) |\n"
              + "| `robot.detect()` / `detectUp()` / `detectDown()` | `true` if solid block there |\n"
              + "\n"
              + "## Inventory (1-indexed slots 1..16)\n"
              + "| Fn | Purpose |\n"
              + "|---|---|\n"
              + "| `robot.select(slot)` | Set active slot |\n"
              + "| `robot.getSelected()` | Current active slot |\n"
              + "| `robot.getItemCount(slot)` | Stack size in slot |\n"
              + "| `robot.getItemName(slot)` | Registry id, e.g. `minecraft:cobblestone` |\n"
              + "| `robot.refuel(slot)` | Burn a fuel item → FE. Returns FE added. |\n"
              + "\n"
              + "Fuel values: coal/charcoal 1600, blaze rod 2400, coal block 16000, lava bucket 20000.\n");
        }

        // Drone API docs
        if (!fileSystem.exists("/Users/User/Documents/docs/drone.md")) {
            fileSystem.writeFile("/Users/User/Documents/docs/drone.md",
                "# drone API\n"
              + "\n"
              + "All calls are fire-and-forget Bluetooth broadcasts on the chosen\n"
              + "channel (defaults to the OS's current channel). Any DroneEntity\n"
              + "listening on that channel within range will obey.\n"
              + "\n"
              + "| Fn | Effect |\n"
              + "|---|---|\n"
              + "| `drone.waypoint(x, y, z [, ch])` | Append a flight waypoint |\n"
              + "| `drone.home([ch])` | Clear waypoints & return to home |\n"
              + "| `drone.clear([ch])` | Clear waypoints, stay in place |\n"
              + "| `drone.hover(bool [, ch])` | Toggle idle hover |\n"
              + "| `drone.refuel(ticks [, ch])` | Remote fuel grant (0..72000) |\n"
              + "\n"
              + "## Binding drones to a channel\n"
              + "1. Right-click a drone with a fuel item (coal, blaze rod, lava\n"
              + "   bucket) to fuel it.\n"
              + "2. The drone registers on BT under its own UUID and listens on\n"
              + "   its own channel (shown when you right-click it bare-handed).\n"
              + "3. Set this computer's channel to match with `bluetooth.setChannel(n)`\n"
              + "   or use `drone.waypoint(x, y, z, n)` to target a specific channel.\n"
              + "\n"
              + "## Protocol (for custom senders)\n"
              + "Plain strings on the drone's channel:\n"
              + "`drone:waypoint:<x>:<y>:<z>` · `drone:home` · `drone:clear`\n"
              + "`drone:hover:<true|false>` · `drone:refuel:<ticks>`\n");
        }

        // Glasses HUD demo — exercises every Tier 1 + Tier 2 widget.
        // Always rewrite so mod updates to this demo reach existing computers.
        fileSystem.writeFile("/Users/User/Documents/glasses_test.lua",
                "-- glasses_test.lua : live demo of every glasses.* widget\n"
              + "-- Wear the Smart Glasses, then run:  lua glasses_test.lua\n"
              + "-- Press Ctrl+T (terminate) or close the shell to stop.\n"
              + "\n"
              + "if not glasses then\n"
              + "  print('glasses API missing -- are you wearing Smart Glasses?')\n"
              + "  return\n"
              + "end\n"
              + "\n"
              + "local samples = {}\n"
              + "for i=1,20 do samples[i] = math.random() end\n"
              + "\n"
              + "local heading  = 0\n"
              + "local pieFill  = 0\n"
              + "local hp       = 1.0\n"
              + "local timerSec = 30\n"
              + "local tick     = 0\n"
              + "\n"
              + "print('glasses demo running -- Ctrl+T to stop')\n"
              + "\n"
              + "while true do\n"
              + "  tick = tick + 1\n"
              + "\n"
              + "  -- rebuild the full widget list every frame\n"
              + "  glasses.clear()\n"
              + "\n"
              + "  glasses.addTitle('t', 'HUD demo running', 0xFFFFFF)\n"
              + "  glasses.addText ('hint', 'frame', tostring(tick))\n"
              + "\n"
              + "  -- animate compass 0..360\n"
              + "  heading = (heading + 6) % 360\n"
              + "  glasses.addCompass('cmp', heading, 0xFFAA00)\n"
              + "\n"
              + "  -- animate pie 0..1\n"
              + "  pieFill = pieFill + 0.05; if pieFill > 1 then pieFill = 0 end\n"
              + "  glasses.addPie('pie', 'CHARGE', pieFill, 0xFFFF00)\n"
              + "\n"
              + "  -- shrinking HP bar  (id, label, min, max, value, color)\n"
              + "  hp = hp - 0.01; if hp < 0 then hp = 1.0 end\n"
              + "  local hpCol = (hp > 0.3) and 0x00FF00 or 0xFF4040\n"
              + "  glasses.addBar('hp', 'HP', 0, 1, hp, hpCol)\n"
              + "  glasses.addBar('mp', 'MP', 0, 1, 0.5, 0x4488FF)\n"
              + "\n"
              + "  -- gauge\n"
              + "  glasses.addGauge('spd', 'SPD', 0, 200, (tick * 3) % 200, 0x00FFAA)\n"
              + "\n"
              + "  -- status light\n"
              + "  glasses.addLight('pwr', 'PWR', 0x00FF00, 'ON')\n"
              + "\n"
              + "  -- countdown timer\n"
              + "  if tick % 4 == 0 then\n"
              + "    timerSec = timerSec - 1; if timerSec < 0 then timerSec = 30 end\n"
              + "  end\n"
              + "  glasses.addTimer('clk', 'BOOST', timerSec, 0x66CCFF)\n"
              + "\n"
              + "  -- rolling sparkline + larger graph\n"
              + "  table.remove(samples, 1)\n"
              + "  samples[#samples+1] = math.random()\n"
              + "  glasses.addSpark('spk', 'load', samples, 0xFFDD00)\n"
              + "  glasses.addGraph('graph', 'TPS', samples, 0x00FF88)\n"
              + "\n"
              + "  -- spinning minimap dots around origin\n"
              + "  local pts = {}\n"
              + "  for i=0,5 do\n"
              + "    local a = (tick * 0.05) + (i * math.pi / 3)\n"
              + "    pts[#pts+1] = math.cos(a) * 30\n"
              + "    pts[#pts+1] = math.sin(a) * 30\n"
              + "    pts[#pts+1] = (i % 2 == 0) and 0xFF0000 or 0x00FFFF\n"
              + "  end\n"
              + "  glasses.addMinimap('map', 0, 0, 50, pts, 0x444444)\n"
              + "\n"
              + "  -- transient alert every 5 seconds\n"
              + "  if tick % 20 == 0 then\n"
              + "    glasses.addAlertT('flash', 'tick '..tick, 0xFFFF00, 2)\n"
              + "  end\n"
              + "\n"
              + "  -- push the whole list to the glasses\n"
              + "  local sent = glasses.flush()\n"
              + "  if tick % 8 == 1 then\n"
              + "    print('flush -> '..tostring(sent)..' wearer(s) on ch '..tostring(glasses.getChannel and glasses.getChannel() or '?'))\n"
              + "    if sent == 0 and glasses.diag then print('  diag: '..glasses.diag()) end\n"
              + "  end\n"
              + "\n"
              + "  sleep(0.25)\n"
              + "end\n");
    }

    // --- Tick / Main Loop ---

    public void tick() {
        tickCount++;

        switch (state) {
            case BOOT -> tickBoot();
            case RUNNING -> tickRunning();
            case SHUTDOWN -> { /* nothing */ }
        }
    }

    private void tickBoot() {
        // Skip the boot animation on first startup — only show it on explicit reboot.
        if (!rebooting) {
            state = State.RUNNING;
            terminal.setTextColor(0);
            terminal.setBackgroundColor(15);
            terminal.clear();
            if (fileSystem.exists("/startup.lua") && fileSystem.isFile("/startup.lua")) {
                launchProgram(new com.apocscode.byteblock.computer.programs.LuaShellProgram("/startup.lua"));
            } else {
                launchProgram(new DesktopProgram());
            }
            return;
        }
        bootTick++;
        if (bootTick == 1) {
            terminal.setTextColor(4); // yellow
            terminal.setBackgroundColor(15); // black
            terminal.clear();
            terminal.setCursorPos(17, 8);
            terminal.write("ByteBlock OS");
            terminal.setTextColor(8); // light gray
            terminal.setCursorPos(18, 10);
            terminal.write("Loading...");
        }
        if (bootTick >= BOOT_DURATION) {
            state = State.RUNNING;
            rebooting = false;
            terminal.setTextColor(0);
            terminal.setBackgroundColor(15);
            terminal.clear();
            // Auto-run /startup.lua if present (CC:Tweaked-style autorun).
            // To disable, delete or rename /startup.lua.
            if (fileSystem.exists("/startup.lua") && fileSystem.isFile("/startup.lua")) {
                launchProgram(new com.apocscode.byteblock.computer.programs.LuaShellProgram("/startup.lua"));
            } else {
                launchProgram(new DesktopProgram());
            }
        }
    }

    private void tickRunning() {
        // Scan for drives every 2 seconds
        if (tickCount % 40 == 0) scanDrives();

        // Fire timers
        Iterator<Map.Entry<Integer, Long>> it = timers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> entry = it.next();
            if (tickCount >= entry.getValue()) {
                pushEvent(new OSEvent(OSEvent.Type.TIMER, entry.getKey()));
                it.remove();
            }
        }

        // Fire alarms — compare current world hour against target. Fires when
        // the hour line is crossed since the last tick, handling midnight wrap.
        if (level != null && !alarms.isEmpty()) {
            double curHour = (level.getDayTime() % 24000L) / 1000.0;
            if (prevWorldHour < 0) prevWorldHour = curHour;
            Iterator<Map.Entry<Integer, Double>> ait = alarms.entrySet().iterator();
            while (ait.hasNext()) {
                Map.Entry<Integer, Double> entry = ait.next();
                double target = entry.getValue();
                boolean fired;
                if (prevWorldHour <= curHour) {
                    fired = (target > prevWorldHour && target <= curHour);
                } else {
                    // Wrapped past midnight this tick.
                    fired = (target > prevWorldHour || target <= curHour);
                }
                if (fired) {
                    pushEvent(new OSEvent(OSEvent.Type.ALARM, entry.getKey()));
                    ait.remove();
                }
            }
            prevWorldHour = curHour;
        }

        // Poll Bluetooth inbox
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(computerId);
        while (msg != null) {
            double dist = msg.senderPos() != null ? Math.sqrt(msg.senderPos().distSqr(new net.minecraft.core.BlockPos(0, 0, 0))) : 0;
            String senderId = msg.senderId() != null ? msg.senderId().toString() : "";
            // B3: capture gps_tool:* broadcasts so gps_tool.last() can return them.
            if (msg.channel() == 9100 && msg.content() != null && msg.content().startsWith("gps_tool:")) {
                this.lastGpsToolBroadcast = msg.content().substring("gps_tool:".length());
            }
            pushEvent(new OSEvent(OSEvent.Type.BLUETOOTH, msg.channel(), msg.content(), senderId));
            msg = BluetoothNetwork.receive(computerId);
        }

        // Dispatch events to foreground program
        while (!eventQueue.isEmpty()) {
            OSEvent event = eventQueue.poll();
            if (event.getType() == OSEvent.Type.TERMINATE) {
                if (foregroundProgram != null) {
                    foregroundProgram.shutdown();
                }
                continue;
            }
            if (event.getType() == OSEvent.Type.SHUTDOWN) {
                shutdown();
                return;
            }
            if (event.getType() == OSEvent.Type.REBOOT) {
                reboot();
                return;
            }
            if (foregroundProgram != null && foregroundProgram.isRunning()) {
                foregroundProgram.handleEvent(event);
            }
        }

        // Tick all processes
        List<OSProgram> toRemove = new ArrayList<>();
        for (OSProgram proc : processes) {
            if (proc.isRunning()) {
                if (!proc.tick()) {
                    proc.shutdown();
                    toRemove.add(proc);
                }
            } else {
                toRemove.add(proc);
            }
        }
        processes.removeAll(toRemove);

        // If foreground program died, fall back to desktop
        if (foregroundProgram != null && !foregroundProgram.isRunning()) {
            foregroundProgram = null;
            if (processes.isEmpty()) {
                launchProgram(new DesktopProgram());
            } else {
                // Focus the last non-background process
                for (int i = processes.size() - 1; i >= 0; i--) {
                    if (!processes.get(i).isBackground()) {
                        foregroundProgram = processes.get(i);
                        break;
                    }
                }
                if (foregroundProgram == null) {
                    launchProgram(new DesktopProgram());
                }
            }
        }

        // Render foreground into pixel buffer
        if (foregroundProgram != null) {
            foregroundProgram.renderGraphics(pixelBuffer);
        }
    }

    // --- Process Management ---

    public void launchProgram(OSProgram program) {
        program.setOS(this);
        program.init(this);
        processes.add(program);
        if (!program.isBackground()) {
            foregroundProgram = program;
        }
    }

    public void killProgram(OSProgram program) {
        program.shutdown();
        processes.remove(program);
        if (foregroundProgram == program) {
            foregroundProgram = null;
        }
    }

    public void launchShell() {
        launchProgram(new ShellProgram());
    }

    public List<OSProgram> getProcesses() {
        return new ArrayList<>(processes);
    }

    public OSProgram getForegroundProgram() {
        return foregroundProgram;
    }

    public void setForegroundProgram(OSProgram program) {
        if (processes.contains(program)) {
            foregroundProgram = program;
        }
    }

    // --- Event System ---

    public void pushEvent(OSEvent event) {
        if (eventQueue.size() < MAX_EVENTS) {
            eventQueue.add(event);
        }
    }

    // --- Timer System ---

    public int startTimer(double seconds) {
        int id = nextTimerId++;
        long fireTick = tickCount + (long)(seconds * 20);
        timers.put(id, fireTick);
        return id;
    }

    public void cancelTimer(int timerId) {
        timers.remove(timerId);
    }

    /**
     * Schedule an alarm to fire when the world's day-time hour reaches the
     * given target (0..24). Mirrors CC:Tweaked's os.setAlarm. Fires an ALARM
     * event with the alarm id; one-shot (auto-removed on fire).
     */
    public int setAlarm(double targetHour) {
        // Normalize to 0..24, wrapping negatives.
        double hr = ((targetHour % 24.0) + 24.0) % 24.0;
        int id = nextAlarmId++;
        alarms.put(id, hr);
        return id;
    }

    public void cancelAlarm(int alarmId) {
        alarms.remove(alarmId);
    }

    // --- OS Commands ---

    public void shutdown() {
        state = State.SHUTDOWN;
        for (OSProgram proc : processes) {
            proc.shutdown();
        }
        processes.clear();
        foregroundProgram = null;
        terminal.setTextColor(0);
        terminal.setBackgroundColor(15);
        terminal.clear();
        terminal.setCursorPos(18, 9);
        terminal.write("Shutting down...");
    }

    public void reboot() {
        for (OSProgram proc : processes) {
            proc.shutdown();
        }
        processes.clear();
        foregroundProgram = null;
        eventQueue.clear();
        timers.clear();
        alarms.clear();
        nextTimerId = 1;
        nextAlarmId = 1;
        prevWorldHour = -1;
        tickCount = 0;
        bootTick = 0;
        rebooting = true;
        state = State.BOOT;
    }

    /**
     * Restart the OS immediately into RUNNING state, skipping the boot animation.
     * Use this when re-opening the terminal after the user closed it (ESC).
     * Use {@link #reboot()} only when the user explicitly chose "Reboot" from the menu.
     */
    public void restartSilent() {
        for (OSProgram proc : processes) {
            proc.shutdown();
        }
        processes.clear();
        foregroundProgram = null;
        eventQueue.clear();
        timers.clear();
        alarms.clear();
        nextTimerId = 1;
        nextAlarmId = 1;
        prevWorldHour = -1;
        tickCount = 0;
        bootTick = 0;
        rebooting = false;
        state = State.RUNNING;
        terminal.setTextColor(0);
        terminal.setBackgroundColor(15);
        terminal.clear();
        if (fileSystem.exists("/startup.lua") && fileSystem.isFile("/startup.lua")) {
            launchProgram(new com.apocscode.byteblock.computer.programs.LuaShellProgram("/startup.lua"));
        } else {
            launchProgram(new DesktopProgram());
        }
    }

    // --- Getters ---

    public TerminalBuffer getTerminal() { return terminal; }
    public PixelBuffer getPixelBuffer() { return pixelBuffer; }
    public VirtualFileSystem getFileSystem() { return fileSystem; }
    public UUID getComputerId() { return computerId; }
    /** Last gps_tool:* JSON broadcast received on channel 9100, or null. */
    public String getLastGpsToolBroadcast() { return lastGpsToolBroadcast; }    public State getState() { return state; }
    public long getTickCount() { return tickCount; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getBluetoothChannel() { return bluetoothChannel; }
    public void setBluetoothChannel(int ch) { this.bluetoothChannel = ch; }

    public net.minecraft.world.level.Level getLevel() { return level; }
    public net.minecraft.core.BlockPos getBlockPos() { return blockPos; }
    public void setWorldContext(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        this.level = level;
        this.blockPos = pos;
    }

    /**
     * Returns the most recently published map of {@link net.minecraft.core.BlockPos} to
     * {@link net.minecraft.world.level.block.entity.BlockEntity} within Bluetooth range
     * of this computer. Safe to read from any thread (volatile reference, immutable map).
     * Returns an empty map until the first call to {@link #refreshPeripheralSnapshot()}.
     */
    public java.util.Map<net.minecraft.core.BlockPos,
            net.minecraft.world.level.block.entity.BlockEntity> getPeripheralSnapshot() {
        return peripheralSnapshot;
    }

    public java.util.Map<net.minecraft.core.BlockPos, String> getPeripheralTypeCache() {
        return peripheralTypeCache;
    }

    public java.util.Map<net.minecraft.core.BlockPos, org.luaj.vm2.LuaTable> getPeripheralTableCache() {
        return peripheralTableCache;
    }

    public java.util.Map<net.minecraft.core.BlockPos,
            net.neoforged.neoforge.items.IItemHandler> getAdjacentItemHandlerCache() {
        return adjacentItemHandlerCache;
    }

    public java.util.Map<java.util.UUID, org.luaj.vm2.LuaTable> getEntityPeripheralTableCache() {
        return entityPeripheralTableCache;
    }

    /** Returns a durable value persisted in block entity NBT, or null when missing. */
    public String getPersistentValue(String key) {
        if (key == null || key.isBlank()) return null;
        return persistentData.get(key);
    }

    /** Stores a durable value persisted in block entity NBT. */
    public void setPersistentValue(String key, String value) {
        if (key == null || key.isBlank()) return;
        String prev = persistentData.put(key, value == null ? "" : value);
        String next = persistentData.get(key);
        if ((prev == null && next != null) || (prev != null && !prev.equals(next))) {
            persistentDataDirty = true;
        }
    }

    public void removePersistentValue(String key) {
        if (key == null || key.isBlank()) return;
        if (persistentData.remove(key) != null) {
            persistentDataDirty = true;
        }
    }

    public CompoundTag savePersistentData() {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, String> entry : persistentData.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            tag.putString(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return tag;
    }

    public void loadPersistentData(CompoundTag tag) {
        persistentData.clear();
        if (tag == null) {
            persistentDataDirty = false;
            return;
        }
        for (String key : tag.getAllKeys()) {
            persistentData.put(key, tag.getString(key));
        }
        persistentDataDirty = false;
    }

    public boolean isPersistentDataDirty() {
        return persistentDataDirty;
    }

    public void clearPersistentDataDirty() {
        persistentDataDirty = false;
    }

    /**
     * Walks the loaded chunks within Bluetooth range and publishes a snapshot of
     * every {@link BlockEntity} keyed by position. MUST be called on the server thread.
     * Throttled internally so it only rebuilds the snapshot at most once per 10 game
     * ticks (~0.5s) per computer.
     */
    public void refreshPeripheralSnapshot() {
        if (level == null || blockPos == null) return;
        if (level.isClientSide()) return;
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        long t = level.getGameTime();
        if (lastPeripheralSnapshotTick >= 0 && (t - lastPeripheralSnapshotTick) < 10) return;
        lastPeripheralSnapshotTick = t;

        int r = com.apocscode.byteblock.network.BluetoothNetwork.BLOCK_RANGE;
        int x0 = blockPos.getX() - r, x1 = blockPos.getX() + r;
        int y0 = Math.max(level.getMinBuildHeight(), blockPos.getY() - r);
        int y1 = Math.min(level.getMaxBuildHeight() - 1, blockPos.getY() + r);
        int z0 = blockPos.getZ() - r, z1 = blockPos.getZ() + r;
        int cx0 = x0 >> 4, cx1 = x1 >> 4;
        int cz0 = z0 >> 4, cz1 = z1 >> 4;
        net.minecraft.server.level.ServerChunkCache scc = sl.getChunkSource();
        java.util.Map<net.minecraft.core.BlockPos,
                net.minecraft.world.level.block.entity.BlockEntity> snap = new java.util.HashMap<>();
        int chunksLoaded = 0, chunksUnloaded = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = scc.getChunkNow(cx, cz);
                if (chunk == null) { chunksUnloaded++; continue; }
                chunksLoaded++;
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    net.minecraft.core.BlockPos bp = entry.getKey();
                    if (bp.getX() < x0 || bp.getX() > x1) continue;
                    if (bp.getY() < y0 || bp.getY() > y1) continue;
                    if (bp.getZ() < z0 || bp.getZ() > z1) continue;
                    net.minecraft.world.level.block.entity.BlockEntity be = entry.getValue();
                    if (be != null) snap.put(bp, be);
                }
            }
        }
        peripheralSnapshot = java.util.Collections.unmodifiableMap(snap);

        // Pre-resolve adapters and build Lua tables on the server thread.
        // This is the critical step: level.getCapability() must only be called
        // here (server thread), never from the Lua coroutine thread.
        java.util.Map<net.minecraft.core.BlockPos, String> typeSnap = new java.util.HashMap<>();
        java.util.Map<net.minecraft.core.BlockPos, org.luaj.vm2.LuaTable> tableSnap = new java.util.HashMap<>();
        // Pre-compute which positions are directly adjacent (for __side injection).
        java.util.Map<net.minecraft.core.BlockPos, String> adjSideMap = new java.util.HashMap<>();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            adjSideMap.put(blockPos.relative(dir),
                com.apocscode.byteblock.computer.peripheral.PeripheralRegistry.directionToSide(dir));
        }
        for (var entry : snap.entrySet()) {
            net.minecraft.core.BlockPos bp = entry.getKey();
            net.minecraft.world.level.block.entity.BlockEntity be = entry.getValue();
            for (com.apocscode.byteblock.computer.peripheral.IPeripheralAdapter adapter
                    : com.apocscode.byteblock.computer.peripheral.PeripheralRegistry.getAdapters()) {
                try {
                    if (adapter.canAdapt(be)) {
                        String type = adapter.getType(be);
                        typeSnap.put(bp, type);
                        org.luaj.vm2.LuaTable tbl = adapter.buildTable(be, this);
                        // Inject __side so peripheral.getName(handle) works (CC-compat liveness check).
                        String side = adjSideMap.get(bp);
                        if (side != null) {
                            tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__side"),
                                       org.luaj.vm2.LuaValue.valueOf(side));
                        }
                        injectPeripheralMetadata(tbl, bp, adapter, be, side, t);
                        tableSnap.put(bp, tbl);
                        break;
                    }
                } catch (Exception e) {
                    javaLog("adapter " + adapter.getClass().getSimpleName()
                            + " threw on " + be.getClass().getSimpleName()
                            + " at " + bp + ": " + e);
                }
            }
        }
        peripheralTypeCache  = java.util.Collections.unmodifiableMap(typeSnap);
        peripheralTableCache = java.util.Collections.unmodifiableMap(tableSnap);
        // Cache IItemHandler for all 6 directly adjacent positions so pushItems/pullItems
        // closures can resolve destination handlers without calling level.getCapability()
        // from the Lua coroutine thread (which deadlocks via CompletableFuture.join).
        java.util.Map<net.minecraft.core.BlockPos,
                net.neoforged.neoforge.items.IItemHandler> adjHandlerSnap = new java.util.HashMap<>();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos adjPos = blockPos.relative(dir);
            net.neoforged.neoforge.items.IItemHandler ih = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, adjPos, null);
            if (ih != null) adjHandlerSnap.put(adjPos, ih);
        }
        adjacentItemHandlerCache = java.util.Collections.unmodifiableMap(adjHandlerSnap);

        // Entity peripheral snapshot — robots and drones in Bluetooth range.
        // Runs on server thread, so safe to call getEntitiesOfClass().
        java.util.Map<java.util.UUID, org.luaj.vm2.LuaTable> entitySnap = new java.util.HashMap<>();
        net.minecraft.world.phys.AABB entityBox = new net.minecraft.world.phys.AABB(
                blockPos.getX() - r, blockPos.getY() - r, blockPos.getZ() - r,
                blockPos.getX() + r, blockPos.getY() + r, blockPos.getZ() + r);
        for (com.apocscode.byteblock.entity.RobotEntity robot
                : sl.getEntitiesOfClass(com.apocscode.byteblock.entity.RobotEntity.class, entityBox)) {
            try { entitySnap.put(robot.getComputerId(), buildRobotPeripheralTable(robot, t)); }
            catch (Exception e) { javaLog("robot peripheral threw: " + e); }
        }
        for (com.apocscode.byteblock.entity.DroneEntity drone
                : sl.getEntitiesOfClass(com.apocscode.byteblock.entity.DroneEntity.class, entityBox)) {
            try { entitySnap.put(drone.getDroneId(), buildDronePeripheralTable(drone, t)); }
            catch (Exception e) { javaLog("drone peripheral threw: " + e); }
        }
        entityPeripheralTableCache = java.util.Collections.unmodifiableMap(entitySnap);

        // ── Peripheral online/offline event detection ─────────────────────
        // Build a flat set of all current peripheral names (block + entity).
        java.util.Set<String> currNames = new java.util.HashSet<>();
        for (org.luaj.vm2.LuaTable tbl : tableSnap.values()) {
            org.luaj.vm2.LuaValue nameVal = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__name"));
            if (!nameVal.isnil()) currNames.add(nameVal.tojstring());
        }
        for (org.luaj.vm2.LuaTable tbl : entitySnap.values()) {
            org.luaj.vm2.LuaValue nameVal = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__name"));
            if (!nameVal.isnil()) currNames.add(nameVal.tojstring());
        }
        // Fire peripheral_online for newly appeared devices.
        for (String pName : currNames) {
            if (!prevPeripheralNames.contains(pName)) {
                // Find type from block or entity cache
                String pType = "peripheral";
                for (org.luaj.vm2.LuaTable tbl : tableSnap.values()) {
                    org.luaj.vm2.LuaValue nv = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__name"));
                    if (!nv.isnil() && pName.equals(nv.tojstring())) {
                        org.luaj.vm2.LuaValue tv = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__type"));
                        if (!tv.isnil()) pType = tv.tojstring();
                        break;
                    }
                }
                for (org.luaj.vm2.LuaTable tbl : entitySnap.values()) {
                    org.luaj.vm2.LuaValue nv = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__name"));
                    if (!nv.isnil() && pName.equals(nv.tojstring())) {
                        org.luaj.vm2.LuaValue tv = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__type"));
                        if (!tv.isnil()) pType = tv.tojstring();
                        break;
                    }
                }
                pushEvent(new OSEvent(OSEvent.Type.PERIPHERAL_ONLINE, pName, pType));
            }
        }
        // Fire peripheral_offline for devices that disappeared.
        for (String pName : prevPeripheralNames) {
            if (!currNames.contains(pName)) {
                pushEvent(new OSEvent(OSEvent.Type.PERIPHERAL_OFFLINE, pName, "peripheral"));
            }
        }
        prevPeripheralNames = currNames;

        // ── Peripheral alert events (low fuel/energy) ─────────────────────
        for (org.luaj.vm2.LuaTable tbl : entitySnap.values()) {
            try {
                org.luaj.vm2.LuaValue nameV = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__name"));
                org.luaj.vm2.LuaValue typeV = tbl.rawget(org.luaj.vm2.LuaValue.valueOf("__type"));
                if (nameV.isnil()) continue;
                String pName = nameV.tojstring();
                String pType = typeV.isnil() ? "" : typeV.tojstring();
                if ("drone".equals(pType)) {
                    // Low fuel alert when < 10%
                    org.luaj.vm2.LuaValue fuelFn = tbl.get("getFuel");
                    org.luaj.vm2.LuaValue capFn  = tbl.get("getFuelCapacity");
                    if (fuelFn.isfunction() && capFn.isfunction()) {
                        int fuel = fuelFn.call().toint();
                        int cap  = capFn.call().toint();
                        if (cap > 0 && (double) fuel / cap < 0.10) {
                            pushEvent(new OSEvent(OSEvent.Type.PERIPHERAL_ALERT,
                                    pName, "low_fuel", fuel));
                        }
                    }
                } else if ("robot".equals(pType)) {
                    // Low energy alert when < 10%
                    org.luaj.vm2.LuaValue engFn = tbl.get("getEnergy");
                    org.luaj.vm2.LuaValue capFn  = tbl.get("getEnergyCapacity");
                    if (engFn.isfunction() && capFn.isfunction()) {
                        int eng = engFn.call().toint();
                        int cap = capFn.call().toint();
                        if (cap > 0 && (double) eng / cap < 0.10) {
                            pushEvent(new OSEvent(OSEvent.Type.PERIPHERAL_ALERT,
                                    pName, "low_energy", eng));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Log summary every snapshot so we can correlate with Lua failures.
        // Throttled to once per ~5s (every 100 ticks) to avoid log spam.
        if ((t % 100) == 0) {
            StringBuilder typeList = new StringBuilder();
            int extra = 0;
            int shown = 0;
            for (var be : snap.values()) {
                String cn = be.getClass().getSimpleName();
                if (shown < 12) {
                    if (typeList.length() > 0) typeList.append(',');
                    typeList.append(cn);
                    shown++;
                } else { extra++; }
            }
            javaLog(String.format(
                "snapshot refresh: tick=%d computer=(%d,%d,%d) range=%d chunks=%d/%d snap.size=%d entity=%d types=[%s%s]",
                t, blockPos.getX(), blockPos.getY(), blockPos.getZ(), r,
                chunksLoaded, (chunksLoaded + chunksUnloaded), snap.size(), entitySnap.size(),
                typeList.toString(), extra > 0 ? (",+" + extra + " more") : ""));
        }
    }

    private void injectPeripheralMetadata(
            org.luaj.vm2.LuaTable tbl,
            net.minecraft.core.BlockPos pos,
            com.apocscode.byteblock.computer.peripheral.IPeripheralAdapter adapter,
            net.minecraft.world.level.block.entity.BlockEntity be,
            String side,
            long gameTime) {
        String type = adapter.getType(be);
        java.util.List<String> types = adapter.getTypes(be);
        java.util.List<String> capabilities = adapter.getCapabilities(be);
        String label = adapter.getLabel(be);
        String stableId = adapter.getStableId(be);
        boolean wireless = side == null;
        String name;
        if (side != null) {
            name = side;
        } else if (stableId != null && stableId.length() >= 8) {
            name = type + "_" + stableId.substring(0, 8);
        } else {
            name = type + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }

        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__name"), org.luaj.vm2.LuaValue.valueOf(name));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__type"), org.luaj.vm2.LuaValue.valueOf(type));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__wireless"), org.luaj.vm2.LuaValue.valueOf(wireless));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__x"), org.luaj.vm2.LuaValue.valueOf(pos.getX()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__y"), org.luaj.vm2.LuaValue.valueOf(pos.getY()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__z"), org.luaj.vm2.LuaValue.valueOf(pos.getZ()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__lastSeen"), org.luaj.vm2.LuaValue.valueOf((int) gameTime));
        if (level != null) {
            tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__dimension"),
                    org.luaj.vm2.LuaValue.valueOf(level.dimension().location().toString()));
        }
        if (label != null && !label.isBlank()) {
            tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__label"), org.luaj.vm2.LuaValue.valueOf(label));
        }
        if (stableId != null && !stableId.isBlank()) {
            tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__id"), org.luaj.vm2.LuaValue.valueOf(stableId));
        }

        org.luaj.vm2.LuaTable typeTable = new org.luaj.vm2.LuaTable();
        int i = 1;
        for (String entry : types) {
            if (entry == null || entry.isBlank()) continue;
            typeTable.set(i++, org.luaj.vm2.LuaValue.valueOf(entry));
        }
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__types"), typeTable);

        org.luaj.vm2.LuaTable capTable = new org.luaj.vm2.LuaTable();
        i = 1;
        for (String entry : capabilities) {
            if (entry == null || entry.isBlank()) continue;
            capTable.set(i++, org.luaj.vm2.LuaValue.valueOf(entry));
        }
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__capabilities"), capTable);
    }

    // ── Entity peripheral table builders ─────────────────────────────────

    private org.luaj.vm2.LuaTable buildRobotPeripheralTable(
            com.apocscode.byteblock.entity.RobotEntity robot, long gameTime) {
        org.luaj.vm2.LuaTable tbl = new org.luaj.vm2.LuaTable();
        net.minecraft.core.BlockPos rpos = robot.blockPosition();
        java.util.UUID id = robot.getComputerId();
        String idStr = id.toString();
        String name = "robot_" + idStr.substring(0, 8);
        String dim = level != null ? level.dimension().location().toString() : "unknown";

        // Metadata
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__name"),       org.luaj.vm2.LuaValue.valueOf(name));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__type"),       org.luaj.vm2.LuaValue.valueOf("robot"));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__id"),         org.luaj.vm2.LuaValue.valueOf(idStr));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__x"),          org.luaj.vm2.LuaValue.valueOf(rpos.getX()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__y"),          org.luaj.vm2.LuaValue.valueOf(rpos.getY()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__z"),          org.luaj.vm2.LuaValue.valueOf(rpos.getZ()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__wireless"),   org.luaj.vm2.LuaValue.TRUE);
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__lastSeen"),   org.luaj.vm2.LuaValue.valueOf((int) gameTime));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__dimension"),  org.luaj.vm2.LuaValue.valueOf(dim));

        // Snapshot state values
        int energy    = robot.getEnergyStorage() != null ? robot.getEnergyStorage().getEnergyStored() : 0;
        int maxEnergy = robot.getEnergyStorage() != null ? robot.getEnergyStorage().getMaxEnergyStored() : 0;
        float shield  = robot.getShieldHP();
        int invSize   = robot.getInventory().getContainerSize();
        int channel   = robot.getBluetoothChannel();

        // Upgrade names
        org.luaj.vm2.LuaTable upTbl = new org.luaj.vm2.LuaTable();
        net.minecraft.world.SimpleContainer upSlots = robot.getUpgradeSlots();
        for (int u = 0; u < upSlots.getContainerSize(); u++) {
            net.minecraft.world.item.ItemStack stack = upSlots.getItem(u);
            if (!stack.isEmpty()) {
                upTbl.set(u + 1, org.luaj.vm2.LuaValue.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).getPath()));
            }
        }

        // Types and capabilities
        org.luaj.vm2.LuaTable typesTbl = new org.luaj.vm2.LuaTable();
        typesTbl.set(1, org.luaj.vm2.LuaValue.valueOf("robot"));
        typesTbl.set(2, org.luaj.vm2.LuaValue.valueOf("mobile"));
        typesTbl.set(3, org.luaj.vm2.LuaValue.valueOf("computer"));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__types"), typesTbl);
        org.luaj.vm2.LuaTable capsTbl = new org.luaj.vm2.LuaTable();
        capsTbl.set(1, org.luaj.vm2.LuaValue.valueOf("energy"));
        capsTbl.set(2, org.luaj.vm2.LuaValue.valueOf("inventory"));
        capsTbl.set(3, org.luaj.vm2.LuaValue.valueOf("wireless"));
        capsTbl.set(4, org.luaj.vm2.LuaValue.valueOf("movement"));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__capabilities"), capsTbl);

        // State read methods (return snapshotted values — safe from any thread)
        tbl.set("getEnergy",         new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(energy); } });
        tbl.set("getEnergyCapacity", new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(maxEnergy); } });
        tbl.set("getShield",         new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf((double) shield); } });
        tbl.set("getShieldMax",      new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(8.0); } });
        tbl.set("getChannel",        new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(channel); } });
        tbl.set("getUpgrades",       new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return upTbl; } });
        tbl.set("getInventorySize",  new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(invSize); } });
        tbl.set("getFacing",         new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(robot.getRobotFacing().getName()); } });
        tbl.set("isCharging",        new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(robot.isCharging()); } });
        tbl.set("position", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                org.luaj.vm2.LuaTable p = new org.luaj.vm2.LuaTable();
                p.set("x", org.luaj.vm2.LuaValue.valueOf(rpos.getX()));
                p.set("y", org.luaj.vm2.LuaValue.valueOf(rpos.getY()));
                p.set("z", org.luaj.vm2.LuaValue.valueOf(rpos.getZ()));
                return p;
            }
        });
        tbl.set("hasUpgrade", new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue arg) {
                String want = arg.tojstring().toLowerCase();
                net.minecraft.world.SimpleContainer us = robot.getUpgradeSlots();
                for (int u = 0; u < us.getContainerSize(); u++) {
                    net.minecraft.world.item.ItemStack s = us.getItem(u);
                    if (!s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(s.getItem()).getPath().toLowerCase().contains(want)) {
                        return org.luaj.vm2.LuaValue.TRUE;
                    }
                }
                return org.luaj.vm2.LuaValue.FALSE;
            }
        });
        tbl.set("getInventory", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                org.luaj.vm2.LuaTable inv = new org.luaj.vm2.LuaTable();
                net.minecraft.world.SimpleContainer c = robot.getInventory();
                for (int s = 0; s < c.getContainerSize(); s++) {
                    net.minecraft.world.item.ItemStack stack = c.getItem(s);
                    if (!stack.isEmpty()) {
                        org.luaj.vm2.LuaTable entry = new org.luaj.vm2.LuaTable();
                        entry.set("slot", org.luaj.vm2.LuaValue.valueOf(s + 1));
                        entry.set("name", org.luaj.vm2.LuaValue.valueOf(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(stack.getItem()).toString()));
                        entry.set("count", org.luaj.vm2.LuaValue.valueOf(stack.getCount()));
                        inv.set(s + 1, entry);
                    }
                }
                return inv;
            }
        });

        // Action methods — send BT commands to the robot (thread-safe via BT)
        int btChannel = channel;
        java.util.UUID robotId = id;
        java.net.InetAddress unused = null; // just to have level captured via closure
        net.minecraft.world.level.Level lvl = level;
        net.minecraft.core.BlockPos myPos = blockPos;

        tbl.set("moveTo", new org.luaj.vm2.lib.VarArgFunction() {
            @Override public org.luaj.vm2.Varargs invoke(org.luaj.vm2.Varargs args) {
                int x = args.checkint(1), y = args.checkint(2), z = args.checkint(3);
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, robotId, btChannel, "robot:cmd:goto:" + x + ":" + y + ":" + z);
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("stop", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, robotId, btChannel, "robot:cmd:stop");
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("returnHome", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, robotId, btChannel, "robot:cmd:clearNav");
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("queueCmd", new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue arg) {
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, robotId, btChannel, "robot:cmd:" + arg.tojstring());
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("patrol", new org.luaj.vm2.lib.VarArgFunction() {
            @Override public org.luaj.vm2.Varargs invoke(org.luaj.vm2.Varargs args) {
                int x1 = args.checkint(1), y1 = args.checkint(2), z1 = args.checkint(3);
                int x2 = args.checkint(4), y2 = args.checkint(5), z2 = args.checkint(6);
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, robotId, btChannel,
                    "robot:cmd:patrol:" + x1 + ":" + y1 + ":" + z1 + ":" + x2 + ":" + y2 + ":" + z2);
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });

        return tbl;
    }

    private org.luaj.vm2.LuaTable buildDronePeripheralTable(
            com.apocscode.byteblock.entity.DroneEntity drone, long gameTime) {
        org.luaj.vm2.LuaTable tbl = new org.luaj.vm2.LuaTable();
        net.minecraft.core.BlockPos dpos = drone.blockPosition();
        java.util.UUID id = drone.getDroneId();
        String idStr = id.toString();
        String name = "drone_" + idStr.substring(0, 8);
        String dim = level != null ? level.dimension().location().toString() : "unknown";

        // Metadata
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__name"),       org.luaj.vm2.LuaValue.valueOf(name));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__type"),       org.luaj.vm2.LuaValue.valueOf("drone"));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__id"),         org.luaj.vm2.LuaValue.valueOf(idStr));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__x"),          org.luaj.vm2.LuaValue.valueOf(dpos.getX()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__y"),          org.luaj.vm2.LuaValue.valueOf(dpos.getY()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__z"),          org.luaj.vm2.LuaValue.valueOf(dpos.getZ()));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__wireless"),   org.luaj.vm2.LuaValue.TRUE);
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__lastSeen"),   org.luaj.vm2.LuaValue.valueOf((int) gameTime));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__dimension"),  org.luaj.vm2.LuaValue.valueOf(dim));

        // Snapshot state values
        int fuel       = drone.getFuel();
        float shield   = drone.getShieldHP();
        int invSize    = drone.getInventory().getContainerSize();
        int channel    = drone.getBluetoothChannel();
        int waypointCt = drone.getWaypointCount();
        boolean hovering = drone.isHovering();
        boolean defender = drone.isDefender();
        int laserTarget  = drone.getLaserTargetId();
        String variant   = drone.getVariant().name().toLowerCase();

        // Upgrade names
        org.luaj.vm2.LuaTable upTbl = new org.luaj.vm2.LuaTable();
        net.minecraft.world.SimpleContainer upSlots = drone.getUpgradeSlots();
        for (int u = 0; u < upSlots.getContainerSize(); u++) {
            net.minecraft.world.item.ItemStack stack = upSlots.getItem(u);
            if (!stack.isEmpty()) {
                upTbl.set(u + 1, org.luaj.vm2.LuaValue.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).getPath()));
            }
        }

        // Types and capabilities
        org.luaj.vm2.LuaTable typesTbl = new org.luaj.vm2.LuaTable();
        typesTbl.set(1, org.luaj.vm2.LuaValue.valueOf("drone"));
        typesTbl.set(2, org.luaj.vm2.LuaValue.valueOf("mobile"));
        typesTbl.set(3, org.luaj.vm2.LuaValue.valueOf("computer"));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__types"), typesTbl);
        org.luaj.vm2.LuaTable capsTbl = new org.luaj.vm2.LuaTable();
        capsTbl.set(1, org.luaj.vm2.LuaValue.valueOf("fuel"));
        capsTbl.set(2, org.luaj.vm2.LuaValue.valueOf("inventory"));
        capsTbl.set(3, org.luaj.vm2.LuaValue.valueOf("wireless"));
        capsTbl.set(4, org.luaj.vm2.LuaValue.valueOf("flight"));
        tbl.rawset(org.luaj.vm2.LuaValue.valueOf("__capabilities"), capsTbl);

        // State read methods
        tbl.set("getFuel",           new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(fuel); } });
        tbl.set("getFuelCapacity",   new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(com.apocscode.byteblock.entity.DroneEntity.MAX_FUEL); } });
        tbl.set("getShield",         new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf((double) shield); } });
        tbl.set("getShieldMax",      new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(8.0); } });
        tbl.set("getChannel",        new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(channel); } });
        tbl.set("getWaypointCount",  new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(waypointCt); } });
        tbl.set("isHovering",        new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(hovering); } });
        tbl.set("isDefender",        new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(defender); } });
        tbl.set("getLaserTarget",    new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return laserTarget < 0 ? org.luaj.vm2.LuaValue.NIL : org.luaj.vm2.LuaValue.valueOf(laserTarget); } });
        tbl.set("getVariant",        new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(variant); } });
        tbl.set("getInventorySize",  new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return org.luaj.vm2.LuaValue.valueOf(invSize); } });
        tbl.set("getUpgrades",       new org.luaj.vm2.lib.ZeroArgFunction() { @Override public org.luaj.vm2.LuaValue call() { return upTbl; } });
        tbl.set("position", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                org.luaj.vm2.LuaTable p = new org.luaj.vm2.LuaTable();
                p.set("x", org.luaj.vm2.LuaValue.valueOf(dpos.getX()));
                p.set("y", org.luaj.vm2.LuaValue.valueOf(dpos.getY()));
                p.set("z", org.luaj.vm2.LuaValue.valueOf(dpos.getZ()));
                return p;
            }
        });
        tbl.set("hasUpgrade", new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue arg) {
                String want = arg.tojstring().toLowerCase();
                net.minecraft.world.SimpleContainer us = drone.getUpgradeSlots();
                for (int u = 0; u < us.getContainerSize(); u++) {
                    net.minecraft.world.item.ItemStack s = us.getItem(u);
                    if (!s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(s.getItem()).getPath().toLowerCase().contains(want)) {
                        return org.luaj.vm2.LuaValue.TRUE;
                    }
                }
                return org.luaj.vm2.LuaValue.FALSE;
            }
        });
        tbl.set("getInventory", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                org.luaj.vm2.LuaTable inv = new org.luaj.vm2.LuaTable();
                net.minecraft.world.SimpleContainer c = drone.getInventory();
                for (int s = 0; s < c.getContainerSize(); s++) {
                    net.minecraft.world.item.ItemStack stack = c.getItem(s);
                    if (!stack.isEmpty()) {
                        org.luaj.vm2.LuaTable entry = new org.luaj.vm2.LuaTable();
                        entry.set("slot", org.luaj.vm2.LuaValue.valueOf(s + 1));
                        entry.set("name", org.luaj.vm2.LuaValue.valueOf(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(stack.getItem()).toString()));
                        entry.set("count", org.luaj.vm2.LuaValue.valueOf(stack.getCount()));
                        inv.set(s + 1, entry);
                    }
                }
                return inv;
            }
        });

        // Action methods — send BT commands to the drone
        int btChannel = channel;
        java.util.UUID droneId = id;
        net.minecraft.world.level.Level lvl = level;
        net.minecraft.core.BlockPos myPos = blockPos;

        tbl.set("addWaypoint", new org.luaj.vm2.lib.VarArgFunction() {
            @Override public org.luaj.vm2.Varargs invoke(org.luaj.vm2.Varargs args) {
                double x = args.checkdouble(1), y = args.checkdouble(2), z = args.checkdouble(3);
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, droneId, btChannel,
                    "drone:waypoint:" + (int)x + ":" + (int)y + ":" + (int)z);
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("clearWaypoints", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, droneId, btChannel, "drone:clear");
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("returnHome", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() {
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, droneId, btChannel, "drone:home");
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("hover", new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue arg) {
                String val = arg.isboolean() ? (arg.toboolean() ? "true" : "false") : arg.tojstring();
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, droneId, btChannel, "drone:hover:" + val);
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("setDefender", new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue arg) {
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, droneId, btChannel,
                    "drone:defender:" + (arg.toboolean() ? "true" : "false"));
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });
        tbl.set("scan", new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue arg) {
                int r2 = arg.isnil() ? 8 : arg.checkint();
                com.apocscode.byteblock.network.BluetoothNetwork.send(
                    lvl, myPos, null, droneId, btChannel, "drone:scan:" + r2);
                return org.luaj.vm2.LuaValue.TRUE;
            }
        });

        return tbl;
    }

    /**
     * Append a line to {@code <computerDir>/java_debug.log}. Used for diagnosing
     * peripheral discovery and other server-side issues from outside Lua.
     * Thread-safe and best-effort: failures are silently swallowed.
     */
    public void javaLog(String msg) {
        if (level == null) return;
        try {
            java.nio.file.Path dir = com.apocscode.byteblock.computer.VfsDiskMirror
                    .computerDir(level, computerId);
            if (dir == null) return;
            java.nio.file.Path file = dir.resolve("java_debug.log");
            // 200KB cap — wipe and start over if too big.
            try {
                if (java.nio.file.Files.exists(file) && java.nio.file.Files.size(file) > 200_000) {
                    java.nio.file.Files.delete(file);
                }
            } catch (Exception ignored) {}
            String line = "[" + java.time.LocalTime.now().withNano(0) + "] " + msg + System.lineSeparator();
            java.nio.file.Files.writeString(file, line,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    public net.minecraft.world.entity.Entity getHost() { return host; }
    public void setHost(net.minecraft.world.entity.Entity host) { this.host = host; }

    // --- Drive Mount System ---

    private void scanDrives() {
        mountedDrives.clear();
        if (level == null || blockPos == null) return;
        char letter = 'D';
        Set<net.minecraft.core.BlockPos> seen = new HashSet<>();

        // Adjacent drives (directly touching the computer)
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(blockPos.relative(dir));
            if (be instanceof DriveBlockEntity drive && drive.hasDisk()) {
                mountedDrives.put(letter++, drive);
                seen.add(be.getBlockPos());
                if (letter > 'Z') return;
            }
        }

        // Bluetooth-range drives
        List<BluetoothNetwork.DeviceEntry> devices =
            BluetoothNetwork.getDevicesInRange(level, blockPos, BluetoothNetwork.BLOCK_RANGE);
        for (BluetoothNetwork.DeviceEntry d : devices) {
            if (d.type() == BluetoothNetwork.DeviceType.DRIVE && !seen.contains(d.pos())) {
                BlockEntity be = level.getBlockEntity(d.pos());
                if (be instanceof DriveBlockEntity drive && drive.hasDisk()) {
                    mountedDrives.put(letter++, drive);
                    seen.add(d.pos());
                    if (letter > 'Z') return;
                }
            }
        }
    }

    public Map<Character, DriveBlockEntity> getMountedDrives() {
        return Collections.unmodifiableMap(mountedDrives);
    }

    public DriveBlockEntity getDrive(char letter) {
        return mountedDrives.get(letter);
    }

    public float getTextScale() { return textScale; }
    public void setTextScale(float s) { this.textScale = Math.max(1.0f, Math.min(3.0f, s)); }

    public boolean isRunning() { return state == State.RUNNING; }
    public boolean isBooting() { return state == State.BOOT; }
    public boolean isRebooting() { return rebooting; }
    public int getBootSecondsRemaining() {
        int ticksLeft = Math.max(0, BOOT_DURATION - bootTick);
        return (ticksLeft + 19) / 20;
    }
    public boolean isShutdown() { return state == State.SHUTDOWN; }

    /**
     * True when a foreground user program (i.e. not the shell/desktop) is actively running.
     * Used by entity renderers to recolor the robot's eyes green while a script executes.
     */
    public boolean isProgramRunning() {
        return state == State.RUNNING
                && foregroundProgram != null
                && foregroundProgram.isRunning();
    }

    // Clipboard (program → system)
    private String clipboard;
    private String clipboardOut;
    public void setClipboard(String text) { this.clipboard = text; this.clipboardOut = text; }
    public String getClipboard() { return clipboard; }
    public String consumeClipboard() { String t = clipboardOut; clipboardOut = null; return t; }
}
