# xApocalypse 1.6.3 Bug Test Guide

Use Paper 26.2 and Java 25. Put `xApocalypse-1.6.3.jar` in `plugins/`, delete the older xApocalypse jar, and start the server.

You can do every required check below alone. You are not expected to fake a 200-player load test at home; these are solo smoke tests to catch broken behavior before release.

## 1. Solo horde lag check

1. Join by yourself and note the TPS/MSPT before spawning anything (`tps` and `mspt` in the server console).
2. Let one natural horde spawn, then force a Blood Moon and wait for another spawn cycle.
3. Walk around the edge of your loaded chunks while watching TPS/MSPT and the console.
4. **Pass:** hordes appear normally, the server does not freeze, TPS recovers, and the console has no chunk-loading or spawn errors.

## 2. Admin spawn spam check

1. Run `/xa spawn horde 100 20`.
2. Try it again when the zombie cap is almost full.
3. **Pass:** zombies arrive in small batches, the server does not freeze, and the command never yeets the count past the configured cap.

## 3. Reload + Blood Moon cleanup check

1. Travel around alone for a minute so several nearby chunks have been visited, then run `/xa reload`.
2. Force a Blood Moon, spawn a bunch of event zombies, then run `/xa stopbloodmoon`.
3. **Pass:** cleanup finishes over a few ticks instead of one mega-lag spike, tagged event mobs disappear, and normal zombies stay alive.

## 4. Miner protection check

1. Put a Miner beside blocks protected by your protection plugin.
2. Let it chase you through the wall.
3. **Pass:** protected blocks stay protected. Unprotected configured breakable blocks still get bonked.

## 5. Offline immunity check

1. Eat Zombie Guts, log out, and stay out until the immunity expires.
2. Rejoin after expiry.
3. **Pass:** zombies can target you again and your original max hearts come back. No permanent five-heart curse. Very cringe if it happens.

## 6. Blank save-file recovery check

1. Eat Zombie Guts and confirm you drop to five hearts.
2. Stop the server normally while immunity is still active.
3. Empty `plugins/xApocalypse/data.yml`, then start the server and rejoin.
4. **Pass:** xApocalypse restores your original hearts, removes the orphaned immunity marker, and does not restore the boss bar.

## 7. Optional MythicMobs check

1. If MythicMobs is installed, force a Blood Moon near loaded chunk borders.
2. Watch Mutant spawns and TPS for a few cycles.
3. **Pass:** at most one Mutant appears per cycle, no new chunks load from placement, and no Mutants bypass the global cap.

## If it acts possessed

Send the exact test number, the relevant console error, and what happened right before it broke. “It lagged lol” is not enough evidence 😭
