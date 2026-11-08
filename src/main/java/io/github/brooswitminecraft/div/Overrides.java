package io.github.brooswitminecraft.div;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
/** Explicit per-car opt-in until the initial compatibility matrix is validated. Restart to reload. */
public final class Overrides {
 public static final class Tuning {
  public boolean enabled;
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
 private record Config(Map<String,Tuning> vehicles) {}
 private static Map<String,Tuning> vehicles = Map.of();
 public static Tuning get(String key) { return vehicles.get(key); }
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
