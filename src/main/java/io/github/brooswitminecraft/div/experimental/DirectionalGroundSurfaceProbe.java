package io.github.brooswitminecraft.div.experimental;

import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.GroundSurface;
import io.github.brooswitminecraft.div.GroundSurfaces;
import io.github.brooswitminecraft.div.DirectionalGroundSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction.Axis;
import org.slf4j.Logger;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe (independent review, PR #16: "demonstrate
 * real canonical oak-log blockstate/model/PNG loading on the dedicated runtime with non-baseline
 * directional output; synthetic injected assets and fallback tests do not prove classpath assets
 * available"). {@code BlockModelResolverTest}/{@code DirectionalGroundSurfaceTest} inject
 * synthetic fixture JSON precisely so they stay pure-Java unit tests (no live Minecraft registry
 * bootstrap) - which means they can prove the ROTATION/UV MATH is correct, but can never prove
 * the real {@code minecraft:oak_log} blockstate/model/PNG this ticket targets actually resolve
 * off this mod's real runtime classpath the way {@code CanonicalTextures}' own doc claims. This
 * probe places a REAL {@code oak_log} (every {@code axis} value) in a live server world and
 * drives it through the EXACT production entry point ({@link DirectionalGroundSurface#at}, the
 * same call {@code PhysicsBridge}'s wheel/terrain contact hook makes), not a re-derivation.
 *
 * <p>Same gating/placement convention as this package's other probes (e.g.
 * {@code GroundSurfaceTagProbe}): OFF by default, a designated-safe placement position, never
 * touches any real vehicle. A probe run that reports every case MISSING (texture/model
 * unresolved - {@link #textureResolved}/{@link #fieldLoaded} false) means classpath assets were
 * NOT actually reachable here (e.g. a plain {@code gradle test} JVM, which this ticket's own
 * investigation confirmed does NOT have the client resources jar on its classpath - only a real
 * {@code gradle runServer} does) - a real failure of the claim this probe exists to check, not a
 * tolerated "optional content absent" case the way {@code GroundSurfaceTagProbe}'s MISSING is.
 */
public final class DirectionalGroundSurfaceProbe {
    private DirectionalGroundSurfaceProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("div.experimentalDirectionalGroundSurfaceProbe");
    private static boolean ran = false;

    // Deliberately far from spawn/any other probe's fixtures, same convention as this package's
    // other world-touching probes - never under or near any real vehicle.
    private static final BlockPos POS = new BlockPos(0, 4, 50000);

    private record AxisCase(String axisName, Axis axis, Direction travelAlong, Direction travelAcross, boolean requireDirectional) {}

    private static final AxisCase[] CASES = {
        // axis=y (vertical, unrotated): world UP is the end-grain (rings) face, which is not
        // strongly linearly grained the way bark is - along/across need not differ here. This
        // case exists only to confirm the UNROTATED path resolves real assets end-to-end
        // (requireDirectional=false); the rotated cases below are what this review item targets.
        new AxisCase("y", Axis.Y, Direction.NORTH, Direction.EAST, false),
        // axis=z (laid, pointing north-south): end-grain faces north/south; the world UP face
        // (bark) is reached via a rotated model face - see BlockModelResolverTest. Travelling
        // NORTH (along the log's own length) is along-grain; travelling EAST (across it) is
        // cross-grain, and real bark texture must show a measurable difference between them.
        new AxisCase("z", Axis.Z, Direction.NORTH, Direction.EAST, true),
        // axis=x (laid, pointing east-west): same bark face, different rotation (x=90,y=90 vs
        // x=90,y=0) - travelling EAST is along-grain, NORTH is cross-grain.
        new AxisCase("x", Axis.X, Direction.EAST, Direction.NORTH, true),
    };

    public static void maybeRun(ServerLevel overworld) {
        if (!ENABLED || ran) return;
        ran = true;
        Block oakLog = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.withDefaultNamespace("oak_log")).orElse(null);
        if (oakLog == null) {
            LOGGER.error("[DIV directionalGroundSurfaceProbe] FAIL minecraft:oak_log not present in this registry at all - cannot run");
            return;
        }
        int pass = 0, fail = 0;
        for (AxisCase c : CASES) {
            BlockState state = oakLog.defaultBlockState().setValue(RotatedPillarBlock.AXIS, c.axis());
            overworld.setBlockAndUpdate(POS, state);
            // Re-read the state back from the live world (not the local `state` variable) - this
            // is what actually proves placement took effect, not merely that setBlockAndUpdate's
            // argument was well-formed.
            BlockState placed = overworld.getBlockState(POS);
            boolean placementOk = placed.getBlock() == oakLog && placed.getValue(RotatedPillarBlock.AXIS) == c.axis();

            // A tire always contacts a log from above: world UP is end-grain for a standing log
            // (axis=y) and bark for a laid one (axis=x/z) - either way, a real resolvable face.
            Direction contactFace = Direction.UP;
            var textureResolution = io.github.brooswitminecraft.div.BlockFaceTextures.textureFor(placed, contactFace);
            boolean textureResolved = textureResolution.isPresent();
            boolean fieldLoaded = textureResolved
                    && io.github.brooswitminecraft.div.CanonicalTextures.at(textureResolution.get().textureId()).isPresent();

            GroundSurface baseline = GroundSurfaces.at(overworld, POS);
            GroundSurface along = DirectionalGroundSurface.at(overworld, POS, contactFace,
                    0.5, 1.0, 0.5, c.travelAlong().getStepX(), c.travelAlong().getStepY(), c.travelAlong().getStepZ());
            GroundSurface across = DirectionalGroundSurface.at(overworld, POS, contactFace,
                    0.5, 1.0, 0.5, c.travelAcross().getStepX(), c.travelAcross().getStepY(), c.travelAcross().getStepZ());
            // "Non-baseline directional output": at least one of the two sampled directions must
            // actually differ from the MINECRAFT-508 isotropic baseline, and the two directions
            // must differ from EACH OTHER - both are required to show this is real per-face
            // texture evidence driving the result, not the safe fallback firing silently.
            boolean nonBaseline = along.frictionMultiplier() != baseline.frictionMultiplier()
                    || across.frictionMultiplier() != baseline.frictionMultiplier();
            boolean directional = along.frictionMultiplier() != across.frictionMultiplier();
            boolean ok = placementOk && textureResolved && fieldLoaded
                    && (!c.requireDirectional() || (nonBaseline && directional));

            LOGGER.info("[DIV directionalGroundSurfaceProbe] axis=" + c.axisName() + " placementOk=" + placementOk
                    + " textureResolved=" + textureResolved + " fieldLoaded=" + fieldLoaded
                    + " baselineFriction=" + baseline.frictionMultiplier()
                    + " alongGrainFriction=" + along.frictionMultiplier() + " crossGrainFriction=" + across.frictionMultiplier()
                    + " nonBaseline=" + nonBaseline + " directional=" + directional + " requireDirectional=" + c.requireDirectional()
                    + " result=" + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        }
        LOGGER.info("[DIV directionalGroundSurfaceProbe] COMPLETE pass=" + pass + " fail=" + fail + " total=" + CASES.length);
    }
}
