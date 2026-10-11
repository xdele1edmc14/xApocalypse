# xApocalypse 1.6.5 Bug Test Guide

Test these one at a time. If one fails, stop there and send the matching console lines.

## Setup

1. Use Paper 26.2 and Java 25.
2. Put `xApocalypse-1.6.5.jar` in the test server's `plugins` folder.
3. Delete the older xApocalypse JAR, then fully restart.
4. Run `/plugins` and make sure xApocalypse is green.

## Test 1: Fake Zombie Guts

1. Rename normal rotten flesh to the Zombie Guts name in an anvil.
2. Right-click or eat it.

**Pass:** no immunity boss bar, no five-heart max, and the fake item does nothing special.

## Test 2: Real Zombie Guts

1. Run `/xa item zombie_guts`.
2. Use the real item.

**Pass:** immunity starts normally and the item is consumed once.

## Test 3: Full inventory grant

1. Fill every storage slot with full stacks of random items.
2. Run `/xa item zombie_guts <your-name> 64`.

**Pass:** it says there is not enough space and gives **zero** Guts. No sneaky partial stack.

## Test 4: Webber webs

1. Spawn a Webber with `/xa spawn webber 1 5`.
2. Let it hit you, then break one of its temporary cobwebs.

**Pass:** the temporary web drops no string and no XP. A normal cobweb you placed yourself still behaves normally.

## Test 5: Fire rules

1. Spawn a Runner and a Scorched zombie.
2. Light both on fire or put them in lava.
3. Confirm `zombie-settings.allow-daylight-burning: false`, restart, then expose a Runner and a vanilla zombie created with `/summon zombie` to daylight.
4. Hit the Runner with Fire Aspect.

**Pass:** the Runner is protected from sunlight but still takes Fire Aspect/lava damage, Scorched stays fully fireproof, and the vanilla zombie still burns in daylight.

## Test 6: Shield and cancelled hits

1. Hold a shield up and let a Webber hit it.
2. Repeat with Frost, Scorched and Psychopath if you can.

**Pass:** a fully blocked zero-damage hit does not add webs, slowness, fire or Wither.

## Test 7: Burster protection

1. Stand inside a GriefPrevention claim.
2. Spawn a Burster and let its fuse finish.

**Pass:** the explosion can hurt as configured, but claimed blocks stay intact.

## Test 8: Zombie cap ownership

1. Set `performance.max-total-zombies: 1` and restart.
2. Keep one normal vanilla command/spawner zombie alive (for example, `/summon zombie`).
3. Run `/xa spawn normal 1 5`.
4. Try the command again while that xApocalypse zombie is alive.

**Pass:** the first xApocalypse zombie spawns because the vanilla zombie does not use the cap. The second request is stopped by the cap.

## Test 9: Quick regression lap

1. Force a short Blood Moon with `/xa forcebloodmoon 2`.
2. Spawn several classes and fight normally.
3. Stop and restart the server once.

**Pass:** no stuck red tilted zombies, no blank YAML files, Blood Moon state survives, and the console has no repeating errors.

## Send this if it breaks

Send the test number, what happened, what should have happened, and the matching part of `logs/latest.log`.
