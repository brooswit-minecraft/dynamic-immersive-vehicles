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
   // MINECRAFT-545/547 (John's comment 54998): server-to-client, the connection-generation
   // announce/disconnect-notify payload - see ConnectionGenerationPayload's own doc.
   registrar.playToClient(ConnectionGenerationPayload.TYPE, ConnectionGenerationPayload.STREAM_CODEC, ConnectionGenerationPayload::handle);
  });
  // MINECRAFT-545/547 (John's comments 54727/54730/54742): orphan-connection release runs on
  // ServerTickEvent.Pre - fires at the HEAD of MinecraftServer#tickServer, before THIS tick's
  // own entity-ticking - specifically so a connection orphaned during the PRIOR tick's own
  // ServerTickEvent.Post phase (after that tick's entity-ticking already ran) is released
  // before THIS tick's entity-ticking, not one tick late. See
  // PhysicsBridge.releaseOrphanedTowingConnections's own doc for the full derivation. Extracted
  // from cleanup() (below), which no longer performs this sweep - never both, no duplicate
  // dispatch.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> PhysicsBridge.releaseOrphanedTowingConnections());
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
  // MINECRAFT-545/547 (John's comment 55010): the event-driven replacement for the 55005
  // round's own blind, fixed-length announce-retry window - PlayerEvent.StartTracking fires
  // server-side for exactly the one player who just started tracking exactly one entity,
  // covering an arbitrarily late join/retrack with no timer and no missed window. See
  // PhysicsBridge.announceConnectionGenerationToNewTracker's own doc.
  NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking event) -> {
   if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
    PhysicsBridge.announceConnectionGenerationToNewTracker(player, event.getTarget());
   }
  });
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
  // Opt-in, CI/isolated-host-only float-only-hull DIRECTIONAL (longitudinal/lateral/vertical)
  // water-drag probe (MINECRAFT-535) - OFF by default, a strict no-op otherwise. Never touches
  // any real vehicle, Zippy, or world content - see DirectionalWaterDragProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.DirectionalWaterDragProbe.maybeRun(overworld);
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
  // Opt-in, CI/isolated-host-only installed-part hitch-frame composition probe (MINECRAFT-530
  // review, comment 54033) - OFF by default, a strict no-op otherwise. Never touches any real
  // vehicle or world content - see InstalledPartHitchFrameProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.InstalledPartHitchFrameProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only float-only-hull hitch lifecycle probe (MINECRAFT-545) - OFF by
  // default, a strict no-op otherwise. Never touches any real vehicle or world content - see
  // BuoyantHitchProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.BuoyantHitchProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only ground-vehicle hitch-replacement comparison probe
  // (MINECRAFT-545, John's own directive comment 54217) - OFF by default, a strict no-op
  // otherwise. Never touches any real vehicle or world content - see
  // GroundHitchReplacementProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.GroundHitchReplacementProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only already-active unsafe-fallback lifecycle probe (MINECRAFT-
  // 545/547, John's comment 54259) - OFF by default, a strict no-op otherwise. Never touches any
  // real vehicle or world content - see AlreadyActiveUnsafeFallbackProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.AlreadyActiveUnsafeFallbackProbe.maybeRun(overworld);
  });
  // Opt-in, CI/isolated-host-only ground-surface-config hot-reload probe (MINECRAFT-531) - OFF by
  // default, a strict no-op otherwise. Never touches any real vehicle or world content - see
  // GroundSurfaceHotReloadProbe's own doc.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.GroundSurfaceHotReloadProbe.maybeRun(overworld);
  });
  // MINECRAFT-545/547 (John's comment 54856): bounded, opt-in missing-index fallback fixture -
  // OFF by default, a strict no-op otherwise. Never touches any real vehicle or world content -
  // see MissingIndexFallbackProbe's own doc. Relies on releaseOrphanedTowingConnections's own
  // existing ServerTickEvent.Pre registration above - no separate sweep trigger needed.
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
   var overworld = event.getServer().overworld();
   if (overworld != null) io.github.brooswitminecraft.div.experimental.MissingIndexFallbackProbe.maybeRun(overworld);
  });
 }
}
