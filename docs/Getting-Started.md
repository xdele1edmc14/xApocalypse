# 🚀 Getting Started

This page covers everything you need to get xApocalypse running on your server.

---

## Requirements

| Requirement | Details |
|-------------|---------|
| **Server software** | Paper 26.2 |
| **API version** | `26.2` (declared in `plugin.yml`) |
| **Java** | **25** or newer |
| **GriefPrevention** | *Optional* soft-dependency — enables claim protection |
| **MythicMobs** | *Optional* soft-dependency — enables the Mutant boss |
| **PlaceholderAPI** | *Optional* soft-dependency — exposes `%xapocalypse_bloodmoon_days_left%`, `%xapocalypse_zombie_guts_duration%`, and `%xapocalypse_current_scent%` |

> All integrations are **soft dependencies**. The plugin loads and runs fine without them; the related features simply stay dormant.

---

## Installation

1. Download or build `xApocalypse-1.6.3.jar` (see [Building from Source](#building-from-source)).
2. Place the jar in your server's `plugins/` directory.
3. **Start the server once.** This generates the default configuration files.
4. Stop the server, edit the configs to taste (see the [Configuration Reference](Configuration-Reference.md)), and start again.
5. In-game, tweak and apply changes live with `/xa reload`.

---

## Generated Files

After the first launch, the plugin folder `plugins/xApocalypse/` contains:

| File | Owner | Purpose |
|------|-------|---------|
| `config.yml` | core | All gameplay settings — see the [Configuration Reference](Configuration-Reference.md) |
| `messages.yml` | `MessageManager` | All player-facing text — see [Localization](Localization.md) |
| `data.yml` | `ImmunityManager` | Persists active Zombie-Guts immunities across restarts |
| `BloodMoonData.yml` | `BloodMoonManager` | Persists Blood Moon state (so a restart can't reset or extend it) |

> `data.yml` and `BloodMoonData.yml` are **state files**, not settings. You normally never edit them by hand. They exist so that immunities and Blood Moons survive a server restart correctly.

---

## First Run Checklist

After installing, confirm the basics:

1. **Set your worlds.** Open `config.yml` and make sure `enabled-worlds` lists the worlds where the apocalypse should be active. By default it's `world`, `world_nether`, `world_the_end`.
2. **Stand in an enabled world in Survival mode.** Hordes only spawn for **survival-mode** players in **enabled** (non-lobby) worlds. Creative/Spectator players and anyone flying or gliding are skipped.
3. **Wait for a spawn cycle.** The spawner runs every `apocalypse-settings.spawn-rate` ticks (default **1500** = 75 seconds). At night you'll see zombies rise from the ground around you.
4. **Try a manual spawn.** As an OP, run `/xa spawn horde 10` to spawn a mixed horde immediately.
5. **Force a Blood Moon** to preview the event: `/xa fbm 5` (5 minutes).

> **Not seeing spawns?** Enable `debug-mode: true` in `config.yml`, reload, and watch the console — the spawner logs exactly why each player is or isn't eligible. See [FAQ & Troubleshooting](FAQ-and-Troubleshooting.md).

---

## Building from Source

The project uses Maven:

```bash
mvn clean package
```

The compiled jar is written to `target/xApocalypse-1.6.3.jar`.

---

### Next steps

- **[Commands & Permissions](Commands-and-Permissions.md)** — learn the `/xa` command tree
- **[Zombie Classes](Zombie-Classes.md)** — meet the enemy
- **[Configuration Reference](Configuration-Reference.md)** — tune every value

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
