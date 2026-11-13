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
 *
 * <p>Layered Slopes compatibility (MINECRAFT-508 review, John): Layered Slopes (extracted from
 * Dynamic Terrain, MINECRAFT-511/499; registry namespace stays the pre-split {@code
 * dynamicterrain}, not {@code layeredslopes} - see that mod's own {@code LayeredSlopesMod} class
 * doc) registers each slope variant via {@code BlockBehaviour.Properties.ofFullCopy(base)}, which
 * DOES copy the base block's {@code SoundType} - but a sloped block is a brand-new block ID, so
 * it is NOT automatically a member of any tag the base block belongs to (tags are assigned by ID,
 * never inherited from copied Properties). Verified against the pinned 1.21.1 decompiled
 * {@code Blocks} registration which base-block {@code SoundType} each slope variant actually
 * copies, and three of them land on the WRONG {@link GroundSurface.SoundFamily} once this class's
 * fallback runs - not a hypothetical, a confirmed defect without the explicit tag entries below:
 * <ul>
 *  <li>{@code dynamicterrain:layered_dirt}/{@code layered_coarse_dirt} copy {@code Blocks.DIRT}/
 *  {@code Blocks.COARSE_DIRT}'s {@code SoundType.GRAVEL} (yes, vanilla dirt's own sound IS
 *  gravel) - unhandled, this falls to {@link GroundSurface.SoundFamily#GRAVEL_LIKE}, not DIRT.
 *  Fixed by listing both explicitly in {@code surface_dirt.json}.</li>
 *  <li>{@code dynamicterrain:layered_podzol} copies {@code Blocks.PODZOL}'s {@code SoundType.GRAVEL}
 *  (also gravel, not grass) - unhandled, this falls to GRAVEL_LIKE, not GRASS. Fixed by listing it
 *  explicitly in {@code surface_grass.json}.</li>
 *  <li>{@code dynamicterrain:layered_ice}/{@code layered_packed_ice} copy {@code Blocks.ICE}/
 *  {@code Blocks.PACKED_ICE}'s {@code SoundType.GLASS} (Minecraft has no dedicated "ice" sound
 *  family at all) - unhandled, this falls to {@code OTHER} i.e. the {@link GroundSurface#PAVEMENT}
 *  baseline fallback, which is exactly the dangerous silent-pavement-downgrade John flagged. Fixed
 *  by listing both explicitly in {@code surface_ice.json}.</li>
 * </ul>
 * Everything else Layered Slopes registers already resolves correctly on its own: {@code
 * layered_grass} ({@code LayeredGrassBlock} copies {@code GRASS_BLOCK}'s {@code SoundType.GRASS}
 * directly), {@code layered_mycelium} ({@code SoundType.GRASS}), {@code layered_gravel} ({@code
 * SoundType.GRAVEL}), {@code layered_sand}/{@code layered_red_sand} ({@code SoundType.SAND} ->
 * SAND_LIKE -> DIRT, the intended mapping), and every stone/terracotta/sandstone/clay/snow-family
 * slope variant - none of those five surfaces are claimed by this bounded ticket, so landing on
 * the documented {@code PAVEMENT} baseline fallback is the correct, intentional behavior, not a
 * gap. All of the above is sourced entirely from this mod's own data-pack tags (block IDs only) -
 * no build/runtime dependency on Layered Slopes or Dynamic Terrain is added, and the entries are
 * {@code "required": false} so a deployment without Layered Slopes installed loads cleanly.
 *
 * <p>{@code minecraft:dirt_path} (MINECRAFT-508 review, John): flagged for verification against
 * the pinned 1.21.1 jar rather than assumed. John's specific claim - that vanilla gives it {@code
 * SoundType.GRAVEL} - does NOT hold: the real decompiled registration
 * ({@code Blocks.DIRT_PATH}, {@code DirtPathBlock}) gives it {@code SoundType.GRASS}, so without
 * an explicit tag entry it would actually land on {@code GRASS_LIKE} -> {@link GroundSurface#GRASS},
 * not gravel. His underlying concern is still real, though: {@code dirt_path} was not listed in
 * ANY of this ticket's tags (vanilla's own {@code #minecraft:convertable_to_mud} composed into
 * {@code surface_dirt.json} is only dirt/coarse_dirt/rooted_dirt, not the path variant), so a worn
 * dirt road was silently resolving to GRASS instead of the intended DIRT. Fixed by listing
 * {@code minecraft:dirt_path} explicitly in {@code surface_dirt.json}.
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
            return GroundSurfaceOverrides.effective("pavement", GroundSurface.PAVEMENT);
        }
        GroundSurface selected = GroundSurface.select(state.is(PAVEMENT_TAG), state.is(ICE_TAG), state.is(GRAVEL_TAG), state.is(GRASS_TAG),
                state.is(DIRT_TAG), familyOf(state.getSoundType(level, pos, null)));
        return GroundSurfaceOverrides.effective(nameOf(selected), selected);
    }

    /** {@link GroundSurface#select} only ever returns one of the five named constants directly
     * (never a newly-constructed instance), so identity comparison is exact. */
    private static String nameOf(GroundSurface surface) {
        if (surface == GroundSurface.PAVEMENT) return "pavement";
        if (surface == GroundSurface.DIRT) return "dirt";
        if (surface == GroundSurface.GRASS) return "grass";
        if (surface == GroundSurface.GRAVEL) return "gravel";
        if (surface == GroundSurface.ICE) return "ice";
        throw new IllegalStateException("unknown GroundSurface constant: " + surface);
    }

    private static GroundSurface.SoundFamily familyOf(SoundType sound) {
        if (sound == SoundType.GRAVEL) return GroundSurface.SoundFamily.GRAVEL_LIKE;
        if (sound == SoundType.SAND || sound == SoundType.SOUL_SAND || sound == SoundType.SOUL_SOIL) return GroundSurface.SoundFamily.SAND_LIKE;
        if (sound == SoundType.GRASS || sound == SoundType.MOSS || sound == SoundType.ROOTED_DIRT
                || sound == SoundType.NYLIUM || sound == SoundType.MUD || sound == SoundType.PACKED_MUD) return GroundSurface.SoundFamily.GRASS_LIKE;
        return GroundSurface.SoundFamily.OTHER;
    }
}
