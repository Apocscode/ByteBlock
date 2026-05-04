# ByteBlock Mod — Copilot Instructions

## Project Identity
- **Mod**: ByteBlock (`com.apocscode.byteblock`)
- **Platform**: NeoForge 1.21.1, `net.neoforged.gradle.userdev` 7.1.22
- **Java**: 21 (Java 21 language features are fine: records, sealed classes, switch expressions, pattern matching)
- **Build**: `Push-Location F:\JavaCraft; .\gradlew.bat shadowJar -x test`
- **Deploy dev**: `Copy-Item "F:\JavaCraft\build\libs\byteblock-0.1.0.jar" "F:\JavaCraft\runs\client\mods\byteblock-0.1.0.jar" -Force`
- **Deploy ATM10**: `Copy-Item "F:\JavaCraft\build\libs\byteblock-0.1.0.jar" "C:\Users\travf\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\byteblock-0.1.0.jar" -Force`

## Test Target Policy
- Primary integration test target is **ATM10**.
- Do not treat `runClient` success alone as feature verification for cross-mod behavior.
- When reporting fixes for runtime/integration issues, prefer ATM10 reproduction status first and local dev status second.

## Documentation Update Policy
- For feature batches or bugfix passes, update these docs in the same branch:
  - `README.md` for user-facing behavior/status notes.
  - `SESSION_LOG.md` for chronological implementation/testing notes.
  - `CHANGELOG.md` for concise release-style entries.

## NeoForge 1.21.1 Rules — NEVER Violate These

### NBT / Data Components
- NEVER use `stack.getTag()`, `stack.setTag()`, `stack.getOrCreateTag()` — these do not exist in 1.21.1
- ALWAYS use `stack.get(DataComponents.X)` / `stack.set(DataComponents.X, value)`
- Custom data: use `stack.get(ModDataComponents.MY_COMPONENT)` via `DeferredRegister<DataComponentType<?>>`

### Registries
- ALWAYS use `DeferredRegister<T>` — never `Registry.register(...)` directly
- Event bus registration: `modBus.addListener(...)` on the mod event bus, `forgeBus.addListener(...)` for game events
- `@EventBusSubscriber(modid = ByteBlock.MODID, bus = EventBusSubscriber.Bus.MOD)` for mod-bus static subscribers

### Networking (1.21.1)
- Use `CustomPacketPayload` + `IPayload` — NOT `SimpleChannel` or `PacketDistributor` from old Forge
- Register packets with `PayloadTypeRegistry.playS2C().register(...)` and `playC2S`
- Handle with `IPayloadHandler<T>`

### Capabilities
- Use `Capabilities.EnergyStorage.BLOCK` / `Capabilities.EnergyStorage.ITEM` — not `CapabilityManager`
- Attach via `RegisterCapabilitiesEvent` on the mod bus
- `EnergyStorage(capacity, maxReceive, maxExtract, initialEnergy)` — 4-arg constructor

### Events
- `@SubscribeEvent` on instance methods works; static requires `@EventBusSubscriber`
- `ServerTickEvent.Post` not `ServerTickEvent` (split in 1.21.1)
- Entity ticks: override `tick()` and call `super.tick()` first

### Client-side
- Renderer registration in `@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.MOD)`
- `EntityRendererProvider.Context` passed to renderer constructors
- `SynchedEntityData` for entity fields synced to clients — define with `SynchedEntityData.defineId()`
- Menu energy sync: use `ContainerData` + `addDataSlots()` in the menu constructor

## Project Structure
```
src/main/java/com/apocscode/byteblock/
  block/          — Block classes + BlockEntity (ChargingStationBlock, ChargingStationBlockEntity)
  block/entity/   — Block entities
  client/         — Client-only: screens (RobotScreen, DroneScreen), renderers (RobotRenderer, DroneRenderer)
  computer/       — JavaOS, LuaRuntime, command system
  entity/         — RobotEntity (PathfinderMob), DroneEntity, UnicycleRobotEntity
  init/           — ModBlocks, ModItems, ModEntityTypes, ModMenuTypes, ModBlockEntityTypes
  item/           — Items including GpsToolItem
  menu/           — RobotMenu, ChargingStationMenu (AbstractContainerMenu subclasses)
  network/        — BluetoothNetwork
  ByteBlock.java  — Main mod class
```

## Key Classes & Their State
- **RobotEntity**: PathfinderMob, `EnergyStorage(10000, 200, 10000, 0)`, `ENERGY_PER_ACTION=10`, `MOVEMENT_SPEED=0.2`, homing threshold 20%, `GroundPathNavigation` with `setMaxVisitedNodesMultiplier(4.0f)`
- **ChargingStationBlockEntity**: `EnergyStorage(100000, 1000, 200, 0)`, `RANGE=3.0`, `CHARGE_RATE=200`, passive generation 1000 FE/t, charges nearby RobotEntity and DroneEntity
- **RobotMenu**: `ContainerData` syncs energy (4 slots: low/high bits for stored and max). `getSyncedEnergy()` / `getSyncedMaxEnergy()` for client use
- **DroneEntity**: Uses `fuelTicks` (starts 6000), NOT FE directly. Station converts FE→fuel ticks

## Code Conventions
- Logger: `private static final Logger LOGGER = LogUtils.getLogger();` (import `com.mojang.logging.LogUtils`)
- `setChanged()` after mutating BlockEntity state
- `level == null` guard at top of `serverTick()`
- Energy guard: `if (energyStorage.getEnergyStored() <= 0) return;` before charging loops
- Use `Math.min(toTransfer, energyStorage.getEnergyStored())` to avoid over-extraction
- `receiveEnergy(amount, false)` returns actual received — always use the return value for extraction

## What NOT to Do
- Do NOT add `@Override` annotations to methods that don't exist in the parent
- Do NOT use deprecated `EventBusSubscriber.Bus` — check if `bus()` is still valid in 7.1.22
- Do NOT use `level.isClientSide` in BlockEntity serverTick — it's already server-only
- Do NOT suggest `ItemStack.getCount()` for energy; energy is in `EnergyStorage` capability
- Do NOT hallucinate NeoForge method names — if unsure, say so
