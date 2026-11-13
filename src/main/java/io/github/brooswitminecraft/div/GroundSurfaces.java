package io.github.brooswitminecraft.div;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MINECRAFT-508: Minecraft-typed glue that resolves a live block to a {@link GroundSurface} -
 * data-driven tags (configurable without a code change), then a {@code SoundType}-family
 * fallback, then {@link GroundSurface#PAVEMENT} as the documented baseline fallback for a
 * genuinely unsupported surface. Never touches a texture (that's the deferred per-pixel/16x16
 * work), so the same block always returns the same result regardless of resource pack or whether
 * running headless on a dedicated server. Kept apart from {@link GroundSurface} so the actual
 * priority decision stays unit-testable without bootstrapping Minecraft's registries.
 */
public final class GroundSurfaces {
    private GroundSurfaces() {}

    private static final String MODID = "dynamicimmersivevehicles";
    public static final TagKey<Block> PAVEMENT_TAG = tag("surface_pavement");
    public static final TagKey<Block> DIRT_TAG = tag("surface_dirt");
    public static final TagKey<Block> GRASS_TAG = tag("surface_grass");
    public static final TagKey<Block> GRAVEL_TAG = tag("surface_gravel");
    public static final TagKey<Block> ICE_TAG = tag("surface_ice");

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MODID, path));
    }

    public static GroundSurface at(LevelReader level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return GroundSurface.PAVEMENT;
        }
        return GroundSurface.select(state.is(PAVEMENT_TAG), state.is(ICE_TAG), state.is(GRAVEL_TAG), state.is(GRASS_TAG),
                state.is(DIRT_TAG), familyOf(state.getSoundType(level, pos, null)));
    }

    private static GroundSurface.SoundFamily familyOf(SoundType sound) {
        if (sound == SoundType.GRAVEL) return GroundSurface.SoundFamily.GRAVEL_LIKE;
        if (sound == SoundType.SAND || sound == SoundType.SOUL_SAND || sound == SoundType.SOUL_SOIL) return GroundSurface.SoundFamily.SAND_LIKE;
        if (sound == SoundType.GRASS || sound == SoundType.MOSS || sound == SoundType.ROOTED_DIRT
                || sound == SoundType.NYLIUM || sound == SoundType.MUD || sound == SoundType.PACKED_MUD) return GroundSurface.SoundFamily.GRASS_LIKE;
        return GroundSurface.SoundFamily.OTHER;
    }
}
