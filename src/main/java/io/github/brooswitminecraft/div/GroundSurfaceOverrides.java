package io.github.brooswitminecraft.div;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MINECRAFT-508 review (John, asks #1/#4): configurable defaults for {@link GroundSurface}'s
 * five baseline multipliers - "configurable defaults," not a full override system, same spirit
 * as {@link Overrides}'s own per-vehicle tuning. Deliberately mirrors {@link Overrides}'s own
 * JSON-reload convention (MINECRAFT-505) rather than inventing a second, competing config-
 * loading mechanism in this repo: a lazily-constructed {@link Gson}/{@link Logger} (never a
 * static field - plain unit tests load classes in this package without {@code com.google.gson}
 * or {@code com.mojang.logging}/NeoForge on their classpath at all, so an eager static field of
 * either type would throw {@code NoClassDefFoundError} for every test that merely references
 * this class), the same {@code FMLPaths.CONFIGDIR}-relative file location, the same sparse-
 * nullable-field override shape.
 *
 * <p>Self-review finding (MINECRAFT-508, PR #12 head 5fdc49f): an earlier version of {@link #load}
 * had its OWN throwing catch block, separate from {@link #reload}'s - so a malformed or out-of-
 * range config file present at first server startup would throw {@code IllegalStateException} and
 * abort DIV entirely, while the exact same bad file encountered via a LATER {@link #reload} call
 * would correctly log and fall back. Fixed: both now funnel through the same non-throwing
 * {@link #apply} - {@code load}'s only difference from {@code reload} is that it first best-
 * effort writes a fresh default file when none exists yet (also non-throwing).  ANY failure in
 * {@code apply} (a parse exception, or an {@link Entry#validate} range failure) leaves
 * {@link #overrides} completely untouched - at startup that means the {@code Map.of()} this field
 * already starts with (i.e. plain hardcoded {@link GroundSurface} defaults), at a later
 * {@link #reload} it means last-known-good - and is reported via the returned {@link ReloadResult}
 * plus a logged error, never thrown.
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

    // Deliberately not static fields - see this class's own doc for why.
    private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }
    private static Logger logger() { return LogUtils.getLogger(); }
    private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles-surfaces.json"); }

    /** Reads and parses {@code path} into its raw surfaces map - validation happens once, inside
     * {@link #apply} (which every caller of this method goes through), not here, so it isn't
     * duplicated. */
    private static Map<String, Entry> parseFile(Path path) {
        Config config;
        try {
            config = gson().fromJson(Files.readString(path), Config.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return (config != null && config.surfaces() != null) ? config.surfaces() : Map.of();
    }

    /**
     * The shared all-or-nothing apply {@link #load} and {@link #reload} both use: runs
     * {@code parse}, validates every entry, and only on full success swaps the result into
     * {@link #overrides}. ANY failure (the parse throwing, or {@link Entry#validate} rejecting
     * an entry) leaves {@link #overrides} completely untouched and returns a failed
     * {@link ReloadResult} instead of throwing - package-visible specifically so
     * {@code GroundSurfaceOverridesTest} can exercise this fallback directly for simulated
     * malformed/out-of-range input, without needing real file I/O or Gson on the test classpath.
     */
    static ReloadResult apply(Supplier<Map<String, Entry>> parse) {
        try {
            Map<String, Entry> candidate = parse.get();
            candidate.values().forEach(Entry::validate);
            overrides = Map.copyOf(candidate);
            return ReloadResult.ok(overrides.size());
        } catch (RuntimeException ex) {
            return ReloadResult.failed("Cannot apply surface overrides: " + ex.getMessage());
        }
    }

    /**
     * Never throws - a malformed/out-of-range config file at first startup logs and falls back
     * to the hardcoded {@link GroundSurface} defaults (DIV must still start), exactly like a bad
     * file encountered later via {@link #reload} falls back to last-known-good. See this class's
     * own doc for the self-review finding this fixed.
     */
    static void load() {
        Path path = configPath();
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, gson().toJson(new Config(Map.of())));
            }
        } catch (IOException ex) {
            logger().warn("[DIV] GroundSurfaceOverrides: could not write default config at " + path + ": " + ex.getMessage());
        }
        ReloadResult result = apply(() -> parseFile(path));
        if (!result.success()) {
            logger().error("[DIV] GroundSurfaceOverrides: " + result.message()
                    + " - startup continues with default surface multipliers (no overrides applied)");
        }
    }

    /** Non-throwing hot-reload - same contract as {@link Overrides#reload}: on ANY failure,
     * {@link #overrides} stays exactly what it was before this call (last-known-good), and the
     * failure is reported via the returned {@link ReloadResult} instead of thrown. */
    public static ReloadResult reload() {
        Path path = configPath();
        ReloadResult result = apply(() -> parseFile(path));
        if (!result.success()) {
            logger().error("[DIV] GroundSurfaceOverrides: " + result.message() + " - keeping last-known-good surface overrides");
        }
        return result;
    }

    /** Outcome of a {@link #reload}/{@link #apply} call - {@code success} false means
     * {@link #overrides} is UNCHANGED (last-known-good, or the startup default), never partially
     * applied. */
    public record ReloadResult(boolean success, String message, int surfaceCount) {
        static ReloadResult ok(int surfaceCount) {
            return new ReloadResult(true, "reloaded " + surfaceCount + " surface override(s)", surfaceCount);
        }
        static ReloadResult failed(String message) { return new ReloadResult(false, message, -1); }
    }
}
