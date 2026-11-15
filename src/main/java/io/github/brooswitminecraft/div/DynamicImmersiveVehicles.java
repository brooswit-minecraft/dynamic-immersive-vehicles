package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.client.DivClientConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
@Mod("dynamicimmersivevehicles")
public final class DynamicImmersiveVehicles {
 public DynamicImmersiveVehicles(IEventBus modEventBus, ModContainer modContainer) {
  GroundContactDiagnostics.logStartupMarker(PhysicsBridge.resolveDebugGroundContactTicks(System.getProperty("div.debugGroundContactTicks")),
    PhysicsBridge.effectiveFrictionSolverIterations());
  Overrides.load();
  GroundSurfaceOverrides.load();
  // Ticket MINECRAFT-504: client-only wheel-aux config (hybrid H-pattern/paddle shift toggle,
  // analog handbrake calibration) - safe to register unconditionally on both sides, same as
  // every other NeoForge mod's CLIENT config (FML simply never loads/uses it on a dedicated
  // server).
  modContainer.registerConfig(ModConfig.Type.CLIENT, DivClientConfig.SPEC);
  modEventBus.addListener((RegisterPayloadHandlersEvent event) -> {
   var registrar = event.registrar("1");
   registrar.playToServer(WheelAuxPayload.TYPE, WheelAuxPayload.STREAM_CODEC, WheelAuxPayload::handle);
  });
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> PhysicsBridge.cleanup());
  // Ticket MINECRAFT-504: per-server-tick continuation for the hybrid H-pattern climb and the
  // analog handbrake's staleness timeout - see PhysicsBridge.advanceWheelAuxInputs's own doc for
  // why this cannot just happen inside the packet handler.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> PhysicsBridge.advanceWheelAuxInputs());
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
  // Opt-in, CI/isolated-host-only mod-load/tag-resolution smoke test (MINECRAFT-508 review,
  // John) - OFF by default, a strict no-op otherwise. Never touches any real vehicle or world
  // content - see GroundSurfaceTagProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.GroundSurfaceTagProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only real-oak_log canonical-texture/rotation smoke test
  // (independent review, PR #16: synthetic fixtures/fallback tests alone don't prove classpath
  // assets are actually reachable) - OFF by default, a strict no-op otherwise. Never touches any
  // real vehicle - see DirectionalGroundSurfaceProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.DirectionalGroundSurfaceProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only wheel-aux state-machine probe (ticket MINECRAFT-504, review
  // comment 53617) - OFF by default, a strict no-op otherwise. Never touches any real vehicle
  // or world content - see WheelAuxProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.WheelAuxProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only float-only-hull angular-water-drag probe (review comment
  // 53675/53676/53679, MINECRAFT-518) - OFF by default, a strict no-op otherwise. Never touches
  // any real vehicle, Zippy, or world content - see BuoyancyAngularDragProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.BuoyancyAngularDragProbe.maybeRun(overworld);
  });
  // Opt-in, dev/isolated-host-only fixed-wing/rotorcraft activation, default-response,
  // configured-response and valid/invalid live-reload probe (MINECRAFT-526) - OFF by default, a
  // strict no-op otherwise. Closes the disclosed "no live EntityVehicleF_Physics/Sable runtime
  // fixture exists" gap both MINECRAFT-521 (PR #19) and MINECRAFT-522 (PR #17) carried in their
  // own test plans. Never touches any real vehicle, Zippy, or world content - see
  // FlightRotorRuntimeFixtureProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.FlightRotorRuntimeFixtureProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only hitch rotation-frame composition probe (MINECRAFT-525, review
  // comment 53817/53832) - OFF by default, a strict no-op otherwise. Never touches any real
  // vehicle or world content - see HitchRotationFrameProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.HitchRotationFrameProbe.maybeRun(overworld);
  });
 }
}
