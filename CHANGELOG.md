# Changelog

## Unreleased

### Added

- **ByteChest Logistics tab** — new tab in the sneak-right-click config screen with:
  - 6 AE2 pull-filter slots with item-id autocomplete suggestions.
  - `Keep` target stock level and configurable items-per-tick budget.
  - 6-direction push picker that auto-detects and labels adjacent blocks (parens = no
    `IItemHandler`).
  - Player inventory grid display for quick verification.
- **AE2 grid integration on ByteChest** — `Ae2GridNodeBridge` registers the chest as an
  `IInWorldGridNodeHost`, so AE2 cables visually connect and the chest can extract items
  from the ME network directly via its own managed node (no requirement for an adjacent
  AE2-aware block).
- **Logistics diagnostic logging** (`ByteChest/Logistics`, `ByteChest/PullDiag`,
  `ByteChest/AE2Extract`) — reports configuration saves, exact pull/push failure reasons,
  AE2 cache state, simulated extract availability, and inventory free space every 5 seconds
  while a transfer is enabled but nothing moves.

### Fixed

- **AE2 reflection cache**: `Actionable` was being looked up at
  `appeng.api.networking.action.Actionable` (incorrect) instead of
  `appeng.api.config.Actionable`. The wrong package caused `mMEStorageExtract`,
  `actionableSimulate`, and `actionableModulate` to all be `null`, silently disabling every
  AE2 extract path used by ByteChest pull, AE2 peripheral methods, and the Materials
  Calculator. Now resolves correctly and items pull from the ME network as expected.

## Unreleased (previous)

### Added

- **Universal Peripheral Layer** — full mod support milestone.
  - `IPeripheralAdapter`: universal metadata defaults (`getTypes`, `getCapabilities`, `getLabel`, `getStableId`, `injectUniversalMeta`).
  - New block-entity adapters: `ByteChest`, `ChargingStation`, `Scanner`, `Drive`, `Printer`, `RedstoneRelay`.
  - **AE2** adapter: `getTypes`/`getCapabilities` tags, `listFiltered(filter)`, `getStorageInfo()` Lua methods.
  - **Create** adapter: `getTypes`/`getCapabilities` tags.
  - **Create: Storage** adapter: `getTypes`/`getCapabilities` tags.
  - **Mekanism** adapter: `getTypes`/`getCapabilities` tags.
  - Robot and Drone entity peripherals — discoverable via `peripheral.wrap`, `peripheral.list`, `peripheral.findByCapability` etc.
  - `peripheral.*` Lua API expanded: `list`, `hasType`, `findByLabel`, `findById`, `findByCapability`, `status`, universal instance methods.
- **Peripheral event model** — `OSEvent` types `PERIPHERAL_ONLINE`, `PERIPHERAL_OFFLINE`, `PERIPHERAL_ALERT`.
  - JavaOS diffs peripheral name sets each snapshot tick and fires online/offline events automatically.
  - Low-fuel/low-energy alerts fire when a robot or drone drops below 10% capacity.
  - Lua programs receive `peripheral_online`, `peripheral_offline`, `peripheral_alert` via `os.pullEvent()`.
- Puzzle IDE deployment options for BT-only, disk-only, or both.
- Bluetooth deploy ACK flow for robot/drone upload handlers.
- Session-level project log file (`SESSION_LOG.md`) for chronological tracking.
- Universal peripheral specification document (`docs/universal-peripheral-spec.md`).

### Fixed

- `RobotEntity.handleBluetoothMessage`: `robot:cmd:goto:x:y:z` was truncated to just `goto` — now correctly reconstructs the full nav command string.

### Changed
- README now states ATM10 as the primary integration test target.
- Lua/peripheral integration paths updated toward server-safe snapshot/cache access patterns for modpack stability.
- VFS file-content persistence updated to UTF-8 byte-array storage for large-file safety.

### Notes
- Final closure of historical large startup-script ATM10 regressions depends on in-pack repro verification after latest jar deployment.
