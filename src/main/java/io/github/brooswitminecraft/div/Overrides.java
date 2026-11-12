package io.github.brooswitminecraft.div;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
/**
 * Optional per-car tuning/override, keyed by packID:systemName. Physics now applies to every
 * eligible vehicle by default (see PhysicsBridge); a car needs no entry here at all. An entry's
 * {@code enabled} is the one activation-relevant field, and it is an opt-OUT only: leaving it
 * unset (or absent) uses the default classification-based behavior, {@code true} is a no-op kept
 * for backwards compatibility with earlier opt-in configs, and {@code false} excludes that one
 * car even if its category would otherwise be eligible. Every other field only tunes an already-
 * eligible car. Restart to reload.
 */
public final class Overrides {
 /**
  * Sparse by design (ticket comment 53181): every tunable field but {@code enabled} is a boxed
  * {@code Double}/{@code Boolean}, not a primitive with a literal default - so Gson leaves an
  * OMITTED field {@code null} instead of silently filling in today's shared number. That null is
  * the signal {@link #resolved} needs to fall through to the next precedence level (pack DIV
  * metadata, an IV-adapted native value, a physically-derived value, then the documented
  * fallback below) rather than an operator's config looking like it explicitly chose 8000N drive
  * force when it never mentioned drive force at all. Only the fallback level exists today - the
  * other three are intentionally not wired yet (ticket comment 53177/53179/53181: native
  * drive/brake/steering/tire semantics and the pack-metadata file format are still being
  * resolved) - {@link #resolved} already has the right shape to add them without another
  * breaking change to this class.
  */
 public static final class Tuning {
  public Boolean enabled;
  public Double massKg;
  public Double suspensionTravel;
  public Double friction;
  public Double driveForce;
  public Double brakeForce;
  public Double maxSteerDegrees;
  // Float-only hulls (boats/pontoons) only: hullFillFactor shrinks the chassis bounding-box
  // volume down to the fraction of it the actual hull displaces (a real hull is not a solid
  // box), waterDrag scales quadratic water drag, and propellerForce is the thrust budget split
  // across installed propellers.
  public Double hullFillFactor;
  public Double waterDrag;
  public Double propellerForce;
  // Opt-in self-righting assist (ticket comment 53182/53188) - ALL FIVE must be explicitly set
  // together for the assist to activate at all (see Overrides.selfRightingConfig); there is no
  // flat fallback for any of these fields, unlike the physics defaults above - an unconfigured
  // vehicle gets no assist, full stop. Units/semantics match io.github.brooswitminecraft.div.
  // experimental.SelfRightingAssist.Config exactly (strength N*m, damping N*m*s/rad, maxTorque
  // N*m, both fade angles in degrees up to that class's own HARD_SAFETY_CAP_DEGREES).
  public Double selfRightStrength;
  public Double selfRightDamping;
  public Double selfRightMaxTorque;
  public Double selfRightFadeStartDegrees;
  public Double selfRightFadeEndDegrees;
  // Two-half chassis (ticket comment 53236/53240) - upper-half mass fraction f, defaulting to
  // 0.5 (an even split) via FALLBACK below, same simple-field shape as suspensionTravel/friction/
  // etc., NOT an all-or-nothing group like selfRight*. See TwoBodyChassis.Config for the actual
  // geometry/mass/inertia math this value feeds; io.github.brooswitminecraft.div.Overrides#
  // twoBodyConfig builds that Config from a resolved Tuning, validating against the vehicle's
  // real total mass (not just f in isolation - see TwoBodyChassis.Config.validateAgainstTotalMass's
  // own doc for why that distinction matters).
  public Double upperMassFraction;
  // Ticket 53331/53332: John's hands-on feedback ("flops over to the side when I turn...also
  // maybe less grip or side force from the tires so the thing slides, rather than violently
  // flipping") - a narrow, additive knob scaling ONLY the lateral slip-canceling target inside
  // MultiContactFrictionSolver.solveFriction, left of the existing shared friction-circle clamp.
  // Deliberately separate from `friction` (which still governs the combined circle, including
  // drive/brake grip) per John's own explicit caution against "blindly reducing shared friction
  // if that also destroys drive/brake grip". 1.0 (FALLBACK) is a byte-identical no-op; values
  // below 1.0 let the tire slide sideways more readily before the circle clamp would otherwise
  // allow, without touching longitudinal authority at all.
  public Double lateralGripMultiplier;
  /** Validates only fields this entry actually SET - an omitted (null) field is exactly as
   * valid as a present one; it just means "no operator override at this precedence level",
   * never "zero" or "the documented fallback value" implicitly. */
  public void validate() {
   if (massKg != null && (!Double.isFinite(massKg) || massKg < 50 || massKg > 100000)) throw new IllegalArgumentException("massKg out of range");
   if (suspensionTravel != null && !(suspensionTravel > 0 && suspensionTravel <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (friction != null && !(friction > 0 && friction <= 3)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (driveForce != null && !(driveForce >= 0 && driveForce <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (brakeForce != null && !(brakeForce >= 0 && brakeForce <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (maxSteerDegrees != null && !(maxSteerDegrees > 0 && maxSteerDegrees <= 60)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (hullFillFactor != null && !(hullFillFactor > 0 && hullFillFactor <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (waterDrag != null && !(waterDrag >= 0 && waterDrag <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (propellerForce != null && !(propellerForce >= 0 && propellerForce <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightStrength != null && !(Double.isFinite(selfRightStrength) && selfRightStrength >= 0)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightDamping != null && !(Double.isFinite(selfRightDamping) && selfRightDamping >= 0)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightMaxTorque != null && !(Double.isFinite(selfRightMaxTorque) && selfRightMaxTorque >= 0)) throw new IllegalArgumentException("invalid vehicle tuning");
   double hardCap = io.github.brooswitminecraft.div.experimental.SelfRightingAssist.HARD_SAFETY_CAP_DEGREES;
   if (selfRightFadeStartDegrees != null && !(Double.isFinite(selfRightFadeStartDegrees) && selfRightFadeStartDegrees >= 0 && selfRightFadeStartDegrees <= hardCap)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightFadeEndDegrees != null && !(Double.isFinite(selfRightFadeEndDegrees) && selfRightFadeEndDegrees > (selfRightFadeStartDegrees!=null?selfRightFadeStartDegrees:0) && selfRightFadeEndDegrees <= hardCap)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Strictly between 0 and 1, never 0/1/out-of-range - matches TwoBodyChassis.Config's own
   // constructor validation exactly, checked again here so a malformed config entry fails loudly
   // at load time rather than only when a vehicle actually tries to resolve a two-body config.
   if (upperMassFraction != null && !(Double.isFinite(upperMassFraction) && upperMassFraction > 0 && upperMassFraction < 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Strictly between 0 (exclusive, never a true zero-lateral-grip vehicle - that would be a
   // physically nonsensical ice-skate tire) and 1 (inclusive - the no-op/unconfigured value).
   // Never above 1: this knob is explicitly for REDUCING lateral grip (John's own "less grip...
   // so it slides"), not for granting more lateral authority than the real friction circle.
   if (lateralGripMultiplier != null && !(Double.isFinite(lateralGripMultiplier) && lateralGripMultiplier > 0 && lateralGripMultiplier <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
  }
 }
 /** Documented fallback for every field, used only once every higher precedence level (operator
  * override, pack DIV metadata, IV-adapted native value, physically-derived value) has nothing
  * to say - the bottom of the chain, never the implicit result of an omitted field on its own.
  * Values/units/provenance unchanged from the shared numbers this ticket's physics work has
  * always used: driveForce 8000N, brakeForce 12000N, suspensionTravel 0.3m, friction mu=1.0
  * (dimensionless), maxSteerDegrees 30deg, hullFillFactor 0.5 (dimensionless), waterDrag 400
  * (solver-internal units), propellerForce 6000N. */
 private static final Tuning FALLBACK = new Tuning();
 static {
  FALLBACK.suspensionTravel=0.3; FALLBACK.friction=1.0; FALLBACK.driveForce=8000.0; FALLBACK.brakeForce=12000.0;
  FALLBACK.maxSteerDegrees=30.0; FALLBACK.hullFillFactor=0.5; FALLBACK.waterDrag=400.0; FALLBACK.propellerForce=6000.0;
  FALLBACK.upperMassFraction=0.5;
  FALLBACK.lateralGripMultiplier=1.0;
 }
 private static Tuning copy(Tuning t) {
  Tuning c = new Tuning();
  c.enabled=t.enabled; c.massKg=t.massKg; c.suspensionTravel=t.suspensionTravel; c.friction=t.friction;
  c.driveForce=t.driveForce; c.brakeForce=t.brakeForce; c.maxSteerDegrees=t.maxSteerDegrees;
  c.hullFillFactor=t.hullFillFactor; c.waterDrag=t.waterDrag; c.propellerForce=t.propellerForce;
  c.selfRightStrength=t.selfRightStrength; c.selfRightDamping=t.selfRightDamping; c.selfRightMaxTorque=t.selfRightMaxTorque;
  c.selfRightFadeStartDegrees=t.selfRightFadeStartDegrees; c.selfRightFadeEndDegrees=t.selfRightFadeEndDegrees;
  c.upperMassFraction=t.upperMassFraction; c.lateralGripMultiplier=t.lateralGripMultiplier;
  return c;
 }
 /** Builds a {@code SelfRightingAssist.Config} from a resolved Tuning (ticket comment 53182/
  * 53188) - ALL FIVE selfRight* fields must be present together, or this returns {@code
  * SelfRightingAssist.OFF}. Deliberately all-or-nothing: a partially-configured assist has no
  * sensible meaning (what damping should apply with a strength but no configured maxTorque?),
  * so a vehicle must opt in with every field or get no assist at all, never a silent mix with
  * some other default. */
 public static io.github.brooswitminecraft.div.experimental.SelfRightingAssist.Config selfRightingConfig(Tuning resolved) {
  if (resolved.selfRightStrength==null || resolved.selfRightDamping==null || resolved.selfRightMaxTorque==null
    || resolved.selfRightFadeStartDegrees==null || resolved.selfRightFadeEndDegrees==null) {
   return io.github.brooswitminecraft.div.experimental.SelfRightingAssist.OFF;
  }
  return new io.github.brooswitminecraft.div.experimental.SelfRightingAssist.Config(
    resolved.selfRightStrength, resolved.selfRightDamping, resolved.selfRightMaxTorque,
    resolved.selfRightFadeStartDegrees, resolved.selfRightFadeEndDegrees);
 }
 /** Review (comment 53184): the shared {@link #FALLBACK} singleton must never be handed out
  * directly - every field on {@code Tuning} is public and mutable (required for Gson), so any
  * caller holding the literal shared instance could corrupt the fallback every other vehicle
  * resolves against. Returns a fresh, independently-mutable copy every call. Prefer
  * {@link #resolved} for any new call site - this is just its zero-override, zero-metadata,
  * zero-native-value case. */
 public static Tuning defaultTuning() { return copy(FALLBACK); }
 private record Config(Map<String,Tuning> vehicles) {}
 private static Map<String,Tuning> vehicles = Map.of();
 public static Tuning get(String key) { return vehicles.get(key); }
 public static boolean isOptedOut(String key) { var t = vehicles.get(key); return t != null && Boolean.FALSE.equals(t.enabled); }
 private static Double firstNonNull(Double... values) { for (Double v : values) if (v != null) return v; return null; }
 private static Boolean firstNonNullBoolean(Boolean... values) { for (Boolean v : values) if (v != null) return v; return null; }
 /**
  * Per-field precedence resolution (ticket comment 53181/53184): explicit operator override
  * (this vehicle's {@code Overrides.get(key)} entry, if any) {@literal >} pack DIV metadata
  * {@literal >} an IV-adapted native value {@literal >} a physically-derived value {@literal >}
  * the documented {@link #FALLBACK}. Only the operator-override and fallback levels are wired to
  * a real source today; {@code packMetadata}/{@code ivAdapted}/{@code physicallyDerived} are
  * accepted now (each vehicle's own per-field value, or {@code null} if that source has nothing
  * to say for this field) so later work can plug in the pack-metadata file and native-value
  * adapters per field, independently of each other, without another signature change here.
  * EVERY field - including {@code enabled} and {@code massKg}, which an earlier version of this
  * method left checking only the operator override - goes through the full chain; {@code
  * massKg} and {@code enabled} simply have no flat {@link #FALLBACK} entry (massKg's own
  * fallback, the vehicle's own native mass, is vehicle-INSTANCE-dependent and lives in
  * PhysicsBridge, not a flat constant; {@code enabled} absent at every level means "use the
  * default classification-based eligibility", not forced on or off). Always returns a fully
  * non-null Tuning for every field that DOES have a fallback - callers never see a sparse one
  * for those; {@code enabled}/{@code massKg} can still legitimately come back {@code null}.
  *
  * <p>{@code packMetadata} and {@code ivAdapted}, if supplied, are validated (comment 53184)
  * before being used - a malformed upstream source must fail loudly here, the same as a
  * malformed operator-override entry already does in {@link #load}, not be silently trusted.
  */
 public static Tuning resolved(String key, Tuning packMetadata, Tuning ivAdapted, Tuning physicallyDerived) {
  if (packMetadata != null) packMetadata.validate();
  if (ivAdapted != null) ivAdapted.validate();
  if (physicallyDerived != null) physicallyDerived.validate();
  Tuning override = vehicles.get(key);
  Tuning result = new Tuning();
  result.enabled = firstNonNullBoolean(override!=null?override.enabled:null, packMetadata!=null?packMetadata.enabled:null, ivAdapted!=null?ivAdapted.enabled:null, physicallyDerived!=null?physicallyDerived.enabled:null);
  result.massKg = firstNonNull(override!=null?override.massKg:null, packMetadata!=null?packMetadata.massKg:null, ivAdapted!=null?ivAdapted.massKg:null, physicallyDerived!=null?physicallyDerived.massKg:null);
  result.suspensionTravel = firstNonNull(override!=null?override.suspensionTravel:null, packMetadata!=null?packMetadata.suspensionTravel:null, ivAdapted!=null?ivAdapted.suspensionTravel:null, physicallyDerived!=null?physicallyDerived.suspensionTravel:null, FALLBACK.suspensionTravel);
  result.friction = firstNonNull(override!=null?override.friction:null, packMetadata!=null?packMetadata.friction:null, ivAdapted!=null?ivAdapted.friction:null, physicallyDerived!=null?physicallyDerived.friction:null, FALLBACK.friction);
  result.driveForce = firstNonNull(override!=null?override.driveForce:null, packMetadata!=null?packMetadata.driveForce:null, ivAdapted!=null?ivAdapted.driveForce:null, physicallyDerived!=null?physicallyDerived.driveForce:null, FALLBACK.driveForce);
  result.brakeForce = firstNonNull(override!=null?override.brakeForce:null, packMetadata!=null?packMetadata.brakeForce:null, ivAdapted!=null?ivAdapted.brakeForce:null, physicallyDerived!=null?physicallyDerived.brakeForce:null, FALLBACK.brakeForce);
  result.maxSteerDegrees = firstNonNull(override!=null?override.maxSteerDegrees:null, packMetadata!=null?packMetadata.maxSteerDegrees:null, ivAdapted!=null?ivAdapted.maxSteerDegrees:null, physicallyDerived!=null?physicallyDerived.maxSteerDegrees:null, FALLBACK.maxSteerDegrees);
  result.hullFillFactor = firstNonNull(override!=null?override.hullFillFactor:null, packMetadata!=null?packMetadata.hullFillFactor:null, ivAdapted!=null?ivAdapted.hullFillFactor:null, physicallyDerived!=null?physicallyDerived.hullFillFactor:null, FALLBACK.hullFillFactor);
  result.waterDrag = firstNonNull(override!=null?override.waterDrag:null, packMetadata!=null?packMetadata.waterDrag:null, ivAdapted!=null?ivAdapted.waterDrag:null, physicallyDerived!=null?physicallyDerived.waterDrag:null, FALLBACK.waterDrag);
  result.propellerForce = firstNonNull(override!=null?override.propellerForce:null, packMetadata!=null?packMetadata.propellerForce:null, ivAdapted!=null?ivAdapted.propellerForce:null, physicallyDerived!=null?physicallyDerived.propellerForce:null, FALLBACK.propellerForce);
  // Self-righting (comment 53182/53188): no FALLBACK entry - stays null (meaning "assist off",
  // via selfRightingConfig's all-or-nothing check) unless some level actually sets it.
  result.selfRightStrength = firstNonNull(override!=null?override.selfRightStrength:null, packMetadata!=null?packMetadata.selfRightStrength:null, ivAdapted!=null?ivAdapted.selfRightStrength:null, physicallyDerived!=null?physicallyDerived.selfRightStrength:null);
  result.selfRightDamping = firstNonNull(override!=null?override.selfRightDamping:null, packMetadata!=null?packMetadata.selfRightDamping:null, ivAdapted!=null?ivAdapted.selfRightDamping:null, physicallyDerived!=null?physicallyDerived.selfRightDamping:null);
  result.selfRightMaxTorque = firstNonNull(override!=null?override.selfRightMaxTorque:null, packMetadata!=null?packMetadata.selfRightMaxTorque:null, ivAdapted!=null?ivAdapted.selfRightMaxTorque:null, physicallyDerived!=null?physicallyDerived.selfRightMaxTorque:null);
  result.selfRightFadeStartDegrees = firstNonNull(override!=null?override.selfRightFadeStartDegrees:null, packMetadata!=null?packMetadata.selfRightFadeStartDegrees:null, ivAdapted!=null?ivAdapted.selfRightFadeStartDegrees:null, physicallyDerived!=null?physicallyDerived.selfRightFadeStartDegrees:null);
  result.selfRightFadeEndDegrees = firstNonNull(override!=null?override.selfRightFadeEndDegrees:null, packMetadata!=null?packMetadata.selfRightFadeEndDegrees:null, ivAdapted!=null?ivAdapted.selfRightFadeEndDegrees:null, physicallyDerived!=null?physicallyDerived.selfRightFadeEndDegrees:null);
  result.upperMassFraction = firstNonNull(override!=null?override.upperMassFraction:null, packMetadata!=null?packMetadata.upperMassFraction:null, ivAdapted!=null?ivAdapted.upperMassFraction:null, physicallyDerived!=null?physicallyDerived.upperMassFraction:null, FALLBACK.upperMassFraction);
  result.lateralGripMultiplier = firstNonNull(override!=null?override.lateralGripMultiplier:null, packMetadata!=null?packMetadata.lateralGripMultiplier:null, ivAdapted!=null?ivAdapted.lateralGripMultiplier:null, physicallyDerived!=null?physicallyDerived.lateralGripMultiplier:null, FALLBACK.lateralGripMultiplier);
  return result;
 }
 /** Builds a {@code TwoBodyChassis.Config} from a resolved Tuning's {@code upperMassFraction}
  * (ticket comment 53236/53240) - always resolves to a value (0.5 fallback), unlike
  * {@link #selfRightingConfig}'s all-or-nothing gate, since an even split is a sensible default
  * for every vehicle rather than "feature off". Validates against the vehicle's REAL total mass
  * (not just f in isolation) so a vehicle whose actual mass makes a configured fraction unsafe
  * (either half under the practical minimum) fails loudly here rather than silently producing a
  * near-massless half body downstream. */
 public static io.github.brooswitminecraft.div.experimental.TwoBodyChassis.Config twoBodyConfig(Tuning resolved, double totalMassKg) {
  var config = new io.github.brooswitminecraft.div.experimental.TwoBodyChassis.Config(resolved.upperMassFraction);
  config.validateAgainstTotalMass(totalMassKg);
  return config;
 }
 /** {@link #resolved} with no pack-metadata/IV-adapted/physically-derived source yet available -
  * exactly today's two-level chain (operator override, then the documented fallback), until
  * those three sources actually exist. */
 public static Tuning resolved(String key) { return resolved(key, null, null, null); }
 /** Test-only seam (ticket comment 53184: tests must exercise an actual explicit operator entry,
  * not only the unconfigured-key case) - {@link #load} is the only other writer, and it needs a
  * live FML config directory a plain unit test cannot provide. Package-private: only this
  * package's own tests may call it. */
 static void setForTest(Map<String,Tuning> v) { vehicles = v; }
 static void load() {
  Path path = FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json");
  Gson gson = new GsonBuilder().setPrettyPrinting().create();
  try {
   if (!Files.exists(path)) {
    // mtsofficialpack:quad gets a real, ACTIVE self-right entry on a fresh install (ticket
    // comment 53182/53188's "explicit quad roll-assist" ask). This is a live, applied
    // configuration the moment a fresh install's quad is driven, not a commented-out or inert
    // example (comment 53189's correction: label it accurately as what it actually is) - an
    // operator who doesn't want it must explicitly remove/zero it. selfRightingConfig stays OFF
    // for every OTHER vehicle with no entry at all. Values are a deliberately gentle "cheat a
    // little" assist for this specific ~350kg ATV, not a generic default: strength/damping/
    // maxTorque chosen small relative to this vehicle's own inertiaBody.z (~44 kg*m^2, so even
    // the 600N*m maxTorque caps angular acceleration well under 15rad/s^2), fadeStart/fadeEnd
    // (10/50deg) keeps it acting only while basically upright. An existing installation's own
    // config file is NOT touched by this - only a genuinely fresh (non-existent) file gets it;
    // merging these same five fields into an existing operator config is a separate, manual
    // (or future tooling) step, not something this loader does automatically.
    var quadExample = new Tuning();
    quadExample.selfRightStrength=200.0; quadExample.selfRightDamping=80.0; quadExample.selfRightMaxTorque=600.0;
    quadExample.selfRightFadeStartDegrees=10.0; quadExample.selfRightFadeEndDegrees=50.0;
    Files.writeString(path, gson.toJson(new Config(Map.of("packid:vehicle", new Tuning(), "mtsofficialpack:quad", quadExample))));
   }
   Config config = gson.fromJson(Files.readString(path), Config.class);
   if (config == null || config.vehicles == null) throw new IllegalArgumentException("missing vehicles map");
   config.vehicles.values().forEach(Tuning::validate);
   vehicles = Map.copyOf(config.vehicles);
  } catch (IOException | RuntimeException ex) {
   throw new IllegalStateException("Cannot load " + path + ": " + ex.getMessage(), ex);
  }
 }
}
