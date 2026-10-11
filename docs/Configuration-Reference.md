# 🛠️ Configuration Reference

This page documents every key in `config.yml`. Values shown are the shipped defaults. After editing, run `/xa reload` to apply changes without a restart.

> Related text strings live in a separate file — see **[Localization (messages.yml)](Localization.md)**.

---

## General

```yaml
debug-mode: false   # true = verbose console logging (spawn decisions, scent, blood moon, etc.)
```

## Worlds

```yaml
enabled-worlds:       # worlds where the apocalypse is fully active
  - world
  - world_nether
  - world_the_end

lobby-worlds:         # spawning suppressed, but boss bars/other systems still work
  - lobby
```

See **[Worlds & Integrations](Worlds-and-Integrations.md)** for the full behavior matrix.

## Hooks

```yaml
hooks:
  griefprevention:
    enabled: true
    prevent-spawning-in-claims: true   # natural zombies/miners/mutants respect claims
```

---

## Zombie Classes

```yaml
zombie-classes:
  enabled: true

  # Weighted spawn pool (should total ~1.0). Higher = more common.
  # VETERAN is excluded from this pool.
  weights:
    SWARMER: 0.35
    RUNNER: 0.18
    MINER: 0.10
    NURSE: 0.07
    SPITTER: 0.07
    SCORCHED: 0.04
    PSYCHOPATH: 0.03
    TANK: 0.03
    WEBBER: 0.05
    BURSTER: 0.04
    FROST: 0.04
```

### Per-class tuning

```yaml
  miner:
    enabled: true
    break-delay-ticks: 30   # ticks between block breaks (20 = 1 s)
    drop-items: true        # broken blocks drop items
    tower-min-height: 1.5
    tower-max-height: 4.0
    tower-horizontal-range: 3.0
    breakables:             # which block types a Miner may break
      - DIRT
      - GRASS_BLOCK
      - COARSE_DIRT
      - STONE
      - COBBLESTONE
      - DEEPSLATE
      - COBBLED_DEEPSLATE
      - GLASS
      - TINTED_GLASS
      - OAK_PLANKS
      - SPRUCE_PLANKS
      - BIRCH_PLANKS
      - JUNGLE_PLANKS
      - ACACIA_PLANKS
      - DARK_OAK_PLANKS
      - MANGROVE_PLANKS
      - CHERRY_PLANKS
      - BAMBOO_PLANKS
      - CRIMSON_PLANKS
      - WARPED_PLANKS
      - PALE_OAK_PLANKS

  nurse:
    enabled: true
    heal-radius: 5.0          # blocks
    heal-amount-hp: 3.0       # HP healed per pulse (2 HP = 1 heart)
    interval-seconds: 3       # cooldown between heals
    max-targets-per-tick: 5   # max zombies healed at once

  runner:
    speed: 0.38               # movement speed (base = 0.32)
    health-multiplier: 0.75   # 75% of base HP

  tank:
    health: 50.0              # flat HP (also ×'d during a Blood Moon)
    knockback-resistance: 0.6 # 0.0 = none, 1.0 = immune

  spitter:
    enabled: true
    projectile-cooldown-seconds: 4
    poison-duration-seconds: 6
    poison-level: 2           # Poison II
    impact-damage: 2.0        # HP (2 HP = 1 heart)
    min-range: 1.5
    max-range: 18.0
    projectile-speed: 1.4
    retreat-range: 4.0
    retreat-speed: 0.22

  scorched:
    enabled: true
    fire-duration-seconds: 4

  veteran:
    permanent: true           # promotion sticks for the zombie's life
    attack-bonus: 4.0         # extra damage
    add-health: 0.0
    persist: true

  psychopath:
    attack-bonus: 2.0
    speed-bonus: 0.08
    rage-cooldown-seconds: 25
    bleed-duration-seconds: 3

  frost:
    slowness_level: 2
    duration_ticks: 100       # 100 ticks = 5 s

  webber:
    web_count: 3
    cleanup_delay: 40         # ticks before placed webs are removed
    # (cleanup_delay_seconds is also supported and takes precedence if present)

  burster:
    fuse_ticks: 30            # 30 ticks = 1.5 s
    radius: 3.0               # blocks; how close before the fuse starts
    power: 3.0                # explosion power
    break_blocks: true
```

See **[Zombie Classes](Zombie-Classes.md)** for what each value does in play.

---

## Scent System

```yaml
scent-system:
  enabled: true
  max-scent: 100.0
  sprint-add: 1.5             # scent per second while sprinting
  kill-add: 0.8               # scent per kill
  decay-amount: 1.2           # scent removed each decay tick
  decay-interval-seconds: 4   # how often scent decays
  scent-scale: 15.0           # higher = less impact on horde size
  # jump-add: 0.5             # scent per jump (code default; add to override)
```

See **[Scent System](Scent-System.md)**.

---

## Base Zombie Stats

```yaml
zombie-settings:
  health: 25.0   # base HP (20 = 10 hearts)
  damage: 6.0    # base attack damage
  speed: 0.32    # base movement speed
  allow-baby-zombies: false
  allow-zombie-villagers: true

  zombie-guts:
    enabled: true              # master switch for the immunity system
    drop:
      enabled: true            # toggle the rare death drop
      chance: 0.02             # 0.0–1.0 per eligible kill (0.02 = 2%)
      require-player-kill: true # only drop on a player's killing blow
```

See **[Zombie Guts & Immunity](Zombie-Guts-and-Immunity.md)**.

> `zombie-settings.allow-daylight-burning` defaults to `false`, protecting xApocalypse-managed zombies from sunlight. Vanilla zombies still burn normally, and ordinary xApocalypse classes still take Fire Aspect, lava, and other non-sunlight fire damage.

---

## Spawning

```yaml
apocalypse-settings:
  spawn-rate: 1500             # ticks between spawn cycles (20 = 1 s)
  spawn-radius: 35             # blocks around the player
  base-horde-size: 6           # base zombies per night horde
  horde-variance: 4            # random +0..+4
  max-single-horde-size: 30    # hard cap on one player's horde per cycle (after multipliers)
  day-spawn-chance: 0.02       # chance to spawn during the day (0.02 = 2%)
  day-horde-size: 2            # smaller base size for day hordes
  day-horde-variance: 2
  use-mob-blacklist: true      # true = blacklist mode; false = whitelist mode
  rising-animation: true       # zombies claw up out of the ground
  mob-list:                    # blocked (or, in whitelist mode, the only allowed) mobs
    - SKELETON
    - WITHER_SKELETON
    - SPIDER
    - CREEPER
```

See **[Horde Spawning](Horde-Spawning.md)**.

---

## Blood Moon

```yaml
bloodmoon:
  enabled: true
  interval-days: 14            # natural Blood Moon every X in-game days (1 day ≈ 20 min)
  bossbar-title: "&4&l☠ BLOOD MOON ☠ &cRemaining: %time%"
  multipliers:
    health: 1.5
    damage: 1.3
    speed: 1.1
    horde-size: 1.5
  force-duration-minutes: 10   # default duration for /xa forcebloodmoon
  start-sound:
    enabled: true
    name: ENTITY_WITHER_SPAWN   # any Bukkit Sound name
    volume: 1.0
    pitch: 0.7
```

See **[Blood Moon](Blood-Moon.md)**.

---

## Performance

```yaml
performance:
  max-total-zombies: 300       # xApocalypse-managed zombie cap per world
  tps-threshold: 15.0          # pause new spawns below this TPS; resume once it recovers
  check-interval-ticks: 100    # watchdog interval (100 ticks = 5 s)
```

See **[Performance & LOD](Performance-and-LOD.md)**.

---

## Visuals

```yaml
visuals:
  use-nametags: true             # color-coded names above zombies
  nametag-always-visible: true   # false = only visible when looked at
```

---

## MythicMobs

```yaml
mythicmobs:
  integration:
    mob-type: "ExampleMutantBoss"  # internal MythicMobs mob name (placeholder — use your own)
    max-global-cap: 15           # max alive server-wide (0 = no cap, not recommended)
    spawn-chance: 0.05           # per player per tick-interval (0.05 = 5%)
    spawn-tick-interval: 100     # ticks between rolls (100 = every 5 s)
    spawn-radius:
      min: 20
      max: 40
    spawn-sound:
      enabled: true
      name: ENTITY_ENDER_DRAGON_GROWL
      volume: 1.0
      pitch: 0.8
      radius: 48.0             # heard only in the Mutant's world within this distance
```

See **[MythicMobs Integration](MythicMobs-Integration.md)**.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
