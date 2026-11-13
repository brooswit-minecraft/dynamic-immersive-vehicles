package io.github.brooswitminecraft.div.experimental;

import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.GroundSurface;
import io.github.brooswitminecraft.div.GroundSurfaces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe (MINECRAFT-508 review, John: "an actual
 * mod-load/tag-resolution smoke test on Servy (real server boot, not just unit tests)"). Logs,
 * for a curated set of real block IDs - every vanilla representative of the five baseline
 * surfaces plus the Layered Slopes legacy {@code dynamicterrain:*} IDs this ticket's own fix
 * targets - what {@link GroundSurfaces} actually resolves once this mod's data-pack tags have
 * genuinely finished loading on a live, fully-started server, via the EXACT same production entry
 * point ({@link GroundSurfaces#atState}, which {@link GroundSurfaces#at} itself delegates to) -
 * not a re-derivation, and not merely feeding hand-picked booleans to the pure {@code select()}
 * unit tests. A block ID the test host doesn't have installed (expected for the optional
 * {@code dynamicterrain:*} IDs on a host without Layered Slopes) is reported MISSING, not FAIL -
 * this mod's own tags declare those entries {@code "required": false} for exactly that reason.
 *
 * <p>OFF by default (same gating convention as this package's other probes, e.g.
 * {@code AnchorFrameProbe}); never touches any real vehicle or world content. Comparison is by
 * {@link GroundSurface}'s own record {@code equals} (value, not identity) against the expected
 * named constant, which is robust even if a {@code dynamic-immersive-vehicles-surfaces.json}
 * happened to be present - though a smoke-test host with no such file configures nothing, so
 * identity would hold too in the normal case.
 */
public final class GroundSurfaceTagProbe {
    private GroundSurfaceTagProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("div.experimentalGroundSurfaceTagProbe");
    private static boolean ran = false;

    private record Case(String expectedName, GroundSurface expected, String blockId) {}

    private static final Case[] CASES = {
        new Case("pavement", GroundSurface.PAVEMENT, "minecraft:stone"),
        new Case("pavement", GroundSurface.PAVEMENT, "minecraft:white_concrete"),
        new Case("pavement", GroundSurface.PAVEMENT, "minecraft:stone_bricks"),
        new Case("dirt", GroundSurface.DIRT, "minecraft:dirt"),
        new Case("dirt", GroundSurface.DIRT, "minecraft:dirt_path"),
        new Case("dirt", GroundSurface.DIRT, "minecraft:farmland"),
        new Case("dirt", GroundSurface.DIRT, "minecraft:sand"),
        new Case("grass", GroundSurface.GRASS, "minecraft:grass_block"),
        new Case("grass", GroundSurface.GRASS, "minecraft:mycelium"),
        new Case("grass", GroundSurface.GRASS, "minecraft:podzol"),
        new Case("gravel", GroundSurface.GRAVEL, "minecraft:gravel"),
        new Case("ice", GroundSurface.ICE, "minecraft:ice"),
        new Case("ice", GroundSurface.ICE, "minecraft:packed_ice"),
        // Layered Slopes (MINECRAFT-511/499) legacy registry namespace - optional, "required": false.
        new Case("dirt", GroundSurface.DIRT, "dynamicterrain:layered_dirt"),
        new Case("dirt", GroundSurface.DIRT, "dynamicterrain:layered_coarse_dirt"),
        new Case("grass", GroundSurface.GRASS, "dynamicterrain:layered_podzol"),
        new Case("ice", GroundSurface.ICE, "dynamicterrain:layered_ice"),
        new Case("ice", GroundSurface.ICE, "dynamicterrain:layered_packed_ice"),
    };

    public static void maybeRun(ServerLevel overworld) {
        if (!ENABLED || ran) return;
        ran = true;
        int pass = 0, fail = 0, missing = 0;
        for (Case c : CASES) {
            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(c.blockId())).orElse(null);
            if (block == null) {
                LOGGER.info("[DIV groundSurfaceTagProbe] MISSING block=" + c.blockId()
                        + " (not installed on this host - expected for an optional dynamicterrain:* id without Layered Slopes)");
                missing++;
                continue;
            }
            GroundSurface resolved = GroundSurfaces.atState(overworld, BlockPos.ZERO, block.defaultBlockState());
            boolean ok = resolved.equals(c.expected());
            LOGGER.info("[DIV groundSurfaceTagProbe] block=" + c.blockId() + " expected=" + c.expectedName()
                    + " resolvedFrictionMultiplier=" + resolved.frictionMultiplier()
                    + " resolvedRollingResistanceMultiplier=" + resolved.rollingResistanceMultiplier()
                    + " result=" + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        }
        LOGGER.info("[DIV groundSurfaceTagProbe] COMPLETE pass=" + pass + " fail=" + fail + " missing=" + missing + " total=" + CASES.length);
    }
}
