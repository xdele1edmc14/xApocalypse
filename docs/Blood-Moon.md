# 🩸 Blood Moon

The Blood Moon is xApocalypse's signature event: on certain nights the apocalypse intensifies. Zombies become tougher, faster, deadlier, and more numerous, all tracked by a server-wide boss-bar countdown.

---

## When It Happens

A **natural** Blood Moon triggers automatically at **night** on every *X*-th in-game day, where *X* is `bloodmoon.interval-days` (default **14**). One in-game day is ~20 minutes, so the default is roughly every two weeks of play.

- The check runs against the first **enabled** world (not whatever world happens to be index 0), so multi-world setups with a lobby behave correctly.
- "Night" is defined as world time `13000`–`23000`.
- While active, the plugin **forces the world to stay at night** (snapping the time back if it drifts to day) until the event ends.

A **forced** Blood Moon can be started any time with `/xa forcebloodmoon [minutes]`.

---

## What Changes

During a Blood Moon, every newly spawned zombie has its base stats multiplied, and hordes grow larger:

| Multiplier | Config key | Default | Effect |
|------------|------------|--------:|--------|
| Health | `bloodmoon.multipliers.health` | `1.5` | Tankier zombies (also applies to Tank's flat HP) |
| Damage | `bloodmoon.multipliers.damage` | `1.3` | Harder hits |
| Speed | `bloodmoon.multipliers.speed` | `1.1` | Faster pursuit |
| Horde size | `bloodmoon.multipliers.horde-size` | `1.5` | Bigger hordes per spawn cycle |

Additionally, if **MythicMobs** is installed, the Blood Moon spawns a guaranteed [Mutant boss](MythicMobs-Integration.md) and starts a periodic Mutant spawn loop for the duration of the event.

## Atmosphere

The active Blood Moon world gets its own lightweight atmosphere:

- Configurable storm and thunder.
- A random configured vanilla sound every 60–120 seconds by default.
- No weather or atmosphere sounds in lobby or unrelated worlds.
- No extra per-player repeating tasks.

xApocalypse saves the world's storm, thunder, and weather timers before changing them. When the Blood Moon ends, it restores those exact values if `bloodmoon.atmosphere.weather.restore-previous-weather` is enabled.

Atmosphere can be disabled as a whole or split between weather and sounds under `bloodmoon.atmosphere` in `config.yml`.

## Sleep and Phantoms

Beds cannot be used in the active Blood Moon world while `bloodmoon.sleep.blocked` is enabled. Players in lobby and unrelated worlds can still sleep normally.

With `bloodmoon.sleep.reset-phantom-timer: true`, each blocked bed attempt resets that player's rest statistic. This keeps sleep disabled without causing extra phantom punishment.

---

## The Boss Bar

A red, segmented boss bar shows the time remaining. Its title is configurable and supports `%time%`:

```yaml
bloodmoon:
  bossbar-title: "&4&l☠ BLOOD MOON ☠ &cRemaining: %time%"
```

The bar is shown to players in **enabled worlds *and* lobby worlds** — so even players waiting in a lobby can see that a Blood Moon is raging. It's added/removed automatically as players change worlds, join, or quit.

---

## Forcing & Stopping

| Command | Effect |
|---------|--------|
| `/xa forcebloodmoon [minutes]` *(alias `/xa fbm`)* | Starts a Blood Moon for `[minutes]` (default from config; range 1–120). Sets the world to night if it's currently day. |
| `/xa stopbloodmoon` *(alias `/xa sbm`)* | Ends the active Blood Moon, clears the bar, and sets the world to day (`1000`) so it doesn't immediately re-trigger. |

A **forced** Blood Moon is timed by the **real-world clock** — its duration is the number of minutes you specified, independent of in-game time manipulation.

---

## Persistence

Blood Moon state is saved to **`BloodMoonData.yml`** in the plugin folder. This means:

- A server restart **mid-Blood-Moon** resumes the event and its atmosphere correctly (including the MythicMobs spawn loop) rather than dropping it or replaying the opening sound.
- A forced Blood Moon's original start time and duration are persisted, so you **can't extend a forced Blood Moon by restarting** before it expires.
- The original weather snapshot is kept across restarts, then restored when the event ends.
- Setting the world to day with `/time set day` while a Blood Moon is active will correctly **end** it (and stop the Mutant loop).

> You should never need to edit `BloodMoonData.yml` by hand — it's managed entirely by the plugin.

---

## Notifications

When a natural Blood Moon rises, players in enabled/lobby worlds receive a broadcast. Natural and forced starts also play the configurable vanilla sound under `bloodmoon.start-sound`. The messages are configurable in `messages.yml` under `bloodmoon:` (`start`, `end`, `natural-start`, `natural-end`, `force-start`, etc.). See [Localization](Localization.md).

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
