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
 public static final class Tuning {
  public Boolean enabled;
  public Double massKg;
  public double suspensionTravel = 0.3;
  public double friction = 1.0;
  public double driveForce = 8000;
  public double brakeForce = 12000;
  public double maxSteerDegrees = 30;
  public void validate() {
   if (massKg != null && (!Double.isFinite(massKg) || massKg < 50 || massKg > 100000)) throw new IllegalArgumentException("massKg out of range");
   if (!(suspensionTravel > 0 && suspensionTravel <= 1) || !(friction > 0 && friction <= 3)
     || !(driveForce >= 0 && driveForce <= 100000) || !(brakeForce >= 0 && brakeForce <= 100000)
     || !(maxSteerDegrees > 0 && maxSteerDegrees <= 60)) throw new IllegalArgumentException("invalid vehicle tuning");
  }
 }
 /** Shared defaults applied to any eligible vehicle with no (or no matching) override entry. */
 public static final Tuning DEFAULT = new Tuning();
 private record Config(Map<String,Tuning> vehicles) {}
 private static Map<String,Tuning> vehicles = Map.of();
 public static Tuning get(String key) { return vehicles.get(key); }
 public static boolean isOptedOut(String key) { var t = vehicles.get(key); return t != null && Boolean.FALSE.equals(t.enabled); }
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
