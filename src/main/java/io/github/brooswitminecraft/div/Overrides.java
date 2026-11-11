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
 }
 private static Tuning copy(Tuning t) {
  Tuning c = new Tuning();
  c.enabled=t.enabled; c.massKg=t.massKg; c.suspensionTravel=t.suspensionTravel; c.friction=t.friction;
  c.driveForce=t.driveForce; c.brakeForce=t.brakeForce; c.maxSteerDegrees=t.maxSteerDegrees;
  c.hullFillFactor=t.hullFillFactor; c.waterDrag=t.waterDrag; c.propellerForce=t.propellerForce;
  return c;
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
  return result;
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
    Files.writeString(path, gson.toJson(new Config(Map.of("packid:vehicle", new Tuning()))));
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
