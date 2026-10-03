package com.deleted.xapocalypse;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UndeadSpawnerTest {

    @Test
    void findsGroundBelowPassableVegetationUsingSurfaceHeightmap() {
        World world = mockWorld(World.Environment.NORMAL);
        Block air = block(Material.AIR, false, true, false);
        Block grass = block(Material.SHORT_GRASS, false, true, false);
        Block ground = block(Material.GRASS_BLOCK, false, false, true);
        when(world.getHighestBlockYAt(10, 20, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(64);
        when(ground.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(grass);
        when(grass.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(ground.getLocation()).thenReturn(new Location(world, 10, 64, 20));
        stubColumn(world, air, grass, ground, 65, 64);

        Location result = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(world, 10.2, 65, 20.8));

        assertNotNull(result);
        assertEquals(10.5, result.getX());
        assertEquals(65.0, result.getY());
        assertEquals(20.5, result.getZ());
        verify(world).getHighestBlockYAt(10, 20, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        verify(world, never()).getHighestBlockAt(any(Location.class));
    }

    @Test
    void acceptsSolidNonOccludingGeneratorSurface() {
        World world = mockWorld(World.Environment.NORMAL);
        Block air = block(Material.AIR, false, true, false);
        Block path = block(Material.DIRT_PATH, false, false, true);
        when(world.getHighestBlockYAt(4, 7, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(79);
        when(path.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(air.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(path.getLocation()).thenReturn(new Location(world, 4, 79, 7));
        stubColumn(world, air, null, path, Integer.MIN_VALUE, 79);

        Location result = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(world, 4.5, 80, 7.5));

        assertNotNull(result);
        assertEquals(80.0, result.getY());
    }

    @Test
    void rejectsBlockedHeadroomAndLeafCanopies() {
        World blockedWorld = mockWorld(World.Environment.NORMAL);
        Block air = block(Material.AIR, false, true, false);
        Block obstruction = block(Material.COBWEB, false, false, false);
        Block ground = block(Material.GRASS_BLOCK, false, false, true);
        when(blockedWorld.getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(64);
        when(ground.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(obstruction);
        when(obstruction.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        stubColumn(blockedWorld, air, null, ground, Integer.MIN_VALUE, 64);

        Location blocked = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(blockedWorld, 0, 65, 0));
        assertNull(blocked);

        World leavesWorld = mockWorld(World.Environment.NORMAL);
        Block leaves = block(Material.OAK_LEAVES, false, false, true);
        when(leavesWorld.getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(64);
        when(leaves.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        stubColumn(leavesWorld, air, null, leaves, Integer.MIN_VALUE, 64);

        Location canopy = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(leavesWorld, 0, 65, 0));
        assertNull(canopy);
    }

    @Test
    void doesNotDescendIntoCaveWhenTerrainSurfaceIsInvalid() {
        World world = mockWorld(World.Environment.NORMAL);
        Block air = block(Material.AIR, false, true, false);
        Block water = block(Material.WATER, true, true, false);
        Block caveFloor = block(Material.STONE, false, false, true);
        when(world.getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(90);
        when(caveFloor.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(air.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        stubColumn(world, air, water, caveFloor, 90, 64);

        Location result = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(world, 0, 65, 0));

        assertNull(result);
        verify(world, never()).getBlockAt(0, 64, 0);
    }

    @Test
    void usesGeneratorSurfaceEvenWhenItIsFarAboveAnchorY() {
        World world = mockWorld(World.Environment.NORMAL);
        Block air = block(Material.AIR, false, true, false);
        Block ground = block(Material.GRASS_BLOCK, false, false, true);
        when(world.getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(130);
        when(ground.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(air.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(ground.getLocation()).thenReturn(new Location(world, 0, 130, 0));
        stubColumn(world, air, null, ground, Integer.MIN_VALUE, 130);

        Location result = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(world, 0, 70, 0));

        assertNotNull(result);
        assertEquals(131.0, result.getY());
    }

    @Test
    void retainsPlayerRelativeCavernSearchInNether() {
        World world = mockWorld(World.Environment.NETHER);
        Block air = block(Material.AIR, false, true, false);
        Block ground = block(Material.NETHERRACK, false, false, true);
        when(ground.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(air.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(air);
        when(ground.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        stubColumn(world, air, null, ground, Integer.MIN_VALUE, 64);

        Location result = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(world, 0, 65, 0));

        assertNotNull(result);
        assertEquals(65.0, result.getY());
        verify(world, never()).getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    @Test
    void rejectsUnloadedChunkBeforeReadingTerrain() {
        World world = mockWorld(World.Environment.NORMAL);
        when(world.isChunkLoaded(2, -2)).thenReturn(false);

        Location result = new UndeadSpawner(null, null)
                .getSurfaceSpawnLocation(new Location(world, 40.5, 70, -24.5));

        assertNull(result);
        verify(world, never()).getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    private static World mockWorld(World.Environment environment) {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return world;
    }

    private static Block block(Material material, boolean liquid, boolean passable, boolean solid) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.isLiquid()).thenReturn(liquid);
        when(block.isPassable()).thenReturn(passable);
        when(block.isSolid()).thenReturn(solid);
        return block;
    }

    private static void stubColumn(World world, Block defaultBlock, Block upperBlock,
                                   Block surfaceBlock, int upperY, int surfaceY) {
        when(world.getBlockAt(eq(10), anyInt(), eq(20))).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            if (y == upperY) return upperBlock;
            if (y == surfaceY) return surfaceBlock;
            return defaultBlock;
        });
        when(world.getBlockAt(eq(4), anyInt(), eq(7))).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            if (y == upperY) return upperBlock;
            if (y == surfaceY) return surfaceBlock;
            return defaultBlock;
        });
        when(world.getBlockAt(eq(0), anyInt(), eq(0))).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            if (y == upperY) return upperBlock;
            if (y == surfaceY) return surfaceBlock;
            return defaultBlock;
        });
    }
}
