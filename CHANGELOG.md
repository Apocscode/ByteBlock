# Changelog

## Unreleased

### Added
- Puzzle IDE deployment options for BT-only, disk-only, or both.
- Bluetooth deploy ACK flow for robot/drone upload handlers.
- Session-level project log file (`SESSION_LOG.md`) for chronological tracking.

### Changed
- README now states ATM10 as the primary integration test target.
- GitHub Copilot project instructions now enforce ATM10-first verification and doc update policy.
- Lua/peripheral integration paths updated toward server-safe snapshot/cache access patterns for modpack stability.
- VFS file-content persistence updated to UTF-8 byte-array storage for large-file safety.

### Notes
- Final closure of historical large startup-script ATM10 regressions depends on in-pack repro verification after latest jar deployment.
