package io.github.brooswitminecraft.div;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Minecraft-facing wrapper around {@link BlockModelResolver}: turns a real {@link BlockState}
 * into the namespace/path/property-values triple the resolver works from, and caches by a key
 * that includes every one of the state's property values, so two states of the same block (a
 * log's vertical vs horizontal axis, say) never share a cached, and therefore wrong, face
 * mapping. Adapted from Dynamic Terrain's own {@code BlockFaceTextures} (see
 * {@code GroundSurface}'s class doc).
 */
public final class BlockFaceTextures {
    private static final Map<String, Optional<Map<Face, Resolution>>> CACHE = new ConcurrentHashMap<>();

    private BlockFaceTextures() {}

    /** See {@link BlockModelResolver.FaceTexture} - same fields, just with {@code textureId}
     * already parsed, since every real caller needs it as a {@link ResourceLocation} anyway. */
    public record Resolution(ResourceLocation textureId, Face modelFace, int rotationXDegrees, int rotationYDegrees) {}

    public static Optional<Resolution> textureFor(BlockState state, Direction face) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Map<String, String> stateValues = stateValues(state);
        String key = BlockModelResolver.cacheKey(blockId.toString(), stateValues);
        Optional<Map<Face, Resolution>> faces = CACHE.computeIfAbsent(key,
                k -> BlockModelResolver.resolve(blockId.getNamespace(), blockId.getPath(), stateValues)
                        .map(BlockFaceTextures::toResolutions));
        return faces.map(m -> m.get(toFace(face)));
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    private static Map<String, String> stateValues(BlockState state) {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            values.put(entry.getKey().getName(), nameOf(entry.getKey(), entry.getValue()));
        }
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String nameOf(Property property, Comparable value) {
        return property.getName(value);
    }

    private static Map<Face, Resolution> toResolutions(Map<Face, BlockModelResolver.FaceTexture> faces) {
        Map<Face, Resolution> resolved = new HashMap<>();
        faces.forEach((face, faceTexture) -> resolved.put(face, new Resolution(
                ResourceLocation.parse(faceTexture.textureId()), faceTexture.modelFace(),
                faceTexture.rotationXDegrees(), faceTexture.rotationYDegrees())));
        return resolved;
    }

    static Face toFace(Direction direction) {
        return switch (direction) {
            case UP -> Face.UP;
            case DOWN -> Face.DOWN;
            case NORTH -> Face.NORTH;
            case SOUTH -> Face.SOUTH;
            case EAST -> Face.EAST;
            case WEST -> Face.WEST;
        };
    }
}
