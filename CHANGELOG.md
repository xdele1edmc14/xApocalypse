# Changelog

All notable changes to xApocalypse are documented here.

## [1.6.3] - 2026-10-03

### Fixed

- Put one strict terrain-probe budget around each natural horde cycle, randomized player order, and checked rise-animation capacity before touching terrain.
- Rejected unloaded chunks before ordinary zombie or optional MythicMobs Mutant terrain lookups, preventing synchronous chunk loads during placement.
- Capped `/xa spawn` by remaining world capacity and spread large requests into ten-attempt tick batches.
- Clamped the Mutant loop to a safe interval, eight real placement probes, and at most one successful spawn per cycle.
- Reused primitive player-coordinate snapshots for LOD and culling instead of allocating player and zombie `Location` objects for every pair.
- Batched and deduplicated loaded-chunk cleanup on reload, and batched Blood Moon entity removal through UUID queues.
- Allowed fatal JVM errors to escape the repeating horde task while continuing to log recoverable exceptions.
- Fired cancellable `EntityChangeBlockEvent` events before Miner zombies alter blocks, allowing protection plugins to veto griefing.
- Removed expired offline immunity entries from active scans while retaining the saved max-health restoration state for their next join.

### Compatibility

- Targets Paper 26.2 and Java 25. Existing configuration and persistence files remain compatible.
- MythicMobs remains optional, and xApocalypse Mutants remain supported.

## [1.6.2] - 2026-10-02

### Fixed

- Prevented Nurse zombies from healing invalid, dead, or zero-health zombies during their death sequence, fixing permanently red, tilted, unkillable bodies.
- Rate-limited Nurse radius scans even when no target is healed.
- Made PlaceholderAPI Blood Moon reads use a synchronous cached snapshot and moved placeholder-visible scent and immunity state to concurrent collections.
- Replaced gameplay-thread immunity and Blood Moon YAML writes with ordered background writes that atomically replace their target files and flush during shutdown.
- Kept forced Blood Moons active across restarts by calculating their remaining duration from the persisted wall-clock start time instead of the current in-game night time.
- Configured Mockito as a Java agent for stable Java 25 test runs.

## [1.6.1] - 2026-09-06

### Fixed

- Fixed Miner obstruction detection so fractional entity coordinates and diagonal targets no longer make it inspect its own block instead of the wall ahead.
- Miners now target breakable supporting blocks beneath nearby elevated players, countering simple two-block towers while continuing to respect configured breakables and claim protection.
- Fixed Spitters becoming harmless after normal zombie pathfinding carried them inside the old four-block minimum range.
- Spitters now require line of sight, retreat from close targets, aim at the target's eyes, and only start their cooldown after launching successfully.

### Balance

- Reduced the default Spitter cooldown from 6 to 4 seconds, increased the default effect to Poison II, added 2 HP of immediate acid impact damage, and extended its default range to 1.5–18 blocks.
- Expanded the default Miner breakable list with common stone, deepslate, modern plank, Nether plank, and bamboo plank materials.

## [1.6.0] - 2026-08-20

### Changed

- Updated the officially supported server target from Paper 1.21 to Paper 26.2.
- Updated the build and runtime baseline from Java 21 to Java 25, matching Paper 26.2 requirements.
- Updated the compile-time Paper API to `26.2.build.112-stable` and declared `api-version: 26.2` in `plugin.yml`.
- Updated the provided Adventure API to 5.2.0 and the Java 25-compatible Mockito test dependency to 5.23.0.
- Synchronized the release version, compatibility badges, installation artifact names, bundled resource headers, README, testing guide, and wiki documentation.

### Compatibility and upgrade notes

- Existing configuration and data files remain compatible; this release does not change gameplay settings or reset stored state.
- GriefPrevention, MythicMobs, and PlaceholderAPI remain optional integrations. Install builds of those plugins that explicitly support Minecraft 26.2.

## [1.5.2] - 2026-07-25

### Fixed

- Fixed a bug where the spawner did not work in custom world generators.

## [1.5.1] - 2026-07-21

### Added

- Added optional PlaceholderAPI integration through a bundled internal expansion. PlaceholderAPI remains a soft dependency, so xApocalypse still loads normally when it is absent.
- Added `%xapocalypse_bloodmoon_days_left%` for the whole number of in-game days until the next natural Blood Moon. It returns `Tonight` while a Blood Moon is active or due that day.
- Added `%xapocalypse_zombie_guts_duration%` for the active player's remaining Zombie Guts duration in whole seconds, returning `0` when inactive.
- Added `%xapocalypse_current_scent%` for the active player's live scent value rounded to the nearest whole number, returning `0` when no player context or scent is available.
- Added a configurable vanilla sound when a natural or forced Blood Moon begins. It is played to online players in enabled and lobby worlds.
- Added a configurable proximity sound after every successful Mutant spawn, including guaranteed Blood Moon, periodic Blood Moon, and admin-command spawns.
- Added sound controls for enablement, Bukkit sound name, volume, and pitch. Mutant sounds also support a configurable hearing radius.
- Added a dedicated PlaceholderAPI documentation page and updated the README and configuration reference for the new features.

### Configuration

- Added `bloodmoon.start-sound.enabled` (default `true`).
- Added `bloodmoon.start-sound.name` (default `ENTITY_WITHER_SPAWN`).
- Added `bloodmoon.start-sound.volume` (default `1.0`).
- Added `bloodmoon.start-sound.pitch` (default `0.7`).
- Added `mythicmobs.integration.spawn-sound.enabled` (default `true`).
- Added `mythicmobs.integration.spawn-sound.name` (default `ENTITY_ENDER_DRAGON_GROWL`).
- Added `mythicmobs.integration.spawn-sound.volume` (default `1.0`).
- Added `mythicmobs.integration.spawn-sound.pitch` (default `0.8`).
- Added `mythicmobs.integration.spawn-sound.radius` (default `48.0` blocks).
- Existing installations receive these missing keys through the automatic configuration synchronizer. Administrators do not need to delete or regenerate `config.yml`; existing settings and comments are preserved.
- Invalid configured sound names are skipped safely and reported in the server log instead of preventing the plugin from loading.

### Changed

- Added PlaceholderAPI 2.11.6 as a compile-time-only dependency and declared PlaceholderAPI in `plugin.yml` as a soft dependency.
- Blood Moon day calculations and Zombie Guts remaining-time access are now exposed as read-only manager APIs for integrations.
- Mutant spawn audio is emitted from the shared successful-spawn path, ensuring consistent behavior across every supported spawn source.
- Updated all current release badges, help text, installation examples, artifact names, and resource headers to version 1.5.1.

### Fixed

- Improved Minecraft attribute resolution across Paper 1.21 API variants. xApocalypse now tries modern registry keys, legacy namespaced keys, and reflective legacy attribute fields before reporting a missing attribute.

### Compatibility and upgrade notes

- Requires Java 21 and a server compatible with Bukkit/Paper API 1.21, matching previous releases.
- GriefPrevention, MythicMobs, and PlaceholderAPI integrations are all optional.
- PlaceholderAPI values requiring player state return `0` when parsed without a player context.
- Sound triggers use vanilla Bukkit sound names only; custom resource-pack sound keys are not supported in this release.
