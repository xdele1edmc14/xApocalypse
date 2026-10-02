<h1 align="center">📖 xApocalypse Wiki</h1>

<p align="center">
  <img src="https://i.postimg.cc/MHGh5Snp/IMG-9018-removebg-preview.png" width="420" alt="xApocalypse"/>
</p>

<p align="center">
  <b>The complete documentation for xApocalypse</b> — a hardcore zombie-horde survival plugin<br/>
  with 12 zombie classes, Blood Moons, scent tracking, a Zombie-Guts immunity system,<br/>
  optional MythicMobs bosses, and a performance watchdog.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-1.6.2-brightgreen?style=flat-square"/>
  <img src="https://img.shields.io/badge/API-26.2-red?style=flat-square"/>
  <img src="https://img.shields.io/badge/Java-25-orange?style=flat-square"/>
  <img src="https://img.shields.io/badge/License-GPLv3-yellow?style=flat-square"/>
</p>

---

## 🧭 Table of Contents

| Page | What's inside |
|------|---------------|
| **[Getting Started](Getting-Started.md)** | Requirements, installation, first run, generated files |
| **[Commands & Permissions](Commands-and-Permissions.md)** | Every sub-command, aliases, arguments, permission nodes |
| **[Zombie Classes](Zombie-Classes.md)** | All 12 classes — stats, abilities, AI behavior, spawn weights |
| **[Blood Moon](Blood-Moon.md)** | The Blood Moon event, multipliers, persistence, force/stop |
| **[Scent System](Scent-System.md)** | How player activity attracts bigger hordes |
| **[Zombie Guts & Immunity](Zombie-Guts-and-Immunity.md)** | The immunity item, the trade-off, the rare drop |
| **[MythicMobs Integration](MythicMobs-Integration.md)** | The Mutant boss, spawn caps, Blood Moon tie-in |
| **[PlaceholderAPI](PlaceholderAPI.md)** | Live placeholders for Blood Moons, Zombie Guts, and scent |
| **[Horde Spawning](Horde-Spawning.md)** | How, when, and where hordes spawn; the rise animation |
| **[Performance & LOD](Performance-and-LOD.md)** | Entity caps, TPS watchdog, AI throttling |
| **[Worlds & GriefPrevention](Worlds-and-Integrations.md)** | Enabled vs lobby worlds, claim protection |
| **[Configuration Reference](Configuration-Reference.md)** | Every `config.yml` key, annotated |
| **[Localization (messages.yml)](Localization.md)** | MiniMessage + legacy formatting, placeholders |
| **[FAQ & Troubleshooting](FAQ-and-Troubleshooting.md)** | Common problems and fixes |

---

## ⚡ The 60-Second Overview

xApocalypse turns an ordinary survival world into a relentless siege.

- **Hordes hunt you.** On a timer, zombies spawn around every survival-mode player — clawing out of the ground with a rise animation — and each one is rolled from a weighted pool of **specialized classes** with unique AI.
- **The more noise you make, the worse it gets.** Sprinting, jumping, and killing build a **scent** that swells the size of the hordes drawn to you.
- **Some nights are worse than others.** Every couple of weeks the **Blood Moon** rises: zombies hit harder, move faster, and come in greater numbers, all under a server-wide countdown bar.
- **There's a way out — at a price.** Rare **Zombie Guts** grant 10 minutes where zombies ignore you, but halve your max health while active.
- **Optional bosses.** With MythicMobs installed, a capped **Mutant** boss stalks players during Blood Moons.
- **It stays smooth.** A performance watchdog caps entities, culls the furthest zombies, and throttles distant AI so the siege never tanks your TPS.

New here? Start with **[Getting Started](Getting-Started.md)**, then skim **[Zombie Classes](Zombie-Classes.md)** to see what you're up against.

---

<p align="center"><i>← Back to the <a href="../README.md">README</a></i></p>
