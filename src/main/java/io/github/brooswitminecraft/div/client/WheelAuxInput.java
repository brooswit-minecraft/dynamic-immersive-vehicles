package io.github.brooswitminecraft.div.client;
import io.github.brooswitminecraft.div.HPatternShifter;
import io.github.brooswitminecraft.div.WheelAuxPayload;
import io.github.brooswitminecraft.div.WheelAxisCalibration;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.systems.ControlSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side reading for the two things this project adds on top of native IV wheel/shifter
 * support (ticket MINECRAFT-504, comment 53572): the H-pattern shifter's physical slot, read
 * directly via IV's own {@code ControlsJoystick.CAR_SHIFT_1-R} bindings (so it works regardless
 * of IV's own {@code useShifter} setting - see {@link HPatternShifter}'s own doc for why), and a
 * separately configurable analog handbrake axis IV has no control for at all. Deliberately reuses
 * IV's own configured joystick (whatever device the player already bound to {@code CAR_TURN} in
 * IV's controls GUI) for the handbrake axis too, rather than introducing a second
 * device-selection config - "reuse IV pipeline", not a parallel input stack.
 *
 * <p>Both IV's native pedal/steering/paddle reading and this class run every client tick
 * independently; they only converge, server-side, on {@code PartEngine}'s public
 * shift methods and the friction solver's brake magnitude - see {@code PhysicsBridge}'s own doc
 * at those call sites.
 */
@EventBusSubscriber(modid = "dynamicimmersivevehicles", value = Dist.CLIENT)
public final class WheelAuxInput {
 private static int lastSentHPatternTarget = Integer.MIN_VALUE;
 private static double lastSentHandbrakeMagnitude = -1;
 private static boolean wasRidingForAux;
 private WheelAuxInput() {}

 @SubscribeEvent
 public static void onClientTick(ClientTickEvent.Post event) {
  // IV's own entities are business objects, not vanilla Minecraft entities - getClientPlayer()
  // is IV's own public client wrapper, whose getEntityRiding() is the sanctioned way to reach
  // whatever IV entity the local player is riding (same path IV's own client control code uses).
  boolean riding = Minecraft.getInstance().player != null
    && minecrafttransportsimulator.mcinterface.InterfaceManager.clientInterface.getClientPlayer() != null
    && minecrafttransportsimulator.mcinterface.InterfaceManager.clientInterface.getClientPlayer().getEntityRiding() instanceof EntityVehicleF_Physics;
  if (!riding) {
   wasRidingForAux = false;
   return;
  }
  if (!wasRidingForAux) {
   // Fresh mount: force a resend even if this tick's computed values happen to match the
   // last vehicle's, since the new vehicle's own server-side state starts fresh (same
   // reasoning as WheelPaddlesPayload's own fresh-mount handling in dynamic-vehicles).
   lastSentHPatternTarget = Integer.MIN_VALUE;
   lastSentHandbrakeMagnitude = -1;
  }
  wasRidingForAux = true;
  int hPatternTarget = DivClientConfig.HYBRID_SHIFT_ENABLED.get() ? currentHPatternTarget() : HPatternShifter.DISABLED;
  double handbrakeMagnitude = currentHandbrakeMagnitude();
  if (hPatternTarget != lastSentHPatternTarget || Math.abs(handbrakeMagnitude - lastSentHandbrakeMagnitude) > 1e-6) {
   lastSentHPatternTarget = hPatternTarget;
   lastSentHandbrakeMagnitude = handbrakeMagnitude;
   PacketDistributor.sendToServer(new WheelAuxPayload(hPatternTarget, handbrakeMagnitude));
  }
 }

 /** Mirrors IV's own gearNumber derivation in {@code ControlSystem.controlGroundVehicle} exactly
  * (same decompiled branch, same sentinel values) - reads the SAME bindings IV's GUI already
  * lets the player configure, just independent of {@code useShifter}. */
 private static int currentHPatternTarget() {
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_1.isPressed()) return 1;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_2.isPressed()) return 2;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_3.isPressed()) return 3;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_4.isPressed()) return 4;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_5.isPressed()) return 5;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_6.isPressed()) return 6;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_7.isPressed()) return 7;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_8.isPressed()) return 8;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_9.isPressed()) return 9;
  if (ControlSystem.ControlsJoystick.CAR_SHIFT_R.isPressed()) return HPatternShifter.REVERSE_REQUEST;
  return HPatternShifter.NEUTRAL_REQUEST;
 }

 private static double currentHandbrakeMagnitude() {
  int axis = DivClientConfig.HANDBRAKE_AXIS.get();
  if (axis < 0) return 0.0;
  // Reuse the SAME joystick the player already configured for steering in IV's own controls
  // GUI, rather than a second device-selection config of our own.
  String joystickName = ControlSystem.ControlsJoystick.CAR_TURN.config.joystickName;
  if (joystickName == null || joystickName.isEmpty() || !InterfaceManager.inputInterface.isJoystickPresent(joystickName)) return 0.0;
  float raw = InterfaceManager.inputInterface.getJoystickAxisValue(joystickName, axis);
  return WheelAxisCalibration.magnitude(raw, DivClientConfig.HANDBRAKE_AXIS_MIN.get(), DivClientConfig.HANDBRAKE_AXIS_MAX.get(),
    DivClientConfig.HANDBRAKE_INVERT.get(), DivClientConfig.HANDBRAKE_DEADZONE.get());
 }
}
