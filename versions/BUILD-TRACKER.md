# xApocalypse Build Tracker

This is the quick resume point for the weekly release plan.

## Current position

**Working build:** 1.7.2
**Next action:** begin the safe, notification-only Modrinth update checker.
**Latest archived build:** `2026-11-05/xApocalypse-1.7.1.jar`
**Archive note:** confirm or correct the pre-existing `2026-11-06/xApocalypse-1.7.0.jar` folder date before publishing 1.7.0.

## Release queue

| Build  | Planned release | Status                                                                      | Focus                                                                       |
| ------ | --------------: | --------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| 1.6.2  |      2026-10-09 | Built, tested, committed and pushed                                         | Async safety, persistence and half-dead zombies                             |
| 1.6.3  |      2026-10-16 | Built, tested, committed and pushed                                         | Horde, chunk-loading and LOD performance                                    |
| 1.6.4  |      2026-10-23 | Built, archived, live-tested, committed and pushed                          | Moderate/minor bugs, lifecycle cleanup and the final admin-spawn load fixes |
| 1.6.5  |      2026-10-30 | Built, archived and tested; 75 automated tests plus live regressions passed | Exploits and regression coverage                                            |
| 1.7.0  |      2026-11-06 | Built, tested and booted; full in-game guide pending                        | Blood Moon atmosphere, scary sounds and sleep prevention                    |
| 1.7.1  |      2026-11-05 | Built, archived, automated-tested and live-tested                           | Fix daytime spawning when the configured chance is `0.0`                    |
| 1.7.2  |      2026-11-20 | Not started                                                                 | Safe Modrinth update checker and admin notifications                        |
| 1.8.0  |      2026-11-27 | Not started                                                                 | Crouching reduces zombie detection range                                    |
| 1.9.0  |      2026-12-04 | Not started                                                                 | Configurable/renamable zombie display names                                 |
| 1.10.0 |      2026-12-11 | Not started                                                                 | Localization foundation plus Spanish, Hindi and other reviewed translations |
| 1.11.0 |      2026-12-18 | Not started                                                                 | Screamer and Stalker zombie classes                                         |
| 1.12.0 |      2026-12-25 | Not started                                                                 | Thirst system                                                               |
| 1.13.0 |      2027-01-01 | Not started                                                                 | Airdrops near players in loaded chunks                                      |
| 1.14.0 |      2027-01-08 | Not started                                                                 | Airdrop deposit/loot GUI with weighted `airdrops.yml`                       |
| 1.15.0 |      2027-01-15 | Not started                                                                 | Full admin dashboard and visual airdrop editor                              |
| 1.16.0 |      2027-01-22 | Not started                                                                 | Rogue player NPC                                                            |
| 1.16.1 |      2027-01-29 | Not started                                                                 | Rogue NPC balancing, advanced behaviour and fixes                           |
| 1.17.0 |      2027-02-05 | Not started                                                                 | Original, redistributable default zombie boss                               |

## Remaining builds

Twelve weekly builds follow 1.7.1:

1. 1.7.2 — update checker
2. 1.8.0 — crouch stealth
3. 1.9.0 — renamable zombies
4. 1.10.0 — translations
5. 1.11.0 — Screamer and Stalker
6. 1.12.0 — thirst
7. 1.13.0 — airdrops
8. 1.14.0 — airdrop GUI and weights
9. 1.15.0 — admin dashboard
10. 1.16.0 — rogue NPC
11. 1.16.1 — rogue NPC balancing
12. 1.17.0 — default zombie boss

## 1.7.2 update-check scope

- Check Modrinth on server startup and every 6 hours.
- Notify the console and online admins when a newer stable version exists.
- Provide config toggles for update checks and admin notifications.
- Never download, replace or restart the plugin automatically.

## Rule for every build

Immediately after a successful build:

1. Create `versions/YYYY-MM-DD/` using that build date.
2. Copy the correctly named JAR into it.
3. Add a short, simple `BUGFIX-GUIDE.md` for that exact JAR.
4. Confirm the target and archived JAR hashes match.
5. Live-test before committing and pushing.
