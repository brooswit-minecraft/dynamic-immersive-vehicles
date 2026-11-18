package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.client.ClientTowingConnectionTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
/**
 * Server to client (ticket MINECRAFT-545/547, John's comment 54998): the two halves of the
 * client-side connection-identity binding this fixture's own stale-disconnect-notification
 * design depends on, carried by a single payload shape distinguished by {@link #disconnected}:
 * <ul>
 *   <li>{@code disconnected=false} - a GENERATION ANNOUNCEMENT, sent the same tick a connection's
 *   own generation is first assigned ({@code PhysicsBridge.releaseOrphanedTowingConnections}'s
 *   own per-tick scan) - the client binds this generation to its OWN local connection object for
 *   the named pair/slot, ONLY if its own current connection's identity actually matches.</li>
 *   <li>{@code disconnected=true} - a DISCONNECT NOTIFICATION, sent from
 *   {@code PhysicsBridge}'s own missing-index fallback at the exact point no supported MTS
 *   packet can be constructed (see VERIFICATION.md's own "Per 54966"/"Per 54974" entries for
 *   why) - the client runs the real {@code decideStaleDisconnectAction}/
 *   {@code applyStaleDisconnectAction} pipeline against its own current state.</li>
 * </ul>
 * Recipient routing in both cases: {@code PhysicsBridge.resolveTrackingEntity} (backed by the
 * genuinely public {@code mcinterface1211.InterfaceInterface.toExternal}) plus
 * {@code PacketDistributor.sendToPlayersTrackingEntity} - bounded to players actually tracking
 * the towed vehicle, never an all-player broadcast.
 */
public record ConnectionGenerationPayload(
    java.util.UUID towingVehicleUuid, java.util.UUID towedVehicleUuid,
    int hitchGroupIndex, int hitchConnectionIndex,
    int hookupGroupIndex, int hookupConnectionIndex,
    long generation, boolean disconnected) implements CustomPacketPayload {

 public ConnectionGenerationPayload(PhysicsBridge.TowingConnectionIdentity identity, long generation, boolean disconnected) {
  this(identity.towingVehicleUuid(), identity.towedVehicleUuid(),
    identity.hitchGroupIndex(), identity.hitchConnectionIndex(),
    identity.hookupGroupIndex(), identity.hookupConnectionIndex(),
    generation, disconnected);
 }

 public PhysicsBridge.TowingConnectionIdentity identity() {
  return new PhysicsBridge.TowingConnectionIdentity(
    towingVehicleUuid, towedVehicleUuid, hitchGroupIndex, hitchConnectionIndex, hookupGroupIndex, hookupConnectionIndex);
 }

 public static final CustomPacketPayload.Type<ConnectionGenerationPayload> TYPE =
   new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dynamicimmersivevehicles", "connection_generation"));

 // Manual codec (not StreamCodec.composite, whose arity does not cleanly cover 8 heterogeneous
 // fields) - a direct, explicit read/write pair, same conceptual shape as every other payload
 // this codebase already ships, just written out rather than composed.
 public static final StreamCodec<RegistryFriendlyByteBuf, ConnectionGenerationPayload> STREAM_CODEC = StreamCodec.of(
   (buf, payload) -> {
    buf.writeUUID(payload.towingVehicleUuid());
    buf.writeUUID(payload.towedVehicleUuid());
    buf.writeVarInt(payload.hitchGroupIndex());
    buf.writeVarInt(payload.hitchConnectionIndex());
    buf.writeVarInt(payload.hookupGroupIndex());
    buf.writeVarInt(payload.hookupConnectionIndex());
    buf.writeLong(payload.generation());
    buf.writeBoolean(payload.disconnected());
   },
   buf -> new ConnectionGenerationPayload(
     buf.readUUID(), buf.readUUID(),
     buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
     buf.readLong(), buf.readBoolean()));

 @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

 public static void handle(ConnectionGenerationPayload payload, IPayloadContext context) {
  context.enqueueWork(() -> {
   // MINECRAFT-545/547 (John's comment 55010): this payload is playToClient - handle() (and
   // therefore this lambda) only ever EXECUTES client-side; the JVM's own lazy symbolic
   // resolution of ClientTowingConnectionTracker (a referenced class, not yet loaded until
   // actually invoked) never triggers on a dedicated server, which never calls into this
   // lambda body at all - the same standard pattern every playToClient payload handler in any
   // NeoForge mod already relies on. All client-only state/logic itself lives in
   // ClientTowingConnectionTracker (Dist.CLIENT-scoped), never referenced from common
   // PhysicsBridge - see that class's own doc for the full reasoning.
   var towed = ClientTowingConnectionTracker.findClientTrackedVehicleByUuid(payload.towedVehicleUuid());
   if (towed == null) return; // not currently loaded/tracked client-side - nothing to do.
   if (payload.disconnected()) {
    ClientTowingConnectionTracker.handleClientStaleDisconnectNotification(towed, payload.identity(), payload.generation());
   } else {
    ClientTowingConnectionTracker.bindClientConnectionGeneration(towed, payload.identity(), payload.generation());
   }
  });
 }
}
