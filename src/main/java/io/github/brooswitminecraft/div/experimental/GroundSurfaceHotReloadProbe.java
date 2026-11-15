package io.github.brooswitminecraft.div.experimental;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.GroundSurface;
import io.github.brooswitminecraft.div.GroundSurfaceOverrides;
import io.github.brooswitminecraft.div.GroundSurfaces;
import io.github.brooswitminecraft.div.Overrides;
import io.github.brooswitminecraft.div.PhysicsBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe for MINECRAFT-531's actual deliverable:
 * {@code GroundSurfaceOverrides.reload()} wired into the same bounded {@code OverridesHotReload}
 * mechanism {@code Overrides.reload()} already uses. {@code OverridesHotReload}'s own mtime-
 * polling loop (WHEN to check) has no more to test beyond its existing pure {@code isCheckTick}
 * unit coverage - what this probe exercises is the production EFFECT of a reload actually
 * happening, via the exact same entry points {@code OverridesHotReload} itself calls
 * ({@code GroundSurfaceOverrides.reload()}, {@code PhysicsBridge.reloadOverrides()}), the same
 * direct-call convention {@code FlightRotorRuntimeFixtureProbe} already established for
 * MINECRAFT-526's own vehicle-config live-reload evidence.
 *
 * <p>"Actual contacted-surface response" is read via {@code GroundSurfaces.atState} - the exact
 * same production ground-contact resolution entry point {@code GroundSurfaceTagProbe} (MINECRAFT-
 * 508) already established as valid live evidence, called against a real registered block's
 * {@code defaultBlockState()} rather than a hand-fed boolean - no vehicle/wheel fixture is needed
 * since this file's own override map is read fresh on every such call, with no per-vehicle
 * caching layer in between (unlike vehicle {@code Tuning}, see {@code GroundSurfaceOverrides}'s
 * own class doc).
 *
 * <p>Each phase below writes to BOTH the surfaces file and the vehicle-overrides file in the same
 * step, then reloads BOTH, specifically to exercise "independently detect both config files
 * without one masking the other": a failing edit to one file must never block or corrupt the
 * other's own successful reload, in either direction. Both files are snapshotted before this
 * probe writes anything and restored (content + a final live reload of the restored content) once
 * done, mirroring {@code FlightRotorRuntimeFixtureProbe}'s own {@code restoreConfigOnce} pattern -
 * reached from every exit path, success or failure, never skipped.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalGroundSurfaceHotReload=true} - OFF by default, never
 * touches any real vehicle or world content. Runs once per server lifetime, synchronously across
 * a handful of ticks (no physics settling needed - there is no spawned vehicle), then stops.
 */
public final class GroundSurfaceHotReloadProbe {
    private GroundSurfaceHotReloadProbe() {}

    private static final boolean ENABLED = Boolean.getBoolean("div.experimentalGroundSurfaceHotReload");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VEHICLE_KEY = "divtest:groundsurfacehotreload";

    private enum Phase { BASELINE, VALID_SURFACE_VALID_VEHICLE, INVALID_SURFACE_VALID_VEHICLE, VALID_SURFACE_INVALID_VEHICLE, RESTORE, DONE, FAILED }

    private static Phase phase = Phase.BASELINE;
    private static boolean started = false;
    private static boolean surfacesWasWritten = false, vehicleWasWritten = false;
    private static String originalSurfacesContent, originalVehicleContent;
    private static boolean originalSurfacesExisted, originalVehicleExisted;
    private static boolean restoreFailed = false;

    public static void maybeRun(ServerLevel overworld) {
        if (!ENABLED || phase == Phase.DONE || phase == Phase.FAILED) return;
        if (!started) { started = true; snapshotOriginals(); }
        try {
            switch (phase) {
                case BASELINE -> baseline(overworld);
                case VALID_SURFACE_VALID_VEHICLE -> validSurfaceValidVehicle(overworld);
                case INVALID_SURFACE_VALID_VEHICLE -> invalidSurfaceValidVehicle(overworld);
                case VALID_SURFACE_INVALID_VEHICLE -> validSurfaceInvalidVehicle(overworld);
                case RESTORE -> restore();
                case DONE, FAILED -> {}
            }
        } catch (RuntimeException ex) {
            LOGGER.error("[DIV groundSurfaceHotReload] FAILED - unexpected exception in phase " + phase, ex);
            phase = Phase.RESTORE;
        }
    }

    private static Path surfacesPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles-surfaces.json"); }
    private static Path vehiclePath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json"); }
    private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }

    private static void snapshotOriginals() {
        try {
            originalSurfacesExisted = Files.exists(surfacesPath());
            originalSurfacesContent = originalSurfacesExisted ? Files.readString(surfacesPath()) : null;
            originalVehicleExisted = Files.exists(vehiclePath());
            originalVehicleContent = originalVehicleExisted ? Files.readString(vehiclePath()) : null;
        } catch (IOException ex) {
            LOGGER.error("[DIV groundSurfaceHotReload] FAILED to snapshot existing config before writing - aborting rather than risk losing it", ex);
            phase = Phase.FAILED;
        }
    }

    /** Merges one surface's entry into the surfaces file without disturbing any other entry
     * already present - same non-destructive-merge convention {@code FlightRotorRuntimeFixtureProbe
     * .mergeVehicleOverrideAndWrite} uses for the sibling vehicle file. */
    private static boolean mergeSurfaceOverrideAndWrite(String surfaceName, Double frictionMultiplier, Double rollingResistanceMultiplier) {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(surfacesPath())).getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            root = new JsonObject();
        }
        if (!root.has("surfaces")) root.add("surfaces", new JsonObject());
        JsonObject surfaces = root.getAsJsonObject("surfaces");
        JsonObject entry = new JsonObject();
        if (frictionMultiplier != null) entry.addProperty("frictionMultiplier", frictionMultiplier);
        if (rollingResistanceMultiplier != null) entry.addProperty("rollingResistanceMultiplier", rollingResistanceMultiplier);
        surfaces.add(surfaceName, entry);
        try {
            Files.createDirectories(surfacesPath().getParent());
            Files.writeString(surfacesPath(), gson().toJson(root));
            surfacesWasWritten = true;
            return true;
        } catch (IOException ex) {
            LOGGER.error("[DIV groundSurfaceHotReload] FAILED to write surfaces config at " + surfacesPath(), ex);
            return false;
        }
    }

    private static boolean mergeVehicleOverrideAndWrite(Double massKg) {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(vehiclePath())).getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            root = new JsonObject();
        }
        if (!root.has("vehicles")) root.add("vehicles", new JsonObject());
        JsonObject vehicles = root.getAsJsonObject("vehicles");
        JsonObject entry = new JsonObject();
        entry.addProperty("massKg", massKg);
        vehicles.add(VEHICLE_KEY, entry);
        try {
            Files.createDirectories(vehiclePath().getParent());
            Files.writeString(vehiclePath(), gson().toJson(root));
            vehicleWasWritten = true;
            return true;
        } catch (IOException ex) {
            LOGGER.error("[DIV groundSurfaceHotReload] FAILED to write vehicle config at " + vehiclePath(), ex);
            return false;
        }
    }

    private static GroundSurface resolveBlock(ServerLevel overworld, String blockId) {
        Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockId)).orElseThrow();
        return GroundSurfaces.atState(overworld, BlockPos.ZERO, block.defaultBlockState());
    }

    private static void fail(String reason) {
        LOGGER.error("[DIV groundSurfaceHotReload] FAILED - " + reason);
        phase = Phase.RESTORE;
    }

    /** Establishes a known starting point - a VALID surface entry (ice) and a VALID vehicle entry,
     * written and reloaded TOGETHER - so every later phase's own assertions are against a value
     * this probe itself just set, never an unrelated pre-existing operator config on this host. */
    private static void baseline(ServerLevel overworld) {
        if (!mergeSurfaceOverrideAndWrite("ice", 0.02, 0.4) || !mergeVehicleOverrideAndWrite(1234.0)) {
            fail("could not write the initial baseline config - aborting rather than reload a possibly-stale file");
            return;
        }
        var surfaceResult = GroundSurfaceOverrides.reload();
        var vehicleReport = PhysicsBridge.reloadOverrides();
        if (!surfaceResult.success() || !vehicleReport.configResult().success()) {
            fail("BASELINE reload unexpectedly failed - surfaces=" + surfaceResult + " vehicle=" + vehicleReport.configResult());
            return;
        }
        GroundSurface ice = resolveBlock(overworld, "minecraft:ice");
        if (ice.frictionMultiplier() != 0.02 || ice.rollingResistanceMultiplier() != 0.4) {
            fail("BASELINE surface reload did not reach the actual contacted-surface response - resolved=" + ice);
            return;
        }
        Double massKg = Overrides.resolved(VEHICLE_KEY, null, null, null).massKg;
        if (massKg == null || massKg != 1234.0) {
            fail("BASELINE vehicle reload did not reach Overrides.resolved - massKg=" + massKg);
            return;
        }
        LOGGER.info("[DIV groundSurfaceHotReload] BASELINE PASSED - surfaces reloaded (" + surfaceResult.message() + ") and vehicle reloaded ("
            + vehicleReport.configResult().message() + ") independently, both live-resolved correctly");
        phase = Phase.VALID_SURFACE_VALID_VEHICLE;
    }

    /** Concurrent edit of BOTH files, both valid - the ordinary case this ticket's "independently
     * detect both config files" requirement must not break even when nothing is actually wrong. */
    private static void validSurfaceValidVehicle(ServerLevel overworld) {
        if (!mergeSurfaceOverrideAndWrite("gravel", 0.6, null) || !mergeVehicleOverrideAndWrite(4321.0)) {
            fail("could not write the VALID/VALID concurrent config");
            return;
        }
        var surfaceResult = GroundSurfaceOverrides.reload();
        var vehicleReport = PhysicsBridge.reloadOverrides();
        if (!surfaceResult.success()) { fail("VALID/VALID: surfaces reload unexpectedly failed: " + surfaceResult); return; }
        if (!vehicleReport.configResult().success()) { fail("VALID/VALID: vehicle reload unexpectedly failed: " + vehicleReport.configResult()); return; }
        GroundSurface gravel = resolveBlock(overworld, "minecraft:gravel");
        if (gravel.frictionMultiplier() != 0.6) { fail("VALID/VALID: gravel did not resolve the new frictionMultiplier - resolved=" + gravel); return; }
        Double massKg = Overrides.resolved(VEHICLE_KEY, null, null, null).massKg;
        if (massKg == null || massKg != 4321.0) { fail("VALID/VALID: vehicle massKg did not reach the new value - massKg=" + massKg); return; }
        LOGGER.info("[DIV groundSurfaceHotReload] VALID_SURFACE_VALID_VEHICLE PASSED - concurrent edits to both files both reloaded live and independently");
        phase = Phase.INVALID_SURFACE_VALID_VEHICLE;
    }

    /** Concurrent edit: surfaces file gets a malformed (out-of-range) entry, vehicle file gets a
     * VALID edit - the vehicle edit's own success must not be masked by the surfaces failure, and
     * the surfaces failure must retain its own last-known-good (the VALID_SURFACE_VALID_VEHICLE
     * values above), not fall back to the hardcoded default and not apply any partial/garbage
     * state. */
    private static void invalidSurfaceValidVehicle(ServerLevel overworld) {
        if (!mergeSurfaceOverrideAndWrite("gravel", -5.0, null) || !mergeVehicleOverrideAndWrite(5555.0)) {
            fail("could not write the INVALID-surface/VALID-vehicle concurrent config");
            return;
        }
        var surfaceResult = GroundSurfaceOverrides.reload();
        var vehicleReport = PhysicsBridge.reloadOverrides();
        if (surfaceResult.success()) { fail("INVALID_SURFACE: surfaces reload unexpectedly SUCCEEDED on an out-of-range frictionMultiplier: " + surfaceResult); return; }
        if (!vehicleReport.configResult().success()) { fail("INVALID_SURFACE: the concurrent VALID vehicle edit was masked by the surfaces failure: " + vehicleReport.configResult()); return; }
        GroundSurface gravel = resolveBlock(overworld, "minecraft:gravel");
        if (gravel.frictionMultiplier() != 0.6) {
            fail("INVALID_SURFACE: last-known-good was not retained after the rejected reload - resolved=" + gravel + " (expected the prior VALID frictionMultiplier=0.6)");
            return;
        }
        Double massKg = Overrides.resolved(VEHICLE_KEY, null, null, null).massKg;
        if (massKg == null || massKg != 5555.0) { fail("INVALID_SURFACE: the concurrent VALID vehicle edit did not actually apply - massKg=" + massKg); return; }
        // GroundSurfaceOverrides.reload() validates the WHOLE surfaces file as one unit (same as
        // Overrides.reload() does for vehicles) - this rejected gravel entry stays on DISK (only
        // the in-memory map was left untouched), so it would poison every LATER phase's own
        // reload of this same shared file, not just this one, exactly the failure mode
        // FlightRotorRuntimeFixtureProbe's own applyInvalidConfig doc already found and fixed.
        // Repair it back to its last-known-good value now, AFTER the retention assertion above.
        if (!mergeSurfaceOverrideAndWrite("gravel", 0.6, null)) {
            fail("could not repair the on-disk gravel entry back to VALID after the invalid-reload check");
            return;
        }
        LOGGER.info("[DIV groundSurfaceHotReload] INVALID_SURFACE_VALID_VEHICLE PASSED - invalid surfaces edit correctly rejected (last-known-good retained) while the concurrent valid vehicle edit applied, unmasked");
        phase = Phase.VALID_SURFACE_INVALID_VEHICLE;
    }

    /** The mirror of the previous phase - proves fault isolation runs both directions, not just
     * one: a malformed vehicle edit must not mask a concurrent valid surfaces edit, and the
     * vehicle file's own last-known-good must be retained. */
    private static void validSurfaceInvalidVehicle(ServerLevel overworld) {
        if (!mergeSurfaceOverrideAndWrite("ice", 0.01, 0.2) || !mergeVehicleOverrideAndWrite(999999.0)) {
            fail("could not write the VALID-surface/INVALID-vehicle concurrent config");
            return;
        }
        var surfaceResult = GroundSurfaceOverrides.reload();
        var vehicleReport = PhysicsBridge.reloadOverrides();
        if (!surfaceResult.success()) { fail("VALID_VEHICLE_INVALID: the concurrent VALID surfaces edit was masked by the vehicle failure: " + surfaceResult); return; }
        if (vehicleReport.configResult().success()) { fail("VALID_VEHICLE_INVALID: vehicle reload unexpectedly SUCCEEDED on an out-of-range massKg: " + vehicleReport.configResult()); return; }
        GroundSurface ice = resolveBlock(overworld, "minecraft:ice");
        if (ice.frictionMultiplier() != 0.01 || ice.rollingResistanceMultiplier() != 0.2) {
            fail("VALID_VEHICLE_INVALID: the concurrent VALID surfaces edit did not actually apply - resolved=" + ice);
            return;
        }
        Double massKg = Overrides.resolved(VEHICLE_KEY, null, null, null).massKg;
        if (massKg == null || massKg != 5555.0) {
            fail("VALID_VEHICLE_INVALID: vehicle last-known-good was not retained after the rejected reload - massKg=" + massKg + " (expected the prior VALID 5555.0)");
            return;
        }
        LOGGER.info("[DIV groundSurfaceHotReload] VALID_SURFACE_INVALID_VEHICLE PASSED - invalid vehicle edit correctly rejected (last-known-good retained) while the concurrent valid surfaces edit applied, unmasked");
        phase = Phase.RESTORE;
    }

    /** Reached from every exit path (success or failure) - restores both files' ORIGINAL content
     * and reloads both live, so this probe never leaves a test fixture's values active on a real
     * server past its own run. Mirrors {@code FlightRotorRuntimeFixtureProbe.restoreConfigOnce}. */
    private static void restore() {
        boolean ok = true;
        if (surfacesWasWritten) ok &= restoreOne(surfacesPath(), originalSurfacesExisted, originalSurfacesContent, "surfaces");
        if (vehicleWasWritten) ok &= restoreOne(vehiclePath(), originalVehicleExisted, originalVehicleContent, "vehicle");
        if (surfacesWasWritten) {
            var r = GroundSurfaceOverrides.reload();
            LOGGER.info("[DIV groundSurfaceHotReload] original surfaces config restored and reloaded: " + r.message());
        }
        if (vehicleWasWritten) {
            var r = PhysicsBridge.reloadOverrides();
            LOGGER.info("[DIV groundSurfaceHotReload] original vehicle config restored and reloaded: " + r.configResult().message());
        }
        restoreFailed = !ok;
        phase = restoreFailed ? Phase.FAILED : Phase.DONE;
        if (restoreFailed) {
            LOGGER.error("[DIV groundSurfaceHotReload] CLEANUP FAILED - see the earlier FAILED-to-restore error(s) above for manual cleanup");
        } else {
            LOGGER.info("[DIV groundSurfaceHotReload] COMPLETE (PASSED) - cleanup successful");
        }
    }

    private static boolean restoreOne(Path path, boolean existed, String content, String label) {
        try {
            if (existed) Files.writeString(path, content);
            else Files.deleteIfExists(path);
            return true;
        } catch (IOException ex) {
            LOGGER.error("[DIV groundSurfaceHotReload] FAILED to restore original " + label + " config at " + path
                + " - NOT reloading it (would apply the known-still-probe-mutated file) - manual cleanup needed."
                + (existed ? " Original content this probe is preserving for that cleanup: " + content : " Original file did not exist - it should be deleted."), ex);
            return false;
        }
    }
}
