# xApocalypse Build Tracker

This is the quick resume point for the ten-build release plan.

## Current position

**Working build:** 1.6.4
**Next action:** begin the 1.6.5 exploit and regression-coverage batch.
**Latest archived build:** `2026-10-08/xApocalypse-1.6.4.jar`

## Release queue

| Build | Planned release | Status | Focus |
|---|---:|---|---|
| 1.6.2 | 2026-10-09 | Built, tested, committed and pushed | Async safety, persistence and half-dead zombies |
| 1.6.3 | 2026-10-16 | Built, tested, committed and pushed | Horde, chunk-loading and LOD performance |
| 1.6.4 | 2026-10-23 | Built, archived, live-tested, committed and pushed | Moderate/minor bugs, lifecycle cleanup and the final admin-spawn load fixes |
| 1.6.5 | 2026-10-30 | Not started | Exploits and regression coverage |
| 1.7.0 | 2026-11-06 | Not started | Blood Moon atmosphere, scary sounds and sleep prevention |
| 1.7.1 | 2026-11-13 | Not started | Crouching reduces zombie detection range |
| 1.8.0 | 2026-11-20 | Not started | Thirst system |
| 1.9.0 | 2026-11-27 | Not started | Airdrop system |
| 1.10.0 | 2026-12-04 | Not started | Basic airdrop loot GUI with weights in a separate config |
| 1.11.0 | 2026-12-11 | Not started | Rogue player NPC |

## Remaining builds

Seven builds remain after 1.6.4:

1. 1.6.5
2. 1.7.0
3. 1.7.1
4. 1.8.0
5. 1.9.0
6. 1.10.0
7. 1.11.0

## Rule for every build

Immediately after a successful build:

1. Create `versions/YYYY-MM-DD/` using that build date.
2. Copy the correctly named JAR into it.
3. Add a short, simple `BUGFIX-GUIDE.md` for that exact JAR.
4. Confirm the target and archived JAR hashes match.
5. Live-test before committing and pushing.
