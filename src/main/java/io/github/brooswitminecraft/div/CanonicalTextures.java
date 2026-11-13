package io.github.brooswitminecraft.div;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import net.minecraft.resources.ResourceLocation;

/**
 * Per-texel directional-grain analysis of the canonical texture Minecraft or the owning mod ships
 * for a block, never the player's resource pack: a cosmetic pack must not change physics, and the
 * same block must sample the same way on a headless dedicated server with no resource pack
 * installed at all. Reading straight off the classpath rather than through Minecraft's resource-
 * pack-aware {@code ResourceManager} is what gives that guarantee: an active resource pack is a
 * separate zip/folder that is never on the classpath, so a classpath read always returns the
 * asset this mod's own jar (or the vanilla/NeoForge jars it runs alongside) shipped with -
 * independent of user resource packs, deterministic, and server-side-safe, as this ticket
 * requires. Results are cached per texture ({@code computeIfAbsent}, so concurrent lookups of the
 * same not-yet-cached texture are bounded, not unbounded duplicate decode work); call
 * {@link #invalidateAll} if the owning mod/data version ever changes within one JVM (not
 * currently wired to anything - canonical textures are classpath assets, immutable for the life
 * of this process). Adapted from Dynamic Terrain's own {@code CanonicalTextures} (see
 * {@code GroundSurface}'s class doc).
 */
public final class CanonicalTextures {
    private static final Map<ResourceLocation, Optional<DirectionalTexelField>> CACHE = new ConcurrentHashMap<>();

    private CanonicalTextures() {}

    /** @param textureId a model-style texture id, e.g. {@code minecraft:block/oak_log} */
    public static Optional<DirectionalTexelField> at(ResourceLocation textureId) {
        return CACHE.computeIfAbsent(textureId, CanonicalTextures::load);
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    private static Optional<DirectionalTexelField> load(ResourceLocation textureId) {
        String path = "assets/" + textureId.getNamespace() + "/textures/" + textureId.getPath() + ".png";
        try (InputStream in = CanonicalTextures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Optional.empty();
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return Optional.empty();
            }
            return Optional.of(GrainAnalyzer.analyze(image));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
