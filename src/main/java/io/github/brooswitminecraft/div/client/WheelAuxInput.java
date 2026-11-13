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
 * separately configurable analog handbrake axis IV has no control for at all.
 *
 * <p>Both IV's native pedal/steering/paddle reading and this class run every client tick
 * independently; they only converge, server-side, on {@code PartEngine}'s public
 * shift methods and the friction solver's brake magnitude - see {@code PhysicsBridge}'s own doc
 * at those call sites.
 *
 * <p>Sends a payload on every actual CHANGE, but also a periodic unconditional heartbeat
 * (ticket comment 53617: a changed-only scheme means a CONSTANTLY HELD handbrake - the common
 * case, not an edge case - looks identical to silence to the server, so it would expire at the
 * staleness timeout even while the lever is still pulled). There is deliberately no "clear on
 * dismount" packet: the controller-seat check a dismounted player has already failed by the
 * time such a packet could be sent would reject it anyway - {@code PhysicsBridge}'s own
 * ownership-loss revocation (checked every server tick, independent of any packet) is what
 * actually releases a dismounted vehicle's state immediately.
 */
@EventBusSubscriber(modid = "dynamicimmersivevehicles", value = Dist.CLIENT)
public final class WheelAuxInput {
 private static int lastSentHPatternTarget = Integer.MIN_VALUE;
 private static double lastSentHandbrakeMagnitude = -1;
 private static boolean wasRidingForAux;
 private static int ticksSinceLastSend = 0;
 // Well under PhysicsBridge.WHEEL_AUX_STALE_TIMEOUT_TICKS (100) so ordinary network jitter never
 // lets a sustained input cross that threshold between heartbeats.
 private static final int HEARTBEAT_TICKS = 20;
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
   ticksSinceLastSend = 0;
  }
  wasRidingForAux = true;
  int hPatternTarget = DivClientConfig.HYBRID_SHIFT_ENABLED.get() ? currentHPatternTarget() : HPatternShifter.DISABLED;
  double handbrakeMagnitude = currentHandbrakeMagnitude();
  boolean changed = hPatternTarget != lastSentHPatternTarget || Math.abs(handbrakeMagnitude - lastSentHandbrakeMagnitude) > 1e-6;
  ticksSinceLastSend++;
  if (changed || ticksSinceLastSend >= HEARTBEAT_TICKS) {
   lastSentHPatternTarget = hPatternTarget;
   lastSentHandbrakeMagnitude = handbrakeMagnitude;
   ticksSinceLastSend = 0;
   PacketDistributor.sendToServer(new WheelAuxPayload(hPatternTarget, handbrakeMagnitude));
  }
 }

 private static final ControlSystem.ControlsJoystick[] H_PATTERN_BUTTONS = {
   ControlSystem.ControlsJoystick.CAR_SHIFT_1, ControlSystem.ControlsJoystick.CAR_SHIFT_2, ControlSystem.ControlsJoystick.CAR_SHIFT_3,
   ControlSystem.ControlsJoystick.CAR_SHIFT_4, ControlSystem.ControlsJoystick.CAR_SHIFT_5, ControlSystem.ControlsJoystick.CAR_SHIFT_6,
   ControlSystem.ControlsJoystick.CAR_SHIFT_7, ControlSystem.ControlsJoystick.CAR_SHIFT_8, ControlSystem.ControlsJoystick.CAR_SHIFT_9,
   ControlSystem.ControlsJoystick.CAR_SHIFT_R};
 /** Mirrors IV's own gearNumber derivation in {@code ControlSystem.controlGroundVehicle} exactly
  * (same decompiled branch, same sentinel values) - reads the SAME bindings IV's GUI already
  * lets the player configure, just independent of {@code useShifter}.
  *
  * <p>Ticket comment 53603/53617's explicit correction: checking "is ANY button active" is not
  * enough - a sample is only trustworthy as "released, so neutral" when EVERY slot the player
  * actually bound is itself present; a 6-speed shifter with slots 7-9 left deliberately
  * unconfigured ({@code config.joystickName == null}) is fine and must not count against it,
  * but a configured slot whose device has gone missing makes the WHOLE sample unreliable
  * (its button could be sitting pressed-but-unreadable), so the whole tick is DISABLED rather
  * than risking a false "released" read from an incomplete sample. A keyboard-only player with
  * nothing configured at all (the common case, since {@code hybridShiftEnabled} defaults on)
  * is the same DISABLED outcome via the "no slot configured" branch below. */
 private static int currentHPatternTarget() {
  boolean anyConfigured = false;
  for (var button : H_PATTERN_BUTTONS) {
   if (button.config.joystickName == null) continue;
   anyConfigured = true;
   // Review on PR #13 (fd7fac1): this project's own handbrake axis guard checks the configured
   // index is within range AND component-type-correct before ever reading it; the H-pattern
   // buttons did not, despite IV's own ControlsJoystick.isPressed() calling
   // getJoystickButtonValue(joystickName, buttonIndex) with no such guard at all. Apply the SAME
   // discipline here: a configured slot whose index is out of range, OR whose bound component is
   // actually an AXIS (not a button) on the device, makes the whole sample untrustworthy for the
   // same reason an absent device does - disable outright rather than risk reading garbage.
   String joystickName = button.config.joystickName;
   if (!InterfaceManager.inputInterface.isJoystickPresent(joystickName)) return HPatternShifter.DISABLED;
   int buttonIndex = button.config.buttonIndex;
   if (buttonIndex < 0 || buttonIndex >= InterfaceManager.inputInterface.getJoystickComponentCount(joystickName)) return HPatternShifter.DISABLED;
   if (InterfaceManager.inputInterface.isJoystickComponentAxis(joystickName, buttonIndex)) return HPatternShifter.DISABLED;
  }
  if (!anyConfigured) return HPatternShifter.DISABLED;
  // Review: an ambiguous sample (more than one configured slot reading pressed at once - a
  // device glitch, or a player's shifter genuinely sitting between two detents) must never be
  // silently resolved by picking the lowest index; that is a real gear decision made from bad
  // data. Scan every slot once, count how many read pressed, and only accept a target when
  // EXACTLY one does.
  int pressedCount = 0, pressedForward = -1;
  for (int i = 0; i < 9; i++) if (H_PATTERN_BUTTONS[i].isPressed()) { pressedCount++; pressedForward = i + 1; }
  boolean reversePressed = ControlSystem.ControlsJoystick.CAR_SHIFT_R.isPressed();
  if (reversePressed) pressedCount++;
  if (pressedCount > 1) return HPatternShifter.DISABLED;
  if (pressedCount == 1) return reversePressed ? HPatternShifter.REVERSE_REQUEST : pressedForward;
  return HPatternShifter.NEUTRAL_REQUEST;
 }

 /** Ticket comment 53603/53617: a SEPARATE device, not necessarily the CAR_TURN wheel - empty
  * name falls back to reusing CAR_TURN's own configured joystick. Validates the configured
  * index is both within range AND an actual AXIS component (not just within the combined
  * button+axis component count some IV backends report, per {@code getJoystickComponentCount}'s
  * own documented behavior - an out-of-range OR button-typed index can return 0 from the
  * underlying API, which would silently normalize to a HALF pull under this project's own
  * min/max mapping if not rejected outright here). */
 private static double currentHandbrakeMagnitude() {
  int axis = DivClientConfig.HANDBRAKE_AXIS.get();
  if (axis < 0) return 0.0;
  String configuredName = DivClientConfig.HANDBRAKE_JOYSTICK_NAME.get();
  String joystickName = (configuredName == null || configuredName.isEmpty())
    ? ControlSystem.ControlsJoystick.CAR_TURN.config.joystickName : configuredName;
  if (joystickName == null || joystickName.isEmpty() || !InterfaceManager.inputInterface.isJoystickPresent(joystickName)) return 0.0;
  if (axis >= InterfaceManager.inputInterface.getJoystickComponentCount(joystickName)) return 0.0;
  if (!InterfaceManager.inputInterface.isJoystickComponentAxis(joystickName, axis)) return 0.0;
  float raw = InterfaceManager.inputInterface.getJoystickAxisValue(joystickName, axis);
  return WheelAxisCalibration.magnitude(raw, DivClientConfig.HANDBRAKE_AXIS_MIN.get(), DivClientConfig.HANDBRAKE_AXIS_MAX.get(),
    DivClientConfig.HANDBRAKE_INVERT.get(), DivClientConfig.HANDBRAKE_DEADZONE.get());
 }
}
