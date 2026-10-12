# xApocalypse 1.7.0 Test Guide

Test these one by one. If something breaks, send the test number and the matching console lines.

## Setup

1. Use Paper 26.2 and Java 25.
2. Put `xApocalypse-1.7.0.jar` in the test server's `plugins` folder.
3. Remove the older xApocalypse JAR, then fully restart.
4. Run `/plugins` and make sure xApocalypse is green.

## Test 1: Forced Blood Moon weather

1. Go to the enabled survival world.
2. Run `/xa forcebloodmoon 5`.

**Pass:** the world gets a storm and thunder, the Blood Moon bar appears, and there are no repeating console errors.

## Test 2: Scary sounds

1. Stay in the active Blood Moon world.
2. Wait up to two minutes.

**Pass:** one of the configured vanilla atmosphere sounds plays. It should not spam every player on its own timer.

## Test 3: Sleep and phantom protection

1. Try to enter a bed during the Blood Moon.
2. Keep `bloodmoon.sleep.reset-phantom-timer: true` for this test.

**Pass:** sleeping is blocked, the configured message appears, and the attempt counts as rest for phantom prevention.

## Test 4: Lobby stays normal

1. Leave the Blood Moon running.
2. Move to the lobby or another unrelated world.
3. Try a bed there if that world allows sleeping.

**Pass:** no Blood Moon storm or atmosphere sounds follow you, and xApocalypse does not block the bed.

## Test 5: Weather restoration

1. Before a new test, set the survival world's weather to clear.
2. Force a Blood Moon, then run `/xa stopbloodmoon`.

**Pass:** the exact weather from before the event returns instead of staying stormy.

## Test 6: Restart resume

1. Force a five-minute Blood Moon.
2. Stop the server normally while it is still active.
3. Start it again and rejoin.

**Pass:** the same Blood Moon and storm resume, the timer was not reset, and the opening sound does not replay.

## Test 7: Natural Blood Moon

1. Set `bloodmoon.interval-days: 1`, then run `/xa reload`.
2. Let the enabled world reach night naturally.

**Pass:** the natural Blood Moon gets the same weather, sounds, and sleep rules as a forced one.

## Test 8: Reload during the event

1. Start another Blood Moon.
2. Run `/xa reload` while it is active.
3. Wait for another atmosphere sound, then stop the event.

**Pass:** the storm stays stable, sounds continue, sleep remains blocked, and the old weather still comes back at the end.

## Send this if it breaks

Send the test number, what happened, what should have happened, and the matching part of `logs/latest.log`.
