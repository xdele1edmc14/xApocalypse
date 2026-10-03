# xApocalypse 1.6.3 Bug Test Guide

Use Paper 26.2 and Java 25. Put `xApocalypse-1.6.3.jar` in `plugins/`, delete the older xApocalypse jar, and start the server.

## 1. Horde lag check

1. Get several players into different loaded areas.
2. Let natural hordes run, including during a Blood Moon.
3. Watch MSPT/TPS and the console.
4. **Pass:** one spawn cycle does not cook the server, unloaded borders do not generate chunks, and hordes still appear normally.

## 2. Admin spawn spam check

1. Run `/xa spawn horde 100 20`.
2. Try it again when the zombie cap is almost full.
3. **Pass:** zombies arrive in small batches, the server does not freeze, and the command never yeets the count past the configured cap.

## 3. Reload + Blood Moon cleanup check

1. Spread players out so lots of chunks are loaded, then run `/xa reload`.
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

## 6. Optional MythicMobs check

1. If MythicMobs is installed, force a Blood Moon near loaded chunk borders.
2. Watch Mutant spawns and TPS for a few cycles.
3. **Pass:** at most one Mutant appears per cycle, no new chunks load from placement, and no Mutants bypass the global cap.

## If it acts possessed

Send the exact test number, the relevant console error, and what happened right before it broke. “It lagged lol” is not enough evidence 😭
