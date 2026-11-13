package io.github.brooswitminecraft.div;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
@Mod("dynamicimmersivevehicles")
public final class DynamicImmersiveVehicles {
 public DynamicImmersiveVehicles() {
  GroundContactDiagnostics.logStartupMarker(PhysicsBridge.resolveDebugGroundContactTicks(System.getProperty("div.debugGroundContactTicks")),
    PhysicsBridge.effectiveFrictionSolverIterations());
  Overrides.load();
  GroundSurfaceOverrides.load();
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> PhysicsBridge.cleanup());
  // Ticket 53518 (MINECRAFT-505): bounded, periodic operator-config hot reload - see
  // OverridesHotReload's own doc for why mtime-polling, and PhysicsBridge.reloadOverrides for
  // what actually gets propagated into already-active vehicles vs. reported as needing respawn.
  var overridesHotReload = new OverridesHotReload();
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> overridesHotReload.maybeReload());
  NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> PhysicsBridge.unload(event.getLevel()));
  // Opt-in, CI/isolated-host-only anchor-frame probe (ticket comment 53243/53244) - OFF by
  // default (Boolean.getBoolean read once inside the probe itself), a strict no-op otherwise.
  // Never touches any real vehicle or world content - see AnchorFrameProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.AnchorFrameProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only two-body impulse-response probe (review comment 53280) - OFF
  // by default, a strict no-op otherwise. Never touches any real vehicle or world content - see
  // TwoBodyImpulseResponseProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.TwoBodyImpulseResponseProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only real-vehicle ground-support fixture (review comment 53287) -
  // OFF by default, a strict no-op otherwise. Never touches any real vehicle or world content -
  // see GroundSupportFixtureProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.GroundSupportFixtureProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only brake-only speed sweep (review comment 53327) - OFF by
  // default, a strict no-op otherwise. Never touches any real vehicle or world content - see
  // BrakeSweepProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.BrakeSweepProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only occupied baseline-vs-tuned turn comparison (review comment
  // 53331/53332/53339/53340) - OFF by default, a strict no-op otherwise. Never touches any
  // real vehicle, Zippy, or world content - see TurnComparisonProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.TurnComparisonProbe.maybeRun(overworld);
  });
 }
}
