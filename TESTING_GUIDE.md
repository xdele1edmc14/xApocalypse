# xApocalypse Horde Engine Testing Guide

## Overview
This guide covers testing all the major fixes and improvements made to the xApocalypse Horde engine. Follow these steps to verify each feature works correctly.

## Prerequisites
- PaperMC server running 26.2
- Java 25
- xApocalypse plugin installed with updated configuration
- Test world with various terrain types
- Debug mode enabled in config.yml for better logging

## 1.7.0 Blood Moon Quick Check

1. Run `/xa forcebloodmoon 5` in an enabled survival world.
2. Confirm that world gets a storm and thunder. A lobby or unrelated world must stay unchanged.
3. Wait up to two minutes and confirm the configured vanilla atmosphere sounds play only in the active world.
4. Try to enter a bed. The attempt should be blocked with the configured message.
5. With `bloodmoon.sleep.reset-phantom-timer: true`, confirm the attempt resets the player's phantom timer.
6. Stop and restart the server during the event. The Blood Moon and its atmosphere should resume without replaying the opening sound.
7. Run `/xa stopbloodmoon`. The exact weather from before the event should return.
8. Run `/xa reload` during another Blood Moon and confirm the atmosphere remains stable.

These checks apply to both forced and natural Blood Moons.

## 1. Directional Spawning (Rear 160-degree Arc) Test

### Objective
Verify zombies only spawn behind the player in a strict 160° rear arc.

### Test Steps
1. **Enable Debug Mode**: Set `debug-mode: true` in config.yml
2. **Find an Open Area**: Large flat space with minimal obstacles
3. **Stand Still**: Face a specific direction (North, South, East, or West)
4. **Force Spawns**: Use `/xa spawn horde 10` to spawn zombies
5. **Observe Spawn Locations**:
   - Zombies should appear behind you (within 160° rear arc)
   - No zombies should spawn in front or to the sides
   - Check debug logs for spawn location coordinates

### Expected Results
- All zombies spawn within the rear 160° arc (80° spread on each side of directly behind)
- Dot product check prevents spawns with `dotProduct > -0.3`
- Spawn locations respect the configured radius (default 35 blocks)

### Troubleshooting
- If zombies spawn in front: Check the dot product calculation in `findSpawnLocation()`
- If no spawns occur: Verify spawn radius and light level settings
- Check debug logs for "Not in rear arc" messages

## 2. Visual Rising Effect Test

### Objective
Verify zombies rise from the ground instead of instant teleportation.

### Test Steps
1. **Find Suitable Terrain**: Flat ground with no underground blocks
2. **Force Zombie Spawn**: Use `/xa spawn zombie 1`
3. **Observe Animation**:
   - Zombie should spawn 1.5 blocks underground
   - Rise 0.5 blocks per tick over 3 ticks
   - Smoke and HAPPY_VILLAGER particles at ground level
   - Gravel breaking sound effect

### Expected Results
- Smooth rising animation over 2-3 ticks
- Particle effects at spawn location
- Sound effect plays once
- Zombie ends up exactly on surface

### Troubleshooting
- If instant spawn: Check `spawnZombieWithRisingEffect()` method
- If no particles: Verify Particle imports and effect names
- If zombie spawns in ground: Check underground location safety check

## 3. Performance & Safety Guards Test

### Objective
Verify TPS-based spawning pause and dynamic zombie caps.

### Test Steps

#### 3.1 TPS Monitoring
1. **Check Current TPS**: Use `/tps` command to see server performance
2. **Monitor Performance**: Watch console for PerformanceWatchdog messages
3. **Verify Paper API Usage**: Check that no CraftBukkit imports are used

#### 3.2 Dynamic Zombie Caps
1. **Check Config Settings**: Verify `performance.max-total-zombies: 300`
2. **Spawn Many Zombies**: Use `/xa spawn horde 50` multiple times
3. **Observe Culling**: When count exceeds 300, excess zombies should be removed
4. **Check Distance Prioritization**: Far zombies should be culled first

#### 3.3 TPS-Based Pause
1. **Create Lag**: Use methods to drop TPS below 18.5 (e.g., massive redstone, entity spam)
2. **Observe Spawning Pause**: New zombie spawning should stop
3. **Monitor Recovery**: When TPS recovers to 19.0+, spawning should resume

### Expected Results
- TPS monitoring uses `Bukkit.getTPS()[0]` (Paper API)
- Spawning pauses when TPS < 18.5
- Spawning resumes when TPS ≥ 19.0
- Zombie count never exceeds configured maximum
- Distant zombies prioritized for culling

### Troubleshooting
- If spawning doesn't pause: Check TPS threshold in `checkPerformance()`
- If CraftBukkit errors: Verify all imports use only Bukkit/Paper API
- If over-spawning: Check dynamic cap calculation in `countZombiesInWorld()`

## 4. Factory Pattern Test

### Objective
Verify the simplified zombie creation system works without Builder pattern.

### Test Steps
1. **Spawn Various Zombie Types**: Use `/xa spawn <type> 1` for each type
2. **Verify Type Assignment**: Check nametags show correct zombie class
3. **Test Random Assignment**: Spawn horde and observe type distribution
4. **Check Weight System**: Verify rarer types (Tank, Veteran) appear less frequently

### Expected Results
- All zombie types spawn correctly with assigned stats
- Factory pattern uses `assignZombieType()` and `applyZombieType()`
- No Builder pattern classes in codebase
- Weighted random distribution matches config.yml percentages

### Troubleshooting
- If types don't assign: Check `xApocalypseUtils.assignZombieType()`
- If wrong stats: Verify `applyZombieStats()` method
- If distribution off: Check weight calculation in `getRandomZombieType()`

## 5. Configuration Updates Test

### Objective
Verify new performance settings work correctly.

### Test Steps
1. **Check New Settings**: Verify these exist in config.yml:
   ```
   performance:
     max-total-zombies: 300
     tps-threshold: 15.0
     check-interval-ticks: 100
   ```
2. **Test Entity Cap**: Spawn a large horde past `max-total-zombies` and confirm the furthest zombies are culled
3. **Test TPS Threshold**: Verify spawning pauses below `tps-threshold` and resumes once TPS recovers

### Expected Results
- All new settings recognized and applied
- Spawn rate limited to configured value
- TPS threshold matches pause/resume behavior

## 6. Integration Test

### Objective
Test all systems working together under realistic conditions.

### Test Steps
1. **Start Blood Moon**: Use `/xa forcebloodmoon` to trigger event
2. **Spawn Large Horde**: Natural spawning + manual spawns
3. **Create Performance Stress**: Add entities, redstone, etc.
4. **Observe System Behavior**:
   - Directional spawning works under stress
   - Rising effects still play correctly
   - Performance system manages load
   - Factory pattern handles all types

### Expected Results
- All systems function together without conflicts
- Performance degrades gracefully under load
- Visual effects maintained even with many entities
- No console errors or exceptions

## Performance Benchmarks

### Expected Performance
- **Spawn Rate**: 5 zombies per tick maximum (configurable)
- **TPS Impact**: Minimal during normal operation
- **Memory Usage**: Stable with dynamic culling
- **Visual Effects**: Smooth animations without lag

### Stress Test
1. Spawn 500+ zombies
2. Monitor TPS should stay above 15
3. Verify culling keeps count under 300
4. Check spawning pauses when TPS drops below 18.5

## Common Issues & Solutions

### Issue: Zombies spawning in front
- **Cause**: Dot product check failing
- **Solution**: Verify `findSpawnLocation()` calculation

### Issue: No rising effect
- **Cause**: Particle/Sound import issues
- **Solution**: Check imports for Particle and Sound classes

### Issue: Performance system not working
- **Cause**: CraftBukkit dependencies still present
- **Solution**: Verify pom.xml only has Paper API dependency

### Issue: Configuration not loading
- **Cause**: New performance section missing
- **Solution**: Verify config.yml has performance section

## Final Verification Checklist

- [ ] Directional spawning works (160° rear arc only)
- [ ] Rising effect plays for all spawns
- [ ] Performance monitoring uses Paper API
- [ ] Dynamic zombie caps enforced
- [ ] TPS-based pause/resume functional
- [ ] Factory pattern creates all zombie types
- [ ] Configuration settings applied correctly
- [ ] No CraftBukkit imports in codebase
- [ ] No console errors during operation
- [ ] Integration test passes under stress

## Reporting Issues

If any test fails:
1. Check console for error messages
2. Verify configuration settings
3. Enable debug mode for detailed logging
4. Report specific test case that failed
5. Include server TPS and entity count in report

This comprehensive testing guide ensures all Horde engine improvements work correctly and maintain server performance.
