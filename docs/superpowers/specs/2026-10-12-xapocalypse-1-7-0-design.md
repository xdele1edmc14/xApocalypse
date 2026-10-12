# xApocalypse 1.7.0 Blood Moon Atmosphere Design

## Goal

Make Blood Moons feel threatening without adding expensive effects or per-player tasks. Version 1.7.0 adds stormy weather, occasional vanilla sound cues, and sleep prevention while preserving natural and forced Blood Moons across restarts.

## Scope

The release includes:

- Forced storm and thunder during natural and forced Blood Moons.
- Periodic scary sounds for players in the active Blood Moon world.
- Sleep prevention with an optional phantom-timer reset.
- Full restart recovery and restoration of the world's previous weather.
- One undocumented, lightweight scare described in the internal section below.
- A comment cleanup across Java and bundled YAML files.

The release does not include particles, potion effects, resource-pack sounds, per-player repeating tasks, lobby effects, or unrelated gameplay changes.

## Player Behavior

The atmosphere applies only to the enabled world in which the Blood Moon is active. Lobby worlds and other enabled worlds remain unchanged.

When a natural or forced Blood Moon begins, the plugin saves the active world's current storm flag, thunder flag, weather duration, and thunder duration. It then applies the configured Blood Moon weather. The existing once-per-second Blood Moon lifecycle heartbeat keeps the requested weather active, but only writes to the world when a value has changed.

One global sound timer chooses a random interval between the configured minimum and maximum. When it expires, the plugin chooses one valid configured vanilla sound and plays it once to each online player currently in the active Blood Moon world. It then chooses the next interval. No player receives an individual timer.

Bed entry is cancelled only when all of these are true:

- A Blood Moon is active.
- The player is in the active Blood Moon world.
- Sleep blocking is enabled.

The player receives the configured sleep-blocked message. When the separate phantom-reset setting is enabled, the attempt also resets `timeSinceRest` to zero so blocking sleep does not punish the player with phantom spawns.

## Configuration

The public configuration will use this shape:

```yaml
bloodmoon:
  atmosphere:
    enabled: true
    weather:
      enabled: true
      storm: true
      thunder: true
      restore-previous-weather: true
    sounds:
      enabled: true
      min-interval-seconds: 60
      max-interval-seconds: 120
      volume: 0.8
      pitch: 0.7
      names:
        - AMBIENT_CAVE
        - ENTITY_WITHER_AMBIENT
        - ENTITY_WARDEN_HEARTBEAT
  sleep:
    blocked: true
    reset-phantom-timer: true
```

`messages.yml` will add:

```yaml
bloodmoon:
  sleep-blocked: "&4The Blood Moon will not let you rest."
```

All public 1.7.0 behavior can be disabled independently. The hidden scare is the sole deliberate exception and has no public configuration entry.

Invalid sound names are logged once when configuration is loaded and then ignored. An empty valid sound list safely disables periodic cues. Intervals are clamped to at least one second, and a maximum lower than the minimum is raised to the minimum. Volume cannot be negative, and pitch is kept within Bukkit's useful range.

## Architecture

### `BloodMoonAtmosphereManager`

A new focused manager owns atmosphere configuration and transient state. It exposes lifecycle methods for Blood Moon start, resume, heartbeat, end, shutdown suspension, and configuration reload.

Its responsibilities are:

- Capture, persist, apply, and restore a `WeatherSnapshot`.
- Track one next-sound timestamp for the whole Blood Moon.
- Resolve the configured sound list once at startup or reload.
- Play cues only to players in the active Blood Moon world.
- Own the one-shot state for the hidden scare.

It does not create scheduled tasks and does not decide whether a Blood Moon is active.

### `BloodMoonManager`

The existing manager remains the authority for natural and forced Blood Moon state. Its existing synchronous one-second heartbeat calls the atmosphere manager only while an event is active. Every start path calls either `start` or `resume`, and the centralized `signalBloodMoonEnd()` calls atmosphere cleanup before zombie cleanup.

On plugin shutdown, the atmosphere manager restores the live world's weather without deleting the persisted snapshot. This prevents the forced storm from becoming permanent if the plugin is removed. If the plugin starts again while the Blood Moon is active, it reapplies the atmosphere from the retained snapshot.

### `xApocalypseListener`

`PlayerBedEnterEvent` remains in the existing listener. The handler asks `BloodMoonManager` whether the event is active in the player's exact world, cancels the attempt, sends the configured message, and optionally calls `setTimeSinceRest(0)`.

## Persistence and Restart Flow

`BloodMoonData.yml` stores the weather snapshot, its world UUID, and the hidden scare's one-shot flag beside the existing Blood Moon state.

Start flow:

1. Detect the transition from inactive to active.
2. Capture and persist the original weather before changing the world.
3. Apply configured storm and thunder.
4. Choose the first sound interval.

Restart flow:

1. Load the existing Blood Moon state and weather snapshot.
2. Resolve the saved world UUID.
3. Reapply the configured weather without replaying the Blood Moon start sound.
4. Choose a fresh randomized sound interval.
5. Preserve whether the hidden scare already occurred.

End flow:

1. Restore the saved weather flags and durations when restoration is enabled.
2. Clear the snapshot and transient atmosphere state.
3. Persist the cleared state.
4. Continue existing boss-bar, zombie, and optional integration cleanup.

All termination paths, including natural dawn, forced expiry, `/xa stopbloodmoon`, and time changes, use this same end flow. Missing or invalid persisted world data causes a warning and safe cleanup rather than changing another world.

## Internal Easter Egg

On each normal atmosphere cue, there is a fixed 1% chance to select one eligible player and play `ENTITY_CREEPER_PRIMED` about three blocks behind them. No entity is spawned and no damage or block change can occur. The scare can happen at most once per Blood Moon, including across restarts.

This behavior is intentionally absent from `config.yml`, public documentation, the Modrinth changelog, and player-facing test guides. It remains documented here and covered by automated tests so it can be maintained safely.

## Comment Cleanup

Java and bundled YAML comments will be reviewed across the plugin. Redundant section banners, issue labels such as `CRITICAL FIX`, implementation-history narration, and comments that merely restate the next line will be removed. Necessary comments and Javadocs will be shortened and written naturally, especially where they explain lifecycle ordering, persistence, thread boundaries, or a non-obvious safety decision.

The cleanup must not change behavior outside the 1.7.0 feature work.

## Error Handling

- All Bukkit world, player, weather, sound, and event operations remain on the main thread.
- A missing active world makes the atmosphere heartbeat a no-op; it never falls back to a lobby or unrelated enabled world.
- Failure to save `BloodMoonData.yml` uses the existing atomic-writer error path and leaves the in-memory snapshot intact.
- Repeated heartbeat checks do not repeatedly log the same invalid configuration.
- Disabling weather during a configuration reload restores the snapshot immediately while keeping it available until the Blood Moon ends.
- Re-enabling weather during the same event reapplies it without overwriting the original snapshot.

## Verification

Automated tests will cover:

- Natural and forced starts use the exact active world.
- Lobby and unrelated enabled worlds receive no atmosphere or sleep behavior.
- Weather is captured once, reasserted only when needed, and restored on every end path.
- Restart recovery reapplies weather without replaying the start sound.
- Sound intervals stay within configured bounds.
- Invalid and empty sound lists fail safely.
- Sound cues target only players in the active world.
- Bed entry cancellation, message delivery, and both phantom-reset toggle states.
- The hidden scare's deterministic chance seam, one-player targeting, once-per-event limit, and persisted one-shot state.
- Configuration reload behavior during an active Blood Moon.

Release verification will run the complete Maven test suite and package the JAR. The test server checklist will cover forced and natural Blood Moons, weather restoration, restart resume, world isolation, sound timing, sleep blocking, and phantom-timer behavior. The hidden scare will not appear in the public test guide.
