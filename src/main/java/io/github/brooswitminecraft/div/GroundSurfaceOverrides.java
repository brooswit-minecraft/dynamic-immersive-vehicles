package io.github.brooswitminecraft.div;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * MINECRAFT-508 review (John, asks #1/#4): configurable defaults for {@link GroundSurface}'s
 * five baseline multipliers - "configurable defaults," not a full override system, same spirit
 * as {@link Overrides}'s own per-vehicle tuning. Deliberately mirrors {@link Overrides}'s own
 * JSON-reload convention (MINECRAFT-505) rather than inventing a second, competing config-
 * loading mechanism in this repo: a lazily-constructed {@link Gson} (never a static field - see
 * {@link Overrides}'s own {@code gson()} doc for why: plain unit tests load classes in this
 * package without {@code com.google.gson} on their classpath at all, so an eager static field
 * would throw {@code NoClassDefFoundError} for every test that merely references this class),
 * the same {@code FMLPaths.CONFIGDIR}-relative file location, the same sparse-nullable-field
 * override shape, and the same non-throwing {@link #reload} contract: ANY failure (missing/
 * malformed file, an out-of-range field) leaves the previously-active overrides completely
 * untouched (last-known-good), returned as a {@link ReloadResult} instead of thrown.
 */
public final class GroundSurfaceOverrides {
    private GroundSurfaceOverrides() {}

    /** Sparse per-surface override - a null field means "no operator override for this field,"
     * never zero or the hardcoded {@link GroundSurface} default (same convention as
     * {@link Overrides.Tuning}'s boxed {@code Double} fields). */
    public static final class Entry {
        public Double frictionMultiplier;
        public Double rollingResistanceMultiplier;

        public void validate() {
            if (frictionMultiplier != null && !(frictionMultiplier >= 0 && frictionMultiplier <= 3))
                throw new IllegalArgumentException("frictionMultiplier out of range");
            if (rollingResistanceMultiplier != null && !(rollingResistanceMultiplier >= 0 && rollingResistanceMultiplier <= 10))
                throw new IllegalArgumentException("rollingResistanceMultiplier out of range");
        }
    }

    private record Config(Map<String, Entry> surfaces) {}

    private static Map<String, Entry> overrides = Map.of();

    /**
     * Applies any configured override for {@code surfaceName} ("pavement"/"dirt"/"grass"/
     * "gravel"/"ice") onto {@code fallback} field-by-field - a missing entry, or a present entry
     * with a null field, falls back to {@code fallback}'s own value for that field exactly. Pure
     * and Minecraft/Gson-free, so it's directly unit-testable (see {@code setForTest}).
     */
    public static GroundSurface effective(String surfaceName, GroundSurface fallback) {
        Entry entry = overrides.get(surfaceName);
        if (entry == null) return fallback;
        double friction = entry.frictionMultiplier != null ? entry.frictionMultiplier : fallback.frictionMultiplier();
        double rolling = entry.rollingResistanceMultiplier != null ? entry.rollingResistanceMultiplier : fallback.rollingResistanceMultiplier();
        return new GroundSurface(friction, rolling);
    }

    static void setForTest(Map<String, Entry> overridesForTest) { overrides = overridesForTest; }

    // Deliberately not a static field - see this class's own doc for why.
    private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }
    private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles-surfaces.json"); }

    private static Map<String, Entry> readAndValidate(Path path) throws IOException {
        Config config = gson().fromJson(Files.readString(path), Config.class);
        Map<String, Entry> surfaces = (config != null && config.surfaces() != null) ? config.surfaces() : Map.of();
        surfaces.values().forEach(Entry::validate);
        return surfaces;
    }

    static void load() {
        Path path = configPath();
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, gson().toJson(new Config(Map.of())));
            }
            overrides = Map.copyOf(readAndValidate(path));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Cannot load " + path + ": " + ex.getMessage(), ex);
        }
    }

    /** Non-throwing hot-reload - same contract as {@link Overrides#reload}: on ANY failure,
     * {@link #overrides} stays exactly what it was before this call (last-known-good), and the
     * failure is reported via the returned {@link ReloadResult} instead of thrown. */
    public static ReloadResult reload() {
        Path path = configPath();
        try {
            Map<String, Entry> read = readAndValidate(path);
            overrides = Map.copyOf(read);
            return ReloadResult.ok(overrides.size());
        } catch (IOException | RuntimeException ex) {
            return ReloadResult.failed("Cannot reload " + path + ": " + ex.getMessage());
        }
    }

    /** Outcome of a {@link #reload} call - {@code success} false means the previous overrides
     * map is UNCHANGED (last-known-good), never partially applied. */
    public record ReloadResult(boolean success, String message, int surfaceCount) {
        static ReloadResult ok(int surfaceCount) {
            return new ReloadResult(true, "reloaded " + surfaceCount + " surface override(s)", surfaceCount);
        }
        static ReloadResult failed(String message) { return new ReloadResult(false, message, -1); }
    }
}
