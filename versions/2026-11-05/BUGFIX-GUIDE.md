# xApocalypse 1.7.1 Test Guide

This release fixes the `day-spawn-chance: 0.0` boundary and keeps normal daytime and nighttime horde spawning unchanged.

## Setup

1. Use Paper 26.2 and Java 25.
2. Put `xApocalypse-1.7.1.jar` in the server's `plugins` folder and remove the older xApocalypse JAR.
3. Fully restart, then confirm xApocalypse 1.7.1 is enabled.

## Quick regression check

1. Set `apocalypse-settings.spawn-rate: 100`, `day-spawn-chance: 0.0`, and `debug-mode: true`.
2. Run `/xa reload`, set the enabled survival world to daytime, and wait through several spawn cycles.

**Pass:** no daytime cycle marks the player spawn-eligible and no xApocalypse horde spawns.

3. Change `day-spawn-chance` to `1.0`, run `/xa reload`, and remain in daytime.

**Pass:** the next cycle marks the player spawn-eligible and attempts a daytime horde.

4. Set the world to nighttime and wait for another cycle.

**Pass:** normal nighttime hordes still spawn.

Restore the server's usual spawn rate, daytime chance, and debug setting after testing.
