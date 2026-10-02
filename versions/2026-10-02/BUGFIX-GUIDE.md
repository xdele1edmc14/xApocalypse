# xApocalypse 1.6.2 Bug Test Guide

This guide is only for the fixes in **1.6.2**.

## Before you test

1. Back up the server.
2. Use Paper 26.2 and Java 25.
3. Put `xApocalypse-1.6.2.jar` in `plugins/` and remove the older xApocalypse jar.
4. Start the server and make sure xApocalypse is green in `/plugins`.

## 1. Half-dead zombie check

1. Spawn a Nurse zombie and a few normal zombies near it.
2. Damage the normal zombies, then kill them while the Nurse is still nearby.
3. Repeat this a bunch of times with fast kills.
4. **Pass:** dead zombies disappear normally. No red zombie should stay tilted, frozen, immortal, or stuck forever.

## 2. Nurse lag check

1. Leave a Nurse alive in an empty area with no injured zombies.
2. Watch TPS/MSPT for a few minutes, then add more Nurses.
3. **Pass:** empty Nurses do not spam radius scans every AI tick, and MSPT does not randomly cook itself.

## 3. Placeholder check

1. Install PlaceholderAPI.
2. Test these placeholders with `/papi parse me <placeholder>`:
   - `%xapocalypse_bloodmoon_days_left%`
   - `%xapocalypse_zombie_guts_duration%`
   - `%xapocalypse_current_scent%`
3. If you use a scoreboard or tab plugin, show the same placeholders there for a few minutes.
4. **Pass:** values show normally and the console has no async Bukkit warnings, collection errors, or placeholder stack traces.

## 4. Save-file check

1. Eat Zombie Guts so immunity is active.
2. Force a Blood Moon with `/xa forcebloodmoon 2`.
3. Stop the server normally, then start it again.
4. Check `plugins/xApocalypse/data.yml` and `BloodMoonData.yml`.
5. **Pass:** both files are valid YAML, immunity/Blood Moon state survives correctly, and neither file is blank or cut in half.

## If something breaks

Send the console error, the exact test number, and what you did right before it broke. “It bugged” is not enough lore 😭
