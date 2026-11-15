package io.github.brooswitminecraft.div;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
/**
 * Bounded, periodic (never per-tick) check of each operator config file's own last-modified time
 * (ticket 53518/MINECRAFT-505's "hot reload safely while playing", extended by MINECRAFT-531 to
 * also cover the ground-surface config) - polling mtime is the simplest mechanism that needs no
 * new command/network/UI surface at all, and matches the exact periodic-not-per-tick pattern
 * PhysicsBridge.Body's own diagnostic receipts already use (see
 * ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS/CHASSIS_CLEARANCE_RECEIPT_PERIOD_TICKS).
 * {@link #maybeReload} does the actual work, independently tracking and checking the vehicle-
 * overrides file ({@link #maybeReloadVehicleOverrides}) and the ground-surface-overrides file
 * ({@link #maybeReloadSurfaceOverrides}) on the SAME shared period - one file's mtime is only
 * ever compared against its OWN last-seen value, so an edit to one file can never mask or be
 * masked by an edit to the other (each has its own {@code lastSeen*ModifiedMillis} field, checked
 * and updated independently, in its own try/catch over its own file).
 *
 * <p>Vehicle overrides delegate entirely to {@link PhysicsBridge#reloadOverrides} for both the
 * config swap and the live-propagation into active vehicles - this class only decides WHEN to
 * call it and logs the outcome. Ground-surface overrides need no such propagation step: unlike
 * vehicle {@code Tuning} (cached per-body and only selectively live-mutated), every ground-contact
 * lookup ({@code GroundSurfaces.atState}) already calls {@code GroundSurfaceOverrides.effective}
 * fresh each time, so simply swapping {@code GroundSurfaceOverrides}'s own static map (via
 * {@link GroundSurfaceOverrides#reload}) is already "live" for every vehicle on its very next
 * ground-contact tick - no per-vehicle respawn/propagation reporting applies to this file at all.
 * On any failure, {@code reloadOverrides}/{@code Overrides.reload}/{@code
 * GroundSurfaceOverrides.reload} already guarantee the previous config stays in effect (last-
 * known-good) - this class never retries more aggressively than its own normal period, so a
 * persistently-broken file logs the same failure once per period rather than spamming.
 */
final class OverridesHotReload {
 private static final Logger LOGGER = LogUtils.getLogger();
 /** 100 ticks (~5s at 20tps) - frequent enough that an edit-and-save feels "live" without
  * polling the filesystem every single tick. */
 static final int CHECK_PERIOD_TICKS = 100;
 private int ticksSinceLastCheck = CHECK_PERIOD_TICKS;
 private long lastSeenModifiedMillis = -1;
 private long lastSeenSurfacesModifiedMillis = -1;
 private Path path;
 private Path surfacesPath;
 private Path path() {
  if (path == null) path = FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json");
  return path;
 }
 private Path surfacesPath() {
  if (surfacesPath == null) surfacesPath = FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles-surfaces.json");
  return surfacesPath;
 }
 /** Pure, testable decision extracted from {@link #maybeReload} - whether THIS call is a check
  * call at all, over an explicit tick counter rather than a live clock or file I/O. */
 static boolean isCheckTick(int ticksSinceLastCheck, int periodTicks) { return ticksSinceLastCheck >= periodTicks; }
 void maybeReload() {
  ticksSinceLastCheck++;
  if (!isCheckTick(ticksSinceLastCheck, CHECK_PERIOD_TICKS)) return;
  ticksSinceLastCheck = 0;
  maybeReloadVehicleOverrides();
  maybeReloadSurfaceOverrides();
 }
 private void maybeReloadVehicleOverrides() {
  long modified;
  try {
   modified = Files.getLastModifiedTime(path()).toMillis();
  } catch (IOException ex) {
   return; // no config file at all this check - nothing to reload, not itself an error
  }
  if (modified == lastSeenModifiedMillis) return; // unchanged since the last check - nothing to do
  lastSeenModifiedMillis = modified;
  var report = PhysicsBridge.reloadOverrides();
  var configResult = report.configResult();
  if (!configResult.success()) {
   LOGGER.warn("[DIV overridesReload] FAILED, keeping last-known-good config: "+configResult.message());
   return;
  }
  LOGGER.info("[DIV overridesReload] "+configResult.message()+"; liveUpdatedVehicles="+report.vehiclesUpdatedLive()
    +" fieldsNeedingRespawn="+report.fieldsNeedingRespawn());
 }
 private void maybeReloadSurfaceOverrides() {
  long modified;
  try {
   modified = Files.getLastModifiedTime(surfacesPath()).toMillis();
  } catch (IOException ex) {
   return; // no surfaces config file at all this check - nothing to reload, not itself an error
  }
  if (modified == lastSeenSurfacesModifiedMillis) return; // unchanged since the last check - nothing to do
  lastSeenSurfacesModifiedMillis = modified;
  var result = GroundSurfaceOverrides.reload();
  if (!result.success()) {
   LOGGER.warn("[DIV overridesReload] surfaces FAILED, keeping last-known-good surface config: "+result.message());
   return;
  }
  LOGGER.info("[DIV overridesReload] surfaces "+result.message());
 }
}
