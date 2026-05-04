# Universal Peripheral Spec

This document defines the planned ByteBlock universal peripheral layer.

It is intended to unify discovery, naming, telemetry, and command dispatch across ByteBlock devices while remaining familiar to CC:Tweaked users and safe in large modpacks such as ATM10.

## Goals

- Provide one stable Lua-facing access pattern for monitors, robots, drones, charging stations, printers, scanners, drives, relays, and future devices.
- Reuse the existing Bluetooth device registry instead of creating a parallel discovery system.
- Preserve CC-style ergonomics where useful: `peripheral.find`, `wrap`, labels, method enumeration, terminal-like monitor APIs, and event-driven scripting.
- Add ByteBlock-native capabilities that CC does not provide by default: cross-dimensional mobile devices, capability introspection, async tasks, structured telemetry, and mod integration.
- Keep the server authoritative. All world mutation and long-running work must execute on the server thread or via queued tasks.

## Existing Anchors In This Codebase

- `BluetoothNetwork` already registers device identity, position, channel, type, range, and supports direct/broadcast messaging.
- `MonitorBlockEntity` already exposes a Lua-oriented API surface and naming model (`getLabel`, `setLabel`, text operations, touch events).
- `LuaShellProgram` already forwards CC-style events including `monitor_touch`, `rednet_message`, `redstone`, `timer`, and `task_complete`.
- `BluetoothNetwork.DeviceType` already includes a generic `PERIPHERAL` type and device-specific types such as `MONITOR`, `ROBOT`, `DRONE`, and `CHARGING_STATION`.

The universal peripheral should sit on top of those surfaces rather than replacing them.

## Core Architecture

### 1. Registry Layer

Add a registry/service responsible for building Lua-facing peripheral wrappers from registered devices.

Suggested types:

- `UniversalPeripheralService`
- `UniversalPeripheralHandle`
- `PeripheralCapability`
- `PeripheralSnapshot`
- `PeripheralCommandResult`

Responsibilities:

- Discover devices from `BluetoothNetwork`
- Resolve label, ID, type, capabilities, dimension, and last-seen state
- Build a stable Lua-facing wrapper object for a target device
- Provide permission/range checks before executing commands
- Normalize return data into Lua-safe primitives/tables

### 2. Device Adapter Layer

Each device type should expose a small adapter instead of dumping block-entity or entity internals directly into Lua.

Suggested interface:

```java
public interface ByteBlockPeripheralAdapter {
    String primaryType();
    java.util.Set<String> types();
    java.util.Set<String> capabilities();
    java.util.Map<String, Object> getSnapshot();
    java.util.List<String> getMethodNames();
    Object[] call(String method, Object[] args);
}
```

Suggested implementations:

- `MonitorPeripheralAdapter`
- `DronePeripheralAdapter`
- `RobotPeripheralAdapter`
- `ChargingStationPeripheralAdapter`
- `ScannerPeripheralAdapter`
- `PrinterPeripheralAdapter`
- `DrivePeripheralAdapter`
- `RelayPeripheralAdapter`
- `ByteChestPeripheralAdapter`

### 3. Async Task Layer

Any action that can pathfind, scan, move, print, mine, harvest, wait for inventory, or interact with other machines should return a task handle instead of blocking the Lua coroutine.

Suggested task fields:

- `id`
- `state` = `queued | running | success | failed | cancelled`
- `deviceId`
- `kind`
- `submittedAt`
- `startedAt`
- `finishedAt`
- `message`
- `result`

Related Lua events:

- `task_complete`
- `task_failed`
- `device_online`
- `device_offline`
- `device_alert`
- `inventory_changed`
- `energy_changed`
- `monitor_touch`

## Lua API Shape

The Lua layer should be split into two levels:

- global discovery API
- per-device wrapper API

### Global Discovery API

This should be exposed as a library or root peripheral helper.

Suggested functions:

- `peripheral.list()`
- `peripheral.find(typeName, filterFn)`
- `peripheral.wrap(nameOrId)`
- `peripheral.getName(id)`
- `peripheral.getType(nameOrId)`
- `peripheral.getMethods(nameOrId)`
- `peripheral.hasType(nameOrId, typeName)`

ByteBlock extensions:

- `peripheral.findByLabel(label)`
- `peripheral.findById(id)`
- `peripheral.findByCapability(capability)`
- `peripheral.scan(range)`
- `peripheral.attach(target)`
- `peripheral.detach(target)`

### Universal Base Methods

Every device wrapper should support these methods.

- `id()`
- `name()`
- `label()`
- `setLabel(name)`
- `type()`
- `types()`
- `capabilities()`
- `methods()`
- `status()`
- `position()`
- `dimension()`
- `distanceTo(target)`
- `isOnline()`
- `lastSeen()`
- `help()`
- `ping()`

### Suggested Return Shape For `status()`

```lua
{
  id = "uuid",
  name = "drone_3",
  label = "hauler-east",
  type = "drone",
  types = { "peripheral", "drone", "inventory", "mobile", "energy" },
  capabilities = { "inventory", "waypoints", "gps", "shield", "solar" },
  online = true,
  loaded = true,
  dimension = "minecraft:overworld",
  x = 128,
  y = 72,
  z = -48,
  lastSeen = 123456,
  alerts = {}
}
```

## Device-Specific Methods

### Monitor

Keep the existing CC-like terminal model and extend it carefully.

- `getSize()`
- `getMode()`
- `setMode(mode)`
- `clear()`
- `clearLine()`
- `write(text)`
- `blit(text, fg, bg)`
- `setCursorPos(x, y)`
- `getCursorPos()`
- `setTextColor(color)`
- `setBackgroundColor(color)`
- `setTextScale(scale)`
- `setPaletteColor(index, argb)`
- `getPaletteColor(index)`
- `getTouch()`
- `setFrameColor(argb)`
- `getFrameColor()`
- `setGeometry(thickness, tilt, yaw)`
- `flush()`

### Drone

Prefer high-value command primitives, not low-level physics control.

- `getFuel()`
- `getFuelCapacity()`
- `getShield()`
- `getShieldMax()`
- `getUpgrades()`
- `hasUpgrade(name)`
- `getInventory()`
- `getInventorySize()`
- `getHome()`
- `setHome(x, y, z)`
- `getWaypoints()`
- `addWaypoint(x, y, z)`
- `clearWaypoints()`
- `returnHome()`
- `stop()`
- `getLaserTarget()`
- `scan(range)`
- `deliver(target, slot, count)`
- `pickup(target, slot, count)`
- `follow(entityId)`
- `refuel(amount)`

### Robot

- `getEnergy()`
- `getEnergyCapacity()`
- `getShield()`
- `getShieldMax()`
- `getUpgrades()`
- `hasUpgrade(name)`
- `getInventory()`
- `getInventorySize()`
- `getHome()`
- `setHome(x, y, z)`
- `moveTo(x, y, z)`
- `returnHome()`
- `stop()`
- `mine(x, y, z)`
- `harvest(area)`
- `collect(area)`
- `deposit(target)`
- `equip(slot)`
- `useOn(x, y, z, side)`

### Charging Station

- `getEnergy()`
- `getEnergyCapacity()`
- `getChargeRate()`
- `getRange()`
- `getNearbyDevices()`
- `isCharging(id)`
- `reservePower(id, amount)`

### Scanner

- `getRadius()`
- `setRadius(radius)`
- `scan(radius)`
- `getBlocks(filter)`
- `getEntities(filter)`
- `findBlocks(name, radius)`
- `findEntities(type, radius)`

### Printer

- `queuePrint(title, content)`
- `getQueue()`
- `cancelPrint(id)`
- `getMedia()`

### Drive

- `isDiskPresent()`
- `getDiskLabel()`
- `setDiskLabel(name)`
- `list(path)`
- `read(path)`
- `write(path, content)`
- `delete(path)`

### Redstone Relay

- `getInput(side)`
- `setOutput(side, level)`
- `getBundledInput(side)`
- `setBundledOutput(side, mask)`
- `getSideConfig(side)`
- `setSideConfig(side, cfg)`

### ByteChest / Inventory Devices

- `size()`
- `list()`
- `getItem(slot)`
- `pushItems(target, fromSlot, limit, toSlot)`
- `pullItems(source, fromSlot, limit, toSlot)`
- `canPushTo(target)`

## Capability Names

Use small stable strings. A device can expose multiple capabilities.

- `inventory`
- `energy`
- `fluid`
- `gas`
- `redstone`
- `bundled_redstone`
- `monitor`
- `printer`
- `scanner`
- `drive`
- `wireless`
- `gps`
- `mobile`
- `waypoints`
- `combat`
- `shield`
- `solar`
- `stealth`
- `crafting`
- `storage`

## Event Model

Events should be payload-first and consistent.

Suggested event payloads:

### `device_online`

```lua
device_online, id, type, label
```

### `device_offline`

```lua
device_offline, id, type, label
```

### `device_alert`

```lua
device_alert, id, severity, code, message
```
Examples:

- low fuel
- no path
- inventory full
- shield empty
- chunk unloaded
- target missing

### `energy_changed`

```lua
energy_changed, id, current, max
```

### `inventory_changed`

```lua
inventory_changed, id, usedSlots, totalSlots
```

### `task_complete`

```lua
task_complete, taskId, deviceId, kind, success, message
```

## Naming And Identity

Avoid side-only attachment naming for wireless/mobile devices.

Each peripheral should have:

- stable UUID
- generated short name (`drone_3`, `monitor_1`)
- optional user label (`ore-hauler-west`)

Resolution order:

1. exact ID
2. exact short name
3. exact label
4. filtered search

Labels must not be assumed unique. `findByLabel` should return a list unless explicitly asking for one match.

## Popular Mod Compatibility

The universal peripheral should target capability-based compatibility first, then add richer adapters for the most common mods used in ATM10-scale packs.

### Priority Tier 1

These are the integrations most worth shipping first.

#### CC:Tweaked

Goal:

- Familiar API expectations
- `peripheral.find`, `wrap`, `getMethods`, monitor-like ergonomics
- event compatibility where feasible

Implementation notes:

- Keep method names CC-friendly when semantics match.
- Preserve monitor terminal behavior close to CC.
- Provide ByteBlock-only methods as additive extensions.

#### Applied Energistics 2

Goal:

- Read network power and item availability.
- Submit crafting jobs.
- Move items between ByteBlock logistics and AE2.

Suggested methods:

- `getNetworkPower()`
- `getStoredItem(key)`
- `listStoredItems(filter)`
- `exportItem(key, count, target)`
- `importItem(source, count)`
- `craftItem(key, count)`
- `getCraftingJobs()`

Implementation notes:

- Guard with `ModList.get().isLoaded("ae2")`.
- Prefer adapter wrappers over hard dependency except where compile-only is already present.

#### Mekanism

Goal:

- Energy, fluids, chemicals, machine status, transport awareness.

Suggested methods:

- `getEnergyStored()`
- `getFluidTanks()`
- `getChemicalTanks()`
- `getMachineState()`
- `getProgress()`
- `dumpTank(side, amount)`

Implementation notes:

- Start with NeoForge capabilities where possible.
- Add chemical/gas support behind optional adapter interfaces.

#### Create

Goal:

- Read kinetic state, logistics inventories, and sequencing data.

Suggested methods:

- `getStress()`
- `getSpeed()`
- `isRunning()`
- `getTrainState()`
- `readClipboard()`
- `writeClipboard(text)`

Implementation notes:

- Favor interaction with inventories, trains, clipboards, and display surfaces over trying to remote-drive every contraption block.

#### Refined Storage

Goal:

- AE2-like storage/crafting access where RS is present.

Suggested methods:

- `getNetworkEnergy()`
- `listStoredItems(filter)`
- `exportItem(key, count, target)`
- `importItem(source, count)`
- `craftItem(key, count)`

### Priority Tier 2

These matter in heavily automated packs and should be capability-driven where possible.

#### Thermal Series

- machine energy/progress
- side configuration snapshots
- item/fluid tank reads

#### Pipez / LaserIO / XNet

- endpoint inventory/tank visibility
- route status introspection
- transfer counters if exposed

#### Sophisticated Storage / Sophisticated Backpacks

- inventory reads
- upgrade detection
- push/pull item support

#### Functional Storage / Storage Drawers

- drawer counts
- lock state
- compacting state

#### Immersive Engineering

- energy storage
- multiblock machine state
- tank and inventory reads

#### PneumaticCraft: Repressurized

- pressure
- heat
- air level
- logistics drone station state

### Priority Tier 3

Nice to support once the base is stable.

- Occultism storage and transport
- Industrial Foregoing machine telemetry
- Powah energy devices
- Flux Networks power telemetry
- Ender IO conduits and inventories when available on target version

## Capability-First Strategy

Avoid per-mod hardcoding where NeoForge capabilities already solve the problem.

Use generic adapters first for:

- `IItemHandler`
- `IEnergyStorage`
- fluid capabilities
- redstone state

Then add richer optional integrations for mods that expose concepts not covered by generic capabilities, such as:

- AE2 crafting jobs
- Mekanism chemicals
- Create stress/train systems
- PneumaticCraft pressure and drone logistics

## Security And Safety

The universal peripheral must not become a griefing or lag surface.

- Validate ownership/security if the target device supports it.
- Reject remote mutations when the target chunk is unloaded.
- Rate-limit high-cost commands such as scan, pathfind, and mass inventory operations.
- Batch monitor writes and sync flushes.
- Snapshot read-heavy state to avoid repeated block/entity lookups inside one Lua tick.
- Prefer queued commands over direct deep world interaction from Lua threads.

## Suggested Implementation Order

### Phase 1: Universal Base

- Add peripheral registry/service backed by `BluetoothNetwork`
- Add base metadata/discovery methods
- Add `status`, `methods`, `capabilities`, `label`, `setLabel`
- Add `device_online` / `device_offline`

### Phase 2: Existing ByteBlock Devices

- Monitor adapter
- Drone adapter
- Robot adapter
- Charging station adapter
- Scanner adapter
- Inventory adapter for ByteChest/Drive where applicable

### Phase 3: Async Tasks And Alerts

- Task handles
- `task_complete` / `task_failed`
- low-power / inventory-full / path-failed alerts

### Phase 4: Mod Integrations

- Capability-first generic inventory/energy/fluid wrappers
- AE2 adapter
- Mekanism adapter
- Create adapter
- Refined Storage adapter

### Phase 5: Rich Modpack Ergonomics

- filter helpers
- structured searches
- dashboards on monitors
- remote fleet orchestration examples

## Non-Goals

- Reproducing every CC:Tweaked peripheral API verbatim when ByteBlock semantics differ.
- Exposing raw block/entity internals directly to Lua.
- Synchronous long-running operations from Lua.
- Tight compile-time coupling to every supported mod.

## Example Lua Usage

### Find all mobile haulers

```lua
local drones = peripheral.findByCapability("mobile")
for _, d in ipairs(drones) do
  local s = d.status()
  if s.type == "drone" and s.label and s.label:find("hauler") then
    print(s.label, d.getFuel(), d.getShield())
  end
end
```

### Build a monitor dashboard

```lua
local monitors = peripheral.find("monitor")
local chargers = peripheral.findByCapability("energy")

for _, m in ipairs(monitors) do
  m.clear()
  m.setCursorPos(1, 1)
  m.write("Base Power")
  for i, c in ipairs(chargers) do
    local e = c.getEnergy()
    local cap = c.getEnergyCapacity()
    m.setCursorPos(1, i + 2)
    m.write(string.format("%s %d/%d", c.label(), e, cap))
  end
  m.flush()
end
```

### Dispatch a drone delivery task

```lua
local drone = peripheral.findByLabel("ore-hauler-1")[1]
local taskId = drone.deliver("smelter-input", 1, 64)
print("task", taskId)
```

## Implementation Notes For ATM10

ATM10 is the primary integration target. That means:

- prioritize compatibility with CC:Tweaked expectations
- validate against AE2, Mekanism, Create, and Refined Storage first
- prefer capability-based adapters for pack resilience
- treat chunk-loading, cross-dimensional access, and server-thread safety as hard requirements
