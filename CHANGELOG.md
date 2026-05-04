# Changelog

## Unreleased

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
