package io.github.brooswitminecraft.div.client;
import net.neoforged.neoforge.common.ModConfigSpec;
/**
 * Client-only options for the wheel-aux input (ticket MINECRAFT-504, comment 53572): simultaneous
 * H-pattern + paddle shifting, and a separately configurable analog handbrake. Everything this
 * project already reuses from IV's own binding system (steering/pedals/paddle-shift-via-
 * CAR_SHIFT_U/D) stays configured in IV's OWN controls GUI, unaffected by this file - this is
 * strictly the small amount this project itself reads directly (the H-pattern buttons, read via
 * IV's existing CAR_SHIFT_1-R bindings regardless of its useShifter setting, needs no config of
 * its own here) plus the handbrake axis IV has no control for at all.
 */
public final class DivClientConfig {
 public static final ModConfigSpec SPEC;
 public static final ModConfigSpec.BooleanValue HYBRID_SHIFT_ENABLED;
 public static final ModConfigSpec.ConfigValue<String> HANDBRAKE_JOYSTICK_NAME;
 public static final ModConfigSpec.IntValue HANDBRAKE_AXIS;
 public static final ModConfigSpec.DoubleValue HANDBRAKE_AXIS_MIN;
 public static final ModConfigSpec.DoubleValue HANDBRAKE_AXIS_MAX;
 public static final ModConfigSpec.BooleanValue HANDBRAKE_INVERT;
 public static final ModConfigSpec.DoubleValue HANDBRAKE_DEADZONE;
 static {
  ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
  builder.push("wheel");
  HYBRID_SHIFT_ENABLED = builder
    .comment("Read IV's own H-pattern shift buttons (CAR_SHIFT_1-R, bound in IV's own controls GUI) directly, independent of IV's useShifter setting, so a physical H-pattern shifter and paddle up/down (bound to CAR_SHIFT_U/D with useShifter=false) can both work at once. Disable to fall back to plain native behavior only.")
    .define("hybridShiftEnabled", true);
  HANDBRAKE_JOYSTICK_NAME = builder
    .comment("Exact joystick name (see IV's own controls GUI, or the device list it logs at startup) for a SEPARATE dedicated handbrake device - e.g. a future standalone USB handbrake, not necessarily the same wheel used for steering. Empty = reuse whatever device is already configured for CAR_TURN in IV's own controls GUI.")
    .define("handbrakeJoystickName", "");
  HANDBRAKE_AXIS = builder
    .comment("Joystick axis index on the device above for an analog handbrake, or -1 to disable. No native IV control corresponds to this - it is this project's own addition, separate from the service brake and the parking-brake toggle.")
    .defineInRange("handbrakeAxis", -1, -1, 31);
  HANDBRAKE_AXIS_MIN = builder.comment("Raw axis value at handbrake fully released.")
    .defineInRange("handbrakeAxisMin", -1.0, -1.0, 1.0);
  HANDBRAKE_AXIS_MAX = builder.comment("Raw axis value at handbrake fully pulled.")
    .defineInRange("handbrakeAxisMax", 1.0, -1.0, 1.0);
  HANDBRAKE_INVERT = builder.comment("Flip which end of the axis is pulled vs released.")
    .define("handbrakeInvert", false);
  HANDBRAKE_DEADZONE = builder.comment("Travel (0-1) near released that still counts as fully released.")
    .defineInRange("handbrakeDeadzone", 0.03, 0.0, 0.9);
  builder.pop();
  SPEC = builder.build();
 }
 private DivClientConfig() {}
}
