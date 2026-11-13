package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
/**
 * Client to server: the wheel-aux input this project itself reads (ticket MINECRAFT-504, comment
 * 53572) - the H-pattern shifter's current target slot ({@link HPatternShifter}'s sentinel
 * values) and the analog handbrake's current 0-1 magnitude. Both are read and calibrated entirely
 * client-side; the server applies them via {@code PhysicsBridge}'s own public entry points,
 * exactly like every other IV-native control this project already only reads through the
 * vehicle's public vars.
 */
public record WheelAuxPayload(int hPatternTarget, double handbrakeMagnitude) implements CustomPacketPayload {
 public static final CustomPacketPayload.Type<WheelAuxPayload> TYPE =
   new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dynamicimmersivevehicles", "wheel_aux"));
 public static final StreamCodec<RegistryFriendlyByteBuf, WheelAuxPayload> STREAM_CODEC = StreamCodec.composite(
   ByteBufCodecs.VAR_INT, WheelAuxPayload::hPatternTarget,
   ByteBufCodecs.DOUBLE, WheelAuxPayload::handbrakeMagnitude,
   WheelAuxPayload::new);
 @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
 public static void handle(WheelAuxPayload payload, IPayloadContext context) {
  context.enqueueWork(() -> {
   var player = context.player();
   // IV's own entities are business objects (AEntityB_Existing), not vanilla Entity
   // subclasses - mcinterface1211.WrapperPlayer.getWrapperFor is IV's own public factory for
   // going from a vanilla player to its wrapper, whose getEntityRiding() is the sanctioned
   // way to reach the IV entity a player is riding (same path IV's own packet handlers use).
   if (player != null && mcinterface1211.WrapperPlayer.getWrapperFor(player).getEntityRiding() instanceof EntityVehicleF_Physics v) {
    PhysicsBridge.applyHPatternTarget(v, payload.hPatternTarget());
    PhysicsBridge.setHandbrakeMagnitude(v, payload.handbrakeMagnitude());
   }
  });
 }
}
