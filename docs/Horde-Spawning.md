# 🌑 Horde Spawning

This page explains the core loop that makes the apocalypse happen: how, when, and where natural hordes appear around players.

---

## The Spawn Cycle

A repeating task runs every `apocalypse-settings.spawn-rate` ticks (default **1500** = 75 seconds). Each cycle:

1. Picks the first **enabled** world as the time reference.
2. Checks the time of day (see [Day vs Night](#day-vs-night)).
3. For every online player, decides eligibility and spawns a horde near each eligible one.

### Who gets a horde

A player is eligible **only if** all of these are true:

- ✅ They're in an **enabled** world (`enabled-worlds`)…
- ✅ …that is **not** a lobby world (`lobby-worlds`)
- ✅ They're in **Survival** mode (not Creative or Spectator)
- ✅ They're **not flying or gliding** (no Elytra cheese)

If `debug-mode` is on, the console logs the exact reason each player is skipped or chosen — invaluable for diagnosing "why aren't zombies spawning?".

---

## Day vs Night

| | Night | Day |
|---|-------|-----|
| **Spawn?** | Always (subject to eligibility) | Only if a `day-spawn-chance` roll succeeds |
| **Base horde size** | `base-horde-size` (6) | `day-horde-size` (2) |
| **Variance** | `horde-variance` (4) | `day-horde-variance` (2) |

By default daytime spawning is rare (`day-spawn-chance: 0.02` = 2% per cycle) and produces much smaller hordes, so daytime is a relative reprieve.

---

## Horde Size Math

For each eligible player, the horde size is computed as:

```
size = (base + random(0..variance)) × multiplier
```

Where `multiplier` combines:

- **Blood Moon** horde multiplier (`bloodmoon.multipliers.horde-size`, default 1.5) — only during a [Blood Moon](Blood-Moon.md).
- **Scent** multiplier (`1 + scent / scent-scale`) — see the [Scent System](Scent-System.md).

The result is then clamped twice:

1. `apocalypse-settings.max-single-horde-size` (default **30**) — the most one player can get in a single cycle.
2. `performance.max-total-zombies` (default **300**) — the global xApocalypse-managed zombie safety cap. Vanilla mobs, NPCs and third-party mobs do not consume it.

> The single-horde cap is applied **before** the spawn loop runs, which prevents scent × Blood Moon stacking from triggering hundreds of expensive surface-finding calls per player per cycle.

---

## Placement & the Rise Animation

Each zombie picks a random spot within `apocalypse-settings.spawn-radius` (default **35** blocks) of the player, then the spawner finds a valid surface to stand on:

- In normal and custom-generator worlds, it uses the `MOTION_BLOCKING_NO_LEAVES` terrain heightmap and accepts only that exposed surface. It never keeps scanning down into a cave when the surface is unsuitable.
- Solid paths, slabs, and stairs are accepted, and passable plants or snow may occupy the headroom; liquids, leaf canopies, bedrock, and obstructed spaces are rejected. Nether worlds use a player-relative cavern scan because their heightmap points at the bedrock roof.
- If no valid surface is found, it retries once with a fresh random offset, then gives up that slot. Debug mode reports surface, claim, and final spawn failures separately.

If `apocalypse-settings.rising-animation: true` (default), zombies **claw their way up out of the ground**:

- They start ~1.2 blocks below the surface with AI/gravity disabled and rise over about a second, kicking up block particles and digging sounds, then become fully active.
- Spawns are slightly staggered so a whole horde doesn't erupt on the exact same tick.
- A few safety limits apply: per-player and per-block cooldowns prevent spam, and at most ~50 rise animations run at once.

Set `rising-animation: false` to spawn zombies instantly instead.

---

## The Mob Blacklist / Whitelist

xApocalypse also filters world-generated mob spawns in enabled worlds via `onEntitySpawn`. This includes normal `NATURAL` spawns and initial `CHUNK_GEN` spawns created while a custom-generator chunk is populated:

```yaml
apocalypse-settings:
  use-mob-blacklist: true   # true = block listed mobs; false = whitelist (block everything NOT listed)
  mob-list:
    - SKELETON
    - WITHER_SKELETON
    - SPIDER
    - CREEPER
```

- **Blacklist mode** (`true`): the listed mobs are prevented from spawning naturally or during chunk generation.
- **Whitelist mode** (`false`): *only* the listed mobs may spawn naturally or during chunk generation; everything else is blocked.
- Entries are case-insensitive and accept either Bukkit names (`ZOMBIE`) or namespaced names (`minecraft:zombie`).

Additionally, non-zombie monsters spawning via `NATURAL` reason are blocked, keeping the night a **zombie** apocalypse. Plugin-spawned zombies (and `/xa spawn`) **bypass** this gate entirely.

Baby zombies and zombie villagers can be toggled:

```yaml
zombie-settings:
  allow-baby-zombies: false
  allow-zombie-villagers: true
```

---

## Related

- **[Zombie Classes](Zombie-Classes.md)** — what each spawned zombie can be
- **[Performance & LOD](Performance-and-LOD.md)** — the caps and throttles that keep spawning smooth
- **[Configuration Reference](Configuration-Reference.md)** — every spawn-related key

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
