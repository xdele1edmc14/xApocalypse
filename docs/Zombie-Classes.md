# 🧟 Zombie Classes

Every zombie spawned by xApocalypse is assigned a **class** — a specialization with its own stats, equipment, colored nametag, and (for some) active AI behavior. Classes are toggled by `zombie-classes.enabled` and rolled from a **weighted random pool** defined under `zombie-classes.weights`.

All stats are derived from the three base values in `zombie-settings`:

| Base stat | Default | Config key |
|-----------|---------|------------|
| Health | `25.0` (12.5 hearts) | `zombie-settings.health` |
| Damage | `6.0` | `zombie-settings.damage` |
| Speed | `0.32` | `zombie-settings.speed` |

During a [Blood Moon](Blood-Moon.md) these base values are multiplied before the per-class modifiers are applied.

> 🔥 **Fire rules:** xApocalypse-managed zombies are protected from sunlight by default. Ordinary classes still take Fire Aspect, lava and other fire damage; only Scorched is fully fireproof. Set `zombie-settings.allow-daylight-burning: true` if managed zombies should burn in daylight.

---

## Spawn Weights

The weights below are the shipped defaults. They should total roughly `1.0`; a higher weight means that class is more common. `VETERAN` is **excluded** from the random pool (see [Special Classes](#special-classes)).

| Class | Weight | Share |
|-------|-------:|------:|
| 🗡 Swarmer | `0.35` | 35% |
| ⚡ Runner | `0.18` | 18% |
| ⛏ Miner | `0.10` | 10% |
| ❤ Nurse | `0.07` | 7% |
| ☠ Spitter | `0.07` | 7% |
| 🕸 Webber | `0.05` | 5% |
| 🔥 Scorched | `0.04` | 4% |
| 💣 Burster | `0.04` | 4% |
| ❄ Frost | `0.04` | 4% |
| 🗡 Psychopath | `0.03` | 3% |
| 🛡 Tank | `0.03` | 3% |

---

## Standard Classes

### 🗡 Swarmer
*The backbone of every horde.* Standard base stats with no special abilities — Swarmers exist to overwhelm you with numbers.

- **Stats:** base health / base damage / base speed
- **AI:** none

### ⚡ Runner
*Fast and fragile.* Runners close distance quickly but go down fast.

- **Health:** `× 0.75` (`zombie-classes.runner.health-multiplier`)
- **Damage:** `× 0.9`
- **Speed:** `0.38` (`zombie-classes.runner.speed`) — faster than the base `0.32`
- **AI:** none

### ⛏ Miner
*Digs through your defenses.* Miners break blocks between themselves and their target to reach you.

- **Stats:** base health / base damage / base speed
- **AI:** checks the actual adjacent feet/head obstructions toward its target and breaks one on a cooldown of `break-delay-ticks` (default **30 ticks**).
  - When a nearby target is between `tower-min-height` and `tower-max-height` blocks above it, the Miner attacks the supporting block beneath that target.
  - Only breaks blocks listed in `zombie-classes.miner.breakables` (defaults include common terrain, tower, glass, and plank materials).
  - **Never** breaks bedrock, air, or blocks inside a GriefPrevention claim.
  - `drop-items: true` makes broken blocks drop as items; `false` deletes them.

### ❤ Nurse
*The support unit.* Nurses keep the horde alive — kill them first.

- **Health:** base · **Damage:** `× 0.7`
- **AI:** every **3 seconds**, heals nearby damaged zombies (within ~5 blocks) for **+4 HP** each, with heart particles and a villager "yes" sound.

> The shipped config exposes finer tuning under `zombie-classes.nurse` (`heal-radius`, `heal-amount-hp`, `interval-seconds`, `max-targets-per-tick`).

### ☠ Spitter
*Ranged poison.* Spitters hang back and lob acid at you.

- **Health:** `× 0.85` · **Melee damage:** `× 0.8`
- **AI:** fires from **1.5–18 blocks**, backs away inside its preferred range, and only spends its cooldown when it has line of sight and launches a projectile.
- **On hit:** deals `impact-damage` immediately and applies **Poison** for `poison-duration-seconds` at `poison-level` (defaults: 2 HP plus Poison II for 6 s), with slime particles and a splash sound.

### 🔥 Scorched
*Wreathed in flame.* Fully fire-immune and unsettling to be near.

- **Stats:** base health / base damage / base speed
- **AI:** emits flame particles; nearby **survival-mode** players get cosmetic flame + smoke particles (visual only — see note).
- **Immunity:** fully immune to fire/lava damage.

### 🗡 Psychopath
*The berserker.* Gets deadlier as it gets hurt.

- **Health:** base · **Damage:** base **+ `attack-bonus`** (default **+2.0**)
- **AI:** when dropped **below 50% HP**, enrages — gains **Strength II + Speed II** for 5 seconds (10-second cooldown) with angry-villager particles and a sound cue.

### 🛡 Tank
*A walking wall.* High health, armored, hard to knock back.

- **Health:** `50.0` (`zombie-classes.tank.health`) — also multiplied during a Blood Moon
- **Damage:** `× 1.2` · **Speed:** `× 0.85`
- **Knockback resistance:** `0.6` (`zombie-classes.tank.knockback-resistance`)
- **Equipment:** iron chestplate

### 🕸 Webber
*Traps you in place.* Holds string and snares its victims.

- **Stats:** base health / base damage / base speed · **Equipment:** string (off-hand)
- **On hit:** places **`web_count`** (default **3**) cobwebs around the victim, on a **7-second** cooldown. The webs auto-clear after a configurable delay (`cleanup_delay_seconds`, ~5 s).

### 💣 Burster
*The suicide bomber.* Detonates when it reaches you.

- **Health:** `× 0.8` · **Damage:** `× 0.5` · **Speed:** `× 0.6` (slow but deadly)
- **AI:** when it targets a player within **`radius`** (default **3.0** blocks), it starts a **fuse** of `fuse_ticks` (default **30 ticks** = 1.5 s), glowing/blinking as it counts down, then triggers an explosion of power `power` (default **3.0**).
- The fuse is safely cancelled if the Burster dies before detonating.

### ❄ Frost
*Slows you to a crawl.* Wears a tell-tale aqua-dyed chestplate.

- **Stats:** base health / base damage / base speed · **Equipment:** aqua leather chestplate
- **On hit:** applies **Slowness** for `duration_ticks` (default **100 ticks** = 5 s) at `slowness_level` (default **2**).

---

## Special Classes

The Veteran is **never** rolled from the random spawn pool.

### ★ Veteran
*A zombie that has tasted blood.* Created when a zombie **kills a player** — it gets promoted on the spot.

- **Health:** base **+ `add-health`** (default +0)
- **Damage:** base **+ `attack-bonus`** (default **+4.0**) · **Speed:** `× 1.1`
- **Persistence:** controlled by `zombie-classes.veteran.permanent` and `persist`. When permanent, the upgrade sticks for the rest of the zombie's life.

> Only a genuine **player kill** promotes a zombie. Killing animals or other mobs near a zombie will **not** create a Veteran (an anti-farm guard).

---

## NORMAL (fallback)

`NORMAL` is the plain vanilla-stat xApocalypse zombie used as a fallback — e.g. when `zombie-classes.enabled` is `false`, or if the spawn weights are misconfigured to total zero. It follows the same sunlight setting as other managed zombies but has no full fire immunity.

---

## Nametags

Each class shows a color-coded nametag above the zombie. Visibility is controlled by:

```yaml
visuals:
  use-nametags: true
  nametag-always-visible: true   # false = only visible when looked at
```

Each class's nametag label and color are defined in the plugin. Other player-facing text (commands, Blood Moon, immunity) is fully translatable via `messages.yml` — see [Localization](Localization.md).

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
