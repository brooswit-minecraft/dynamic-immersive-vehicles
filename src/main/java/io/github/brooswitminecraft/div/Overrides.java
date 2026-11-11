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
 /** Kept for compatibility with existing call sites/tests that read a single always-populated
  * Tuning - identical to {@link #resolved} with no operator override, i.e. exactly
  * {@link #FALLBACK}'s values. Prefer {@link #resolved} for any new call site: it is the one
  * that actually applies precedence, this is just its zero-override case. */
 public static final Tuning DEFAULT = FALLBACK;
 private record Config(Map<String,Tuning> vehicles) {}
 private static Map<String,Tuning> vehicles = Map.of();
 public static Tuning get(String key) { return vehicles.get(key); }
 public static boolean isOptedOut(String key) { var t = vehicles.get(key); return t != null && Boolean.FALSE.equals(t.enabled); }
 private static Double firstNonNull(Double... values) { for (Double v : values) if (v != null) return v; return null; }
 /**
  * Per-field precedence resolution (ticket comment 53181): explicit operator override (this
  * vehicle's {@code Overrides.get(key)} entry, if any) {@literal >} pack DIV metadata {@literal >}
  * an IV-adapted native value {@literal >} a physically-derived value {@literal >} the
  * documented {@link #FALLBACK}. Only the operator-override and fallback levels are wired today;
  * {@code packMetadata} and {@code ivAdapted} are accepted now (each vehicle's own per-field
  * value, or {@code null} if that source has nothing to say for this field) so later work can
  * plug in the pack-metadata file and native-value adapters per field, independently of each
  * other and without another signature change here. Always returns a fully non-null Tuning -
  * callers never see a sparse one.
  */
 public static Tuning resolved(String key, Tuning packMetadata, Tuning ivAdapted) {
  Tuning override = vehicles.get(key);
  Tuning result = new Tuning();
  result.enabled = override != null ? override.enabled : null;
  result.massKg = override != null ? override.massKg : null; // massKg's own fallback (native v.getMass()) lives in PhysicsBridge, not here - it was always vehicle-instance-dependent, never a flat constant
  result.suspensionTravel = firstNonNull(override!=null?override.suspensionTravel:null, packMetadata!=null?packMetadata.suspensionTravel:null, ivAdapted!=null?ivAdapted.suspensionTravel:null, FALLBACK.suspensionTravel);
  result.friction = firstNonNull(override!=null?override.friction:null, packMetadata!=null?packMetadata.friction:null, ivAdapted!=null?ivAdapted.friction:null, FALLBACK.friction);
  result.driveForce = firstNonNull(override!=null?override.driveForce:null, packMetadata!=null?packMetadata.driveForce:null, ivAdapted!=null?ivAdapted.driveForce:null, FALLBACK.driveForce);
  result.brakeForce = firstNonNull(override!=null?override.brakeForce:null, packMetadata!=null?packMetadata.brakeForce:null, ivAdapted!=null?ivAdapted.brakeForce:null, FALLBACK.brakeForce);
  result.maxSteerDegrees = firstNonNull(override!=null?override.maxSteerDegrees:null, packMetadata!=null?packMetadata.maxSteerDegrees:null, ivAdapted!=null?ivAdapted.maxSteerDegrees:null, FALLBACK.maxSteerDegrees);
  result.hullFillFactor = firstNonNull(override!=null?override.hullFillFactor:null, packMetadata!=null?packMetadata.hullFillFactor:null, ivAdapted!=null?ivAdapted.hullFillFactor:null, FALLBACK.hullFillFactor);
  result.waterDrag = firstNonNull(override!=null?override.waterDrag:null, packMetadata!=null?packMetadata.waterDrag:null, ivAdapted!=null?ivAdapted.waterDrag:null, FALLBACK.waterDrag);
  result.propellerForce = firstNonNull(override!=null?override.propellerForce:null, packMetadata!=null?packMetadata.propellerForce:null, ivAdapted!=null?ivAdapted.propellerForce:null, FALLBACK.propellerForce);
  return result;
 }
 /** {@link #resolved} with neither a pack-metadata nor an IV-adapted source yet available -
  * exactly today's two-level chain (operator override, then the documented fallback), until
  * those two sources actually exist. */
 public static Tuning resolved(String key) { return resolved(key, null, null); }
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
