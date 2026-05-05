# Session Log

## 2026-05-03

### Context
- Primary validation target set to `All the Mods 10 (ATM10)`.
- Focus area: large Lua `startup` workloads and cross-mod peripheral/inventory reliability.

### Workstream Summary
- Puzzle IDE feature and deployment pipeline expanded (disk + Bluetooth, deploy modes, ACK flow, UI controls).
- Robot and drone upload/mission receive handlers were extended for remote deploy behavior.
- Drone mission runtime and control-flow handling were expanded.
- Multiple UI and renderer updates were applied across monitor/robot/drone interfaces.

### Stability-Oriented Changes Relevant to Startup/Inventory Failures
- Lua peripheral access paths migrated away from coroutine-thread capability/chunk lookups to snapshot/cached lookups where possible.
- Adjacent item-handler access was shifted to server-side cached handlers to avoid thread handoff deadlocks.
- VFS payload storage moved to UTF-8 byte-array persistence for large file safety.

### Testing Notes
- Build/compile checks completed successfully during this pass.
- Final issue-closure for the historical large-`startup` ATM10 scenario still requires explicit in-pack repro confirmation.

### Documentation Updates in This Session
- Updated `README.md` with ATM10-target and stability notes.
- Updated `.github/copilot-instructions.md` with test-target and docs-update policy.
- Added/updated this `SESSION_LOG.md`.

## 2026-05-04

### ByteChest Logistics + AE2 Integration

#### Workstream Summary
- Added 6-slot AE2 pull-filter UI with item-ID autocomplete and a 6-direction push picker that detects adjacent block item-handler capability.
- Wired `Ae2GridNodeBridge` so ByteChest is registered as an `IInWorldGridNodeHost`; cables now visually connect and items can be extracted directly via the chest's own managed grid node.
- Added rich diagnostic logging (`ByteChest/Logistics`, `ByteChest/PullDiag`, `ByteChest/AE2Extract`) that fires every 5s when a transfer is enabled but nothing moves, surfacing the exact failure reason.

#### Bug Hunt
- AE2 pull silently failed for several iterations. Diagnostics narrowed the failure to `mMEStorageExtract` / `actionableSimulate` / `actionableModulate` all being `null` despite the grid node being on the network and storage being reachable.
- Root cause: the reflection cache loaded `Actionable` from `appeng.api.networking.action.Actionable`. The correct package is `appeng.api.config.Actionable`. Wrong class lookup zeroed out the entire extract path.
- Fix: cache lookup now tries `appeng.api.config.Actionable` first, with the old path as a fallback. Verified in-game: oak_log pulls items per tick from the ME network as configured.

#### Documentation Updates in This Session
- `README.md` ByteChest section rewritten to describe the new Logistics tab, AE2 integration, and diagnostic log channels.
- `CHANGELOG.md` Unreleased section gained a new block describing the Logistics tab, AE2 grid host registration, diagnostic logging, and the `Actionable` reflection-cache fix.
