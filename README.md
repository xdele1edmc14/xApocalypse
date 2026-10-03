<p align="center">
  <img src="https://i.postimg.cc/MHGh5Snp/IMG-9018-removebg-preview.png" width="600" alt="xApocalypse Banner"/>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk"/>
  <img src="https://img.shields.io/badge/Paper-26.2-red?style=for-the-badge&logo=spigotmc"/>
  <img src="https://img.shields.io/badge/License-GPLv3-yellow?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/Version-1.6.3-brightgreen?style=for-the-badge"/>
</p>

<p align="center">
  <i>A brutal, customizable zombie apocalypse plugin for survival servers 🌑🧟</i><br>
  <b>12 zombie classes. Blood Moons. Scent tracking. Optimized for performance.</b>
</p>

<p align="center">
  <a href="docs/Home.md">
    <img src="https://img.shields.io/badge/%F0%9F%93%96%20%20READ%20THE%20WIKI-8B0000?style=for-the-badge&labelColor=000000&color=8B0000" height="42" alt="Read the Wiki"/>
  </a>
</p>

<p align="center">
  <sub>📖 Full documentation — classes, Blood Moons, scent, config & more — lives in the <a href="docs/Home.md"><b>Wiki</b></a>.</sub>
</p>

---

## ⚙️ Core Features

* **🧟 12 Zombie Classes** — Every horde zombie is rolled from a weighted pool of specialized classes, each with its own AI, stats, and colored nametag. → *[Wiki: Zombie Classes](docs/Zombie-Classes.md)*

* **🌑 Dynamic Horde Spawning** — Hordes spawn around players on a configurable timer, with surface-snapping (no suffocating in hills or void-dropping) and an optional ground-rise animation. → *[Wiki: Horde Spawning](docs/Horde-Spawning.md)*

* **🩸 Blood Moon Events** — Every *X* in-game days the night turns hostile: zombie health, damage, speed, and horde size are all multiplied, with a server-wide boss bar countdown. Can be triggered or stopped manually. → *[Wiki: Blood Moon](docs/Blood-Moon.md)*

* **👃 Scent Tracking** — Player activity (sprinting, killing) builds a *scent* that attracts larger hordes. Scent decays over time, so staying quiet keeps you safer. → *[Wiki: Scent System](docs/Scent-System.md)*

* **🫀 Zombie Guts** — A rare drop that can be consumed for 10 minutes of zombie immunity, at the cost of reduced max health. → *[Wiki: Zombie Guts & Immunity](docs/Zombie-Guts-and-Immunity.md)*

* **🐉 MythicMobs Integration** — Optionally spawns a capped, server-wide "Mutant" boss mob near players using your own MythicMobs definition. → *[Wiki: MythicMobs Integration](docs/MythicMobs-Integration.md)*

* **🔌 PlaceholderAPI Support** — Exposes live Blood Moon, Zombie Guts, and scent values for scoreboards, tab lists, chat, and other PlaceholderAPI-aware plugins. → *[Wiki: PlaceholderAPI](docs/PlaceholderAPI.md)*

* **🛡️ GriefPrevention Support** — Natural horde zombies will not spawn inside protected claims. → *[Wiki: Worlds & Integrations](docs/Worlds-and-Integrations.md)*

* **🚦 Performance Guards** — Global entity caps, per-tick spawn rate limiting, and a TPS watchdog that throttles spawning when the server lags. → *[Wiki: Performance & LOD](docs/Performance-and-LOD.md)*

* **🎨 Visuals & Localization** — Color-coded nametags and a fully translatable `messages.yml` supporting both **MiniMessage** (`<gradient>`, `<rainbow>`, `<#hex>`) and legacy (`&a`, `§c`) formatting. → *[Wiki: Localization](docs/Localization.md)*

---

## 🧟 Zombie Classes

Horde zombies are assigned a class from a weighted pool (configurable under `zombie-classes.weights`).

| Class | Role |
|-------|------|
| ⚔ **Swarmer** | Basic zombie — the bulk of the horde. |
| ⚡ **Runner** | Fast and fragile (reduced HP, higher speed). |
| ⛏ **Miner** | Breaks through configured blocks to reach you. |
| ❤ **Nurse** | Heals nearby zombies on a cooldown. |
| ☠ **Spitter** | Ranged attacker that applies poison. |
| 🔥 **Scorched** | Sets targets on fire on hit. |
| ⚔ **Psychopath** | Berserker that charges with bonus damage and bleed. |
| ⛨ **Tank** | High HP and knockback resistance. |
| 🕸 **Webber** | Places cobwebs to slow and trap you. |
| 💣 **Burster** | Explodes when it gets close (with a fuse warning). |
| ❄ **Frost** | Slows targets on hit. |
| ★ **Veteran** | A permanent, upgraded zombie with bonus attack. |

> Per-class behavior (break delays, heal radius, projectile cooldowns, explosion power, etc.) is tunable in `config.yml`.

---

## 🎮 Commands

Everything lives under one root command: **`/xapocalypse`**, with aliases **`/xa`** and **`/zombie`**.

| Command | Description | Permission |
|---------|-------------|------------|
| `/xa help` | Show the (permission-aware) command list | — |
| `/xa spawn <type\|horde\|mutant> [count] [radius]` | Spawn zombies of a class, a mixed horde, or the MythicMobs mutant | `xapocalypse.command.spawn` |
| `/xa item <item> [player]` | Give a special item (e.g. `zombie_guts`) | `xapocalypse.admin` |
| `/xa forcebloodmoon [minutes]` *(alias `fbm`)* | Force a Blood Moon (default duration from config, max 120 min) | `xapocalypse.admin` |
| `/xa stopbloodmoon` *(alias `sbm`)* | End the active Blood Moon | `xapocalypse.admin` |
| `/xa reload` | Reload `config.yml` and `messages.yml` | `xapocalypse.admin` |

### Permissions

| Node | Default | Grants |
|------|---------|--------|
| `xapocalypse.admin` | `op` | All admin sub-commands (reload, item, blood moon control) |
| `xapocalypse.command.spawn` | `op` | Spawning zombies via `/xa spawn` |

---

## 🛠️ Configuration

A trimmed overview of `config.yml`. See the file itself for inline comments on every option.

```yaml
debug-mode: false

# Worlds where horde spawning is active
enabled-worlds:
  - world
  - world_nether
  - world_the_end

# Spawning + bosses disabled here, but boss bars/other systems still work
lobby-worlds:
  - lobby

hooks:
  griefprevention:
    enabled: true
    prevent-spawning-in-claims: true

# Weighted pool of zombie classes (should total ~1.0) + per-class tuning
zombie-classes:
  enabled: true
  weights:
    SWARMER: 0.35
    RUNNER: 0.18
    MINER: 0.10
    # ... NURSE, SPITTER, SCORCHED, PSYCHOPATH, TANK, WEBBER, BURSTER, FROST

# Player activity attracts bigger hordes
scent-system:
  enabled: true
  max-scent: 100.0
  sprint-add: 1.5
  kill-add: 0.8
  decay-amount: 1.2
  decay-interval-seconds: 4

# Base stats for a plain zombie (before class/blood moon modifiers)
zombie-settings:
  health: 25.0
  damage: 6.0
  speed: 0.32
  allow-baby-zombies: false
  allow-zombie-villagers: true
  zombie-guts:
    enabled: true
    drop:
      enabled: true
      chance: 0.02            # 2% per eligible kill
      require-player-kill: true

# Horde spawning behavior
apocalypse-settings:
  spawn-rate: 1500            # Ticks between spawn cycles
  spawn-radius: 35
  base-horde-size: 6
  horde-variance: 4
  max-single-horde-size: 30
  day-spawn-chance: 0.02      # 2% chance to spawn during the day
  rising-animation: true
  use-mob-blacklist: true
  mob-list: [SKELETON, WITHER_SKELETON, SPIDER, CREEPER]

# Blood Moon
bloodmoon:
  enabled: true
  interval-days: 14
  bossbar-title: "&4&l☠ BLOOD MOON ☠ &cRemaining: %time%"
  multipliers:
    health: 1.5
    damage: 1.3
    speed: 1.1
    horde-size: 1.5
  force-duration-minutes: 10
  start-sound:
    enabled: true
    name: ENTITY_WITHER_SPAWN
    volume: 1.0
    pitch: 0.7

# Performance guards
performance:
  max-total-zombies: 300
  tps-threshold: 15.0
  check-interval-ticks: 100

# Optional MythicMobs "Mutant" boss
mythicmobs:
  integration:
    mob-type: "ExampleMutantBoss"   # replace with any mob name from your MythicMobs config
    max-global-cap: 15
    spawn-chance: 0.05
    spawn-tick-interval: 100
    spawn-radius: { min: 20, max: 40 }
    spawn-sound:
      enabled: true
      name: ENTITY_ENDER_DRAGON_GROWL
      volume: 1.0
      pitch: 0.8
      radius: 48.0
```

---

## 📦 Requirements & Installation

* **Server:** **Paper 26.2**
* **Java:** **25+**
* **Soft dependencies (optional):** [GriefPrevention](https://www.spigotmc.org/resources/griefprevention.1884/), [MythicMobs](https://www.spigotmc.org/resources/mythicmobs.5702/), [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)

1. Drop `xApocalypse-1.6.3.jar` into your server's `plugins/` folder.
2. Start the server once to generate `config.yml` and `messages.yml`.
3. Edit the configs to taste and run `/xa reload`.

### Building from source

```bash
mvn clean package
```

The compiled jar lands in `target/`.

---

## 📸 Screenshots & Showcase

<details>
  <summary>Click to view screenshots of xApocalypse in action!</summary>
  <br/>

### Daytime
<img src="https://i.imgur.com/ImSWNd1.png" alt="Daytime Scene" width="800"/>

### Nighttime
<img src="https://i.imgur.com/HuBR6TI.png" alt="Nighttime Scene" width="800"/>

### Blood Moon
<img src="https://i.imgur.com/PW2TPAS.png" alt="Bloodmoon Scene" width="800"/>

*Note: Images may not reflect the latest version.*

</details>

---

<p align="center">
  <i>Created by <b>xDele1ed</b> • Licensed under GPLv3</i>
</p>
