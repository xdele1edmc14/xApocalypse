# ❓ FAQ & Troubleshooting

Common questions and fixes. When in doubt, set `debug-mode: true` in `config.yml`, run `/xa reload`, and watch the console — the plugin logs its spawn decisions in detail.

---

## Spawning

### Zombies aren't spawning at all
Work through the eligibility checklist (all must be true):

1. Your world is in **`enabled-worlds`** and is **not** in `lobby-worlds`.
2. You're in **Survival** mode (not Creative/Spectator).
3. You're **not flying or gliding**.
4. It's **night**, *or* a `day-spawn-chance` roll succeeded (daytime spawns are rare by default).
5. The server TPS isn't critically low — below **10 TPS** the watchdog **pauses** spawning.
6. Enough time has passed: the spawn cycle runs every `spawn-rate` ticks (default **1500** = 75 s).

With `debug-mode: true`, the console prints exactly why each player is skipped or chosen.

### "/rtp drops me somewhere and nothing spawns"
The spawner reads your **live** world each cycle. If `/rtp` or a Multiverse teleport lands you in a world that isn't in `enabled-worlds` (or is a `lobby-world`), spawning is correctly suppressed there. The debug log prints the exact world name it sees for you.

### Zombies spawn on tree leaves / inside hills / not at all in forests
This is handled: in normal and custom-generator worlds, the spawner uses the no-leaves terrain heightmap and accepts only its exposed surface. It does not scan down into a cave when water, obstructed terrain, or another unsafe top block is encountered. Tall grass and other passable plants can remain above a valid floor. In very rough terrain (water, ravines), some attempts are skipped after a retry; debug mode reports them as `no surface`.

### `/xa spawn` works but natural hordes don't (or vice-versa)
`/xa spawn` deliberately **bypasses** the GriefPrevention and mob-list gates and snaps to a surface, so it always works. Natural spawning honors those gates. If natural spawns are blocked everywhere, check `use-mob-blacklist`/`mob-list` and whether you're standing in a claim.

---

## Zombie Classes

### All my zombies look the same / have no nametags
- Make sure `zombie-classes.enabled: true`.
- Check `visuals.use-nametags: true`. With `nametag-always-visible: false`, names only show when you look directly at a zombie.

### Custom zombies are burning up in daylight
xApocalypse-managed zombies are protected from sunlight by default when `zombie-settings.allow-daylight-burning` is `false`. Ordinary classes still take Fire Aspect, lava and other fire damage; only Scorched is fully fireproof. Vanilla command, spawner and third-party zombies are not adopted by xApocalypse and still burn normally.

### Spitters / Bursters / Webbers don't do their thing
- **Spitter** only fires when its target is **1.5–18 blocks** away and visible — near-overlap, blocked line of sight, or excessive distance prevents a shot.
- **Burster** only starts its fuse once it targets a player within `burster.radius` (default 3 blocks).
- **Webber/Frost** abilities trigger **on hit**, with cooldowns.

---

## Blood Moon

### The Blood Moon won't start naturally
Natural Blood Moons trigger at **night** on days that are multiples of `interval-days` (default 14). Day count is based on world full-time. Use `/xa fbm` to force one for testing.

### A forced Blood Moon won't go away / time is stuck at night
The Blood Moon **forces night** while active. End it with `/xa sbm` (sets the world back to day), or wait for the forced duration to elapse. State is persisted in `BloodMoonData.yml`.

### Can players cheat a longer forced Blood Moon by restarting?
No. The forced start time and duration are persisted, so a restart resumes the original countdown rather than resetting it.

### The boss bar is stuck after a reload
Fixed — the previous bar is removed before a new one is created on reload. If you ever see a ghost bar, a `/xa reload` or relog clears it.

---

## Zombie Guts & Immunity

### Eating Zombie Guts does nothing
Use it by **right-clicking** while holding it — this works regardless of hunger level or game mode. (Eating also works in Survival when your hunger isn't full, but right-click is the reliable path.) Ensure `zombie-settings.zombie-guts.enabled: true`.

### My hearts didn't come back after immunity ended
This is handled in every expiry path — your original max health is restored when the timer ends, on relog, or on reload. Immunity timing uses the real-world clock, so it isn't disrupted by Blood Moon time changes.

### Zombie Guts never drops
Check `zombie-settings.zombie-guts.drop.enabled: true` and the `chance` value (default 2%). With `require-player-kill: true`, only zombies killed **by a player** can drop guts — grinders and traps won't.

---

## MythicMobs

### Mutants never spawn
- MythicMobs must be installed, and `mythicmobs.integration.mob-type` must **exactly** match an existing MythicMobs mob name (check the startup log for "Hooked in… verified").
- Mutants spawn during Blood Moons and via `/xa spawn mutant`. Outside a Blood Moon there's no periodic spawning.
- If `max-global-cap` is reached, no more spawn until some die.

---

## Performance

### TPS drops during big sieges
Lower `performance.max-total-zombies` and/or `apocalypse-settings.max-single-horde-size`. The watchdog culls the furthest managed zombies over the cap and pauses spawning below `performance.tps-threshold`.

### Zombies far away seem frozen
That's the **LOD system** — AI is disabled beyond 32 blocks from any player (and for all zombies in player-less worlds) to save CPU. It re-enables automatically as players approach.

---

## Still stuck?

1. Enable `debug-mode: true` and reload.
2. Reproduce the issue and read the console output.
3. Confirm your `config.yml` is valid YAML (indentation matters!).
4. Check that optional integrations (GriefPrevention, MythicMobs) are the versions you expect.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
