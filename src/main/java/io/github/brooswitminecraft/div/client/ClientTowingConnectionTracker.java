package io.github.brooswitminecraft.div.client;
import io.github.brooswitminecraft.div.ConnectionGenerationPayload;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * MINECRAFT-545/547 (John's comment 55010): "Direct Minecraft client-class reference in common
 * PhysicsBridge needs side-separated adapter... compilation alone is insufficient." ALL
 * client-only state/logic for the stale-disconnect-notification mechanism lives HERE, in DIV's
 * own established client-code package (see {@link WheelAuxInput}/{@link DivClientConfig}), never
 * in the common {@code PhysicsBridge} class - {@code @EventBusSubscriber(Dist.CLIENT)} ensures
 * this class, and every event handler on it, is registered ONLY on the client physical side,
 * never loaded at all on a dedicated server JVM (a framework-level guarantee, not merely careful
 * organization).
 */
@EventBusSubscriber(modid = "dynamicimmersivevehicles", value = Dist.CLIENT)
public final class ClientTowingConnectionTracker {
 private ClientTowingConnectionTracker() {}

 /** The CLIENT's own, side-correct resolution: enumerates the CLIENT's own currently-rendered
  * entities ({@code Minecraft.getInstance().level.entitiesForRendering()} - a real, bounded,
  * client-local set, never reaching across the network) for a {@code BuilderEntityExisting}
  * whose own business entity (via {@code InterfaceInterface.toInternal}, the exact reverse of
  * {@code PhysicsBridge.resolveTrackingEntity}'s own forward direction) matches {@code uuid}.
  * NEVER falls back to {@code PhysicsBridge}'s own physics registry - that registry is
  * populated only by SERVER-side body construction, which on an integrated singleplayer world
  * (client and server sharing one JVM's static state) would have let this exact lookup find
  * and return the SERVER's own live object instead (the real, confirmed hazard the prior round's
  * own {@code findActiveVehicleByUuid} had). */
 public static EntityVehicleF_Physics findClientTrackedVehicleByUuid(java.util.UUID uuid) {
  var level = Minecraft.getInstance().level;
  if (level == null) return null;
  for (var entity : level.entitiesForRendering()) {
   if (entity instanceof mcinterface1211.BuilderEntityExisting builder) {
    var business = mcinterface1211.InterfaceInterface.toInternal(builder);
    if (business instanceof EntityVehicleF_Physics v && v.uniqueUUID.equals(uuid)) return v;
   }
  }
  return null;
 }

 /** The CLIENT's own half of the generation ledger - a SEPARATE instance from {@code
  * PhysicsBridge.connectionGenerations} (never shared), populated ONLY by binding an explicit,
  * server-sourced generation announcement to the client's OWN local connection object, never by
  * the client inventing its own numbering. Also a {@code WeakHashMap} for the same structural-
  * boundedness reason as the server's own. */
 private static final java.util.Map<Object, Long> clientConnectionGenerations = new java.util.WeakHashMap<>();

 /** Tracks, per TOWED VEHICLE (a stable identifier across reconnects, unlike any one
  * {@code TowingConnection} object), the highest generation number this client has EVER
  * successfully bound - a plain {@code HashMap<UUID,Long>}, intentionally small, explicitly
  * cleared only at a genuine session boundary (see {@link #forgetAllClientStateForTowedVehicle}),
  * never left to grow unbounded across a long play session (bounded by the number of distinct
  * towed vehicles ever encountered in ONE session - small in practice). */
 private static final java.util.Map<java.util.UUID, Long> clientHighestBoundGenerationPerTowedVehicle = new java.util.HashMap<>();

 /** Called from the client's own packet-handling thread when a generation ANNOUNCEMENT arrives.
  * The actual safety decision is {@code PhysicsBridge.shouldBindAnnouncedGeneration} (pure,
  * unit-tested) - this wrapper only supplies the real, live inputs it needs. */
 public static void bindClientConnectionGeneration(
     EntityVehicleF_Physics towedVehicle, PhysicsBridge.TowingConnectionIdentity announcedIdentity, long announcedGeneration) {
  var current = towedVehicle.towedByConnection;
  if (current == null) return;
  if (!PhysicsBridge.toIdentity(current).equals(announcedIdentity)) return;
  boolean alreadyBound = clientConnectionGenerations.containsKey(current);
  Long watermark = clientHighestBoundGenerationPerTowedVehicle.get(towedVehicle.uniqueUUID);
  if (!PhysicsBridge.shouldBindAnnouncedGeneration(alreadyBound, watermark, announcedGeneration)) return;
  clientConnectionGenerations.put(current, announcedGeneration);
  clientHighestBoundGenerationPerTowedVehicle.put(towedVehicle.uniqueUUID, announcedGeneration);
 }

 /** The CLIENT's own counterpart to {@code PhysicsBridge.forgetConnectionGeneration} - releases
  * {@code connection}'s own entry from the CLIENT's ledger (never the server's). The watermark
  * is deliberately NOT cleared here (it must persist across an ordinary disconnect so a later,
  * stale/delayed announcement for the just-removed object cannot "win" simply because its own
  * entry is now gone) - only {@link #forgetAllClientStateForTowedVehicle} (a genuine session
  * boundary) clears it. */
 public static void forgetClientConnectionGeneration(Object connection) {
  if (connection != null) clientConnectionGenerations.remove(connection);
 }

 /** MINECRAFT-545/547 (John's comment 55010): "has no logout/world-unload call site and clears
  * only watermark despite claiming both maps." Fixed on both counts: this now actually clears
  * the per-vehicle watermark (the only map that NEEDS an explicit session-boundary clear - the
  * per-object bindings in {@link #clientConnectionGenerations} are already weakly held and self-
  * clean once unreferenced, so "clearing both maps" was never literally necessary, only the doc
  * claim was wrong) and is now ACTUALLY WIRED to a real call site below
  * ({@link #onLevelUnload(LevelEvent.Unload)}), scoped to the client's own rendering level only. */
 public static void forgetAllClientStateForTowedVehicle(java.util.UUID towedVehicleUuid) {
  clientHighestBoundGenerationPerTowedVehicle.remove(towedVehicleUuid);
 }

 /** MINECRAFT-545/547 (John's comment 55010): the actual logout/world-unload call site that was
  * missing - {@code LevelEvent.Unload} fires on BOTH logical sides for a common event, so this
  * checks {@code isClientSide()} explicitly (this class is already {@code Dist.CLIENT}-scoped,
  * but on an INTEGRATED singleplayer world the internal SERVER's own level unload also fires
  * this same event in the same JVM - the explicit check is what actually distinguishes "the
  * client's own session just ended" from "the server's own level happened to unload too").
  * Clears the ENTIRE per-vehicle watermark map outright - a session boundary (leaving/switching
  * levels) is exactly the point at which no watermark can still be meaningful, consistent with
  * {@code PhysicsBridge.connectionGenerations}'s own "nonreusable session identity" reasoning
  * for the server-side equivalent. */
 @SubscribeEvent
 public static void onLevelUnload(LevelEvent.Unload event) {
  if (!event.getLevel().isClientSide()) return;
  clientHighestBoundGenerationPerTowedVehicle.clear();
  clientConnectionGenerations.clear();
 }

 /** MINECRAFT-545/547 (John's comment 55010): the REAL production adapter for
  * {@code PhysicsBridge.applyStaleDisconnectAction} - MOVED here from the common
  * {@code PhysicsBridge} class (it is logically a client-only operation, and the prior round's
  * own version called the SERVER's {@code forgetConnectionGeneration} from this exact
  * client-invoked code - fixed by calling {@link #forgetClientConnectionGeneration} instead, in
  * BOTH branches). */
 public static final PhysicsBridge.StaleDisconnectOps<EntityVehicleF_Physics> REAL_STALE_DISCONNECT_OPS =
   new PhysicsBridge.StaleDisconnectOps<EntityVehicleF_Physics>() {
  public void disconnectByClientIndex(EntityVehicleF_Physics v, int clientOwnIndex) {
   var connection = v.towedByConnection;
   v.towedByConnection.towingVehicle.disconnectTrailer(clientOwnIndex);
   forgetClientConnectionGeneration(connection);
  }
  public void idempotentCleanup(EntityVehicleF_Physics v) {
   var connection = v.towedByConnection;
   v.towedByConnection.towingVehicle.resetAllVariables();
   v.resetAllVariables();
   v.parkingBrakeVar.setTo(1.0, false);
   v.towedByConnection = null;
   forgetClientConnectionGeneration(connection);
  }
 };

 /** Called from the client's own packet-handling thread when a DISCONNECT notification arrives.
  * Builds the client's own current identity/generation/valid-index inputs from
  * {@code towedVehicle}'s own real, live state, decides via
  * {@code PhysicsBridge.decideStaleDisconnectAction}, and applies via
  * {@code PhysicsBridge.applyStaleDisconnectAction} with {@link #REAL_STALE_DISCONNECT_OPS} -
  * the full real pipeline, never a reimplementation of either step. */
 public static void handleClientStaleDisconnectNotification(
     EntityVehicleF_Physics towedVehicle, PhysicsBridge.TowingConnectionIdentity notifiedIdentity, long notifiedGeneration) {
  var currentConnection = towedVehicle.towedByConnection;
  PhysicsBridge.TowingConnectionIdentity clientCurrent = currentConnection != null ? PhysicsBridge.toIdentity(currentConnection) : null;
  Long clientCurrentGeneration = clientConnectionGenerations.get(currentConnection);
  int clientOwnIndex = currentConnection != null
    ? currentConnection.towingVehicle.towingConnections.indexOf(currentConnection) : -1;
  boolean clientHasValidIndex = clientOwnIndex >= 0;
  var action = PhysicsBridge.decideStaleDisconnectAction(notifiedIdentity, notifiedGeneration, clientCurrent, clientCurrentGeneration, clientHasValidIndex);
  PhysicsBridge.applyStaleDisconnectAction(towedVehicle, action, clientOwnIndex, REAL_STALE_DISCONNECT_OPS);
 }
}
