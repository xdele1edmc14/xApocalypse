# xApocalypse 1.6.2 Bug Test Guide

Use Paper 26.2 and Java 25. Put `xApocalypse-1.6.2.jar` in `plugins/`, remove older xApocalypse jars, and start the server.

## Forced Blood Moon restart check

1. Run `/xa forcebloodmoon 2` late at night, after the world clock has passed tick 15400.
2. Stop the server normally before the two minutes finish.
3. Start it again right away.
4. Check `plugins/xApocalypse/BloodMoonData.yml`.
5. **Pass:** the Blood Moon resumes with the remaining real time, `forced` stays `true`, and it does not randomly end one second after startup.

## Save-file check

1. Eat Zombie Guts so immunity is active.
2. Force another two-minute Blood Moon.
3. Stop and restart normally.
4. Check `data.yml` and `BloodMoonData.yml`.
5. **Pass:** both files are valid YAML and not blank, Zombie Guts immunity returns, and the Blood Moon resumes until its original timer actually expires.

## Quick regression check

Run the Nurse/half-dead-zombie, PlaceholderAPI, and special-zombie tests from the previous 1.6.2 guide. **Pass:** nothing regresses and the console stays free of exceptions.
