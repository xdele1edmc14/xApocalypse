# xApocalypse 1.6.4 Bug Test Guide

## What changed

Manual `/xa spawn` commands now retry bad terrain instead of eating the requested zombie count. Every admin shares one global ten-probe budget, so two people spamming commands cannot secretly turn it into 20, 30 or 100 probes in one tick. Mutants are even stricter: one expensive attempt per tick, with the full MythicMobs registry scan capped at once per second. The final message shows the real result like `2/2`.

## Quick test

1. Use Paper 26.2 and Java 25.
2. Stand near messy terrain with water, trees or ravines.
3. Run `/xa spawn miner 2 10` a few times.
4. Run `/xa spawn horde 100 20` and watch TPS/MSPT.
5. While that big job is still running, run another `/xa spawn` command.
6. Optional MythicMobs check: run `/xa spawn mutant 10 20` and watch how they arrive.

## You are good if

- Small requests keep trying and usually reach `2/2` when safe loaded ground exists.
- The result message always shows `spawned/requested`.
- Large requests arrive in batches and do not freeze the server.
- A second command from the same player says the first job must finish instead of stacking more work.
- If MythicMobs is installed, Mutants arrive one attempt per tick instead of one giant boss burst.

## It is cooked if

- A tiny request immediately says `0/2` beside usable ground.
- The message hides how many were requested.
- A large command causes one giant lag spike or loads new chunks.
- Repeating the command stacks multiple jobs for the same player.
- A big Mutant command dumps the entire count into one tick.

## Send this if it breaks

Send Paper version, Java version, xApocalypse version, the exact command, where you stood, what the final count said, and the matching part of `logs/latest.log`.
