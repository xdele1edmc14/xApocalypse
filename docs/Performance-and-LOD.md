# 🚦 Performance & LOD

A zombie apocalypse can spawn a *lot* of entities. xApocalypse ships with a **Performance Watchdog** and a **Level-of-Detail (LOD)** AI system to keep the siege smooth even under load.

---

## Entity Cap & Culling

The watchdog periodically (every `performance.check-interval-ticks`, default **100** ticks) counts xApocalypse-managed zombies in each enabled world. Vanilla zombies, NPC bases and third-party mobs do not consume this cap. If a world exceeds `performance.max-total-zombies` (default **300**), it **culls the excess**:

- Managed zombies are sorted by distance to the nearest player, and the **furthest are removed first**.
- Managed zombies **within 20 blocks** of a player are never culled — you won't see ones near you vanish.

This keeps xApocalypse's entity pressure bounded no matter how many players, hordes, scent multipliers, and Blood Moons are in play.

---

## TPS Watchdog

The watchdog reads server TPS (via Paper's API) each check interval and compares it against
`performance.tps-threshold` (default **15.0**):

| TPS | Behavior |
|-----|----------|
| **< `tps-threshold`** | New horde spawning is **paused** |
| **≥ `tps-threshold + 1.5`** | Spawning **resumes** (the pause threshold is capped at 18.5 so the full gap remains reachable at 20 TPS) |

While paused, the horde spawner task simply skips its work, so a struggling server stops digging itself deeper and gets a chance to recover. (Entity-count culling keeps running regardless.)

> On non-Paper servers without `getTPS()`, the watchdog defaults to assuming a healthy 20 TPS and relies on the entity cap instead.

---

## LOD: Distance-Based AI Throttling

Running full AI on hundreds of zombies is expensive — most of that cost is wasted on zombies far from any player. The LOD system addresses this:

- Zombies **farther than 32 blocks** from every player have their **AI disabled** (vanilla pathfinding included), dramatically cutting CPU use.
- When a player moves back within range, AI is **re-enabled** automatically.
- In a world with **no players at all**, every zombie's AI is disabled.
- Custom-class AI (Nurse healing, Miner digging, etc.) is ticked on a throttled schedule for distant zombies and at full rate for close ones.

Zombies that are mid **rise-animation** are skipped by the LOD system so the animation isn't interrupted.

The LOD tracking map is bounded (capped at 1000 entries with automatic eviction of dead/invalid zombies), so it can't grow without limit.

---

## Configuration

```yaml
performance:
  max-total-zombies: 300        # xApocalypse-managed zombie cap per world
  tps-threshold: 15.0           # pause new zombie spawns when TPS drops below this; resume once it recovers
  check-interval-ticks: 100     # how often the watchdog runs (100 ticks = 5 s)
```

> `max-total-zombies` is the single most important knob for a busy server — lower it if you see entity-related lag, raise it if your hardware can handle denser hordes. `tps-threshold` pauses *new* spawns while the server is below it (entity culling still runs).

---

## Tuning Tips

| Symptom | Try |
|---------|-----|
| TPS drops during big fights | Lower `max-total-zombies`; lower `apocalypse-settings.max-single-horde-size` |
| Hordes feel too thin | Raise `max-total-zombies` and/or horde sizes; lower `scent-system.scent-scale` |
| Too many spawn attempts in rough terrain | Reduce `spawn-radius`; expect some "skipped" debug lines near water/ravines |
| Spawning seems to stop entirely | Check console for low-TPS pause messages; verify the server isn't below `performance.tps-threshold` |

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
