package io.github.brooswitminecraft.div;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Exercises the resolver with injected JSON fixtures shaped like a real log's blockstate/model
 * (vertical vs horizontal axis), rather than real game assets - a pure Java test even though the
 * classpath here has neither live Minecraft registries nor gson. Adapted from Dynamic Terrain's
 * own {@code BlockModelResolverTest} (see {@code GroundSurface}'s class doc), with the key
 * difference this ticket owns: a horizontal (rotated) log axis now RESOLVES to the correct
 * world-face mapping instead of being refused - see {@link #axisZLogResolvesEndGrainOnNorthSouth}
 * and {@link #axisXLogResolvesEndGrainOnEastWest}.
 */
class BlockModelResolverTest {
    /** A cube_column-shaped log: vertical (axis=y) is unrotated; horizontal axes rotate the model.
     * Values taken verbatim from vanilla's real oak_log blockstate. */
    private static BlockModelResolver.JsonLoader logFixture() {
        Map<String, Object> documents = new HashMap<>();
        documents.put("minecraft:blockstates/oak_log", MiniJson.parse("""
                {"variants": {
                    "axis=x": {"model": "minecraft:block/oak_log_horizontal", "x": 90, "y": 90},
                    "axis=y": {"model": "minecraft:block/oak_log"},
                    "axis=z": {"model": "minecraft:block/oak_log_horizontal", "x": 90}
                }}
                """));
        documents.put("minecraft:models/block/oak_log", MiniJson.parse("""
                {"parent": "minecraft:block/cube_column",
                 "textures": {"end": "minecraft:block/oak_log_top", "side": "minecraft:block/oak_log"}}
                """));
        documents.put("minecraft:models/block/cube_column", MiniJson.parse("""
                {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "down": {"texture": "#end"}, "up": {"texture": "#end"},
                    "north": {"texture": "#side"}, "south": {"texture": "#side"},
                    "east": {"texture": "#side"}, "west": {"texture": "#side"}
                }}]}
                """));
        documents.put("minecraft:models/block/oak_log_horizontal", MiniJson.parse("""
                {"parent": "minecraft:block/cube_column",
                 "textures": {"end": "minecraft:block/oak_log_top", "side": "minecraft:block/oak_log"}}
                """));
        return (namespace, path) -> documents.get(namespace + ":" + path);
    }

    @Test void verticalAxisResolvesEndAndSideFacesCorrectly() {
        Map<Face, String> faces = BlockModelResolver
                .resolve("minecraft", "oak_log", Map.of("axis", "y"), logFixture())
                .orElseThrow();
        assertEquals("minecraft:block/oak_log_top", faces.get(Face.UP));
        assertEquals("minecraft:block/oak_log_top", faces.get(Face.DOWN));
        assertEquals("minecraft:block/oak_log", faces.get(Face.NORTH));
        assertEquals("minecraft:block/oak_log", faces.get(Face.EAST));
    }

    /** MINECRAFT-517's own "laid logs" requirement: a log lying north-south shows its end-grain
     * texture on the north/south faces (and bark everywhere else), matching the real in-game look. */
    @Test void axisZLogResolvesEndGrainOnNorthSouth() {
        Map<Face, String> faces = BlockModelResolver
                .resolve("minecraft", "oak_log", Map.of("axis", "z"), logFixture())
                .orElseThrow();
        assertEquals("minecraft:block/oak_log_top", faces.get(Face.NORTH));
        assertEquals("minecraft:block/oak_log_top", faces.get(Face.SOUTH));
        assertEquals("minecraft:block/oak_log", faces.get(Face.UP));
        assertEquals("minecraft:block/oak_log", faces.get(Face.DOWN));
        assertEquals("minecraft:block/oak_log", faces.get(Face.EAST));
        assertEquals("minecraft:block/oak_log", faces.get(Face.WEST));
    }

    /** A log lying east-west shows its end-grain on the east/west faces instead. */
    @Test void axisXLogResolvesEndGrainOnEastWest() {
        Map<Face, String> faces = BlockModelResolver
                .resolve("minecraft", "oak_log", Map.of("axis", "x"), logFixture())
                .orElseThrow();
        assertEquals("minecraft:block/oak_log_top", faces.get(Face.EAST));
        assertEquals("minecraft:block/oak_log_top", faces.get(Face.WEST));
        assertEquals("minecraft:block/oak_log", faces.get(Face.UP));
        assertEquals("minecraft:block/oak_log", faces.get(Face.DOWN));
        assertEquals("minecraft:block/oak_log", faces.get(Face.NORTH));
        assertEquals("minecraft:block/oak_log", faces.get(Face.SOUTH));
    }

    @Test void nonRightAngleRotationReturnsNoEvidenceRatherThanGuessing() {
        BlockModelResolver.JsonLoader loader = fixtureOf(Map.of(
                "minecraft:blockstates/tilted_log", """
                        {"variants": {"": {"model": "minecraft:block/cube", "x": 45}}}""",
                "minecraft:models/block/cube", """
                        {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                            "up": {"texture": "minecraft:block/top"}}}]}"""));
        assertFalse(BlockModelResolver.resolve("minecraft", "tilted_log", Map.of(), loader).isPresent());
    }

    @Test void unmatchedStateValueReturnsNoEvidence() {
        Optional<Map<Face, String>> faces =
                BlockModelResolver.resolve("minecraft", "oak_log", Map.of("axis", "diagonal"), logFixture());
        assertFalse(faces.isPresent());
    }

    @Test void missingBlockstateReturnsNoEvidence() {
        Optional<Map<Face, String>> faces =
                BlockModelResolver.resolve("minecraft", "nonexistent_block", Map.of(), logFixture());
        assertFalse(faces.isPresent());
    }

    @Test void cacheKeyDiffersForDifferentStateValuesOfTheSameBlock() {
        String vertical = BlockModelResolver.cacheKey("minecraft:oak_log", Map.of("axis", "y"));
        String horizontalZ = BlockModelResolver.cacheKey("minecraft:oak_log", Map.of("axis", "z"));
        String horizontalX = BlockModelResolver.cacheKey("minecraft:oak_log", Map.of("axis", "x"));
        assertNotEquals(vertical, horizontalZ);
        assertNotEquals(vertical, horizontalX);
        assertNotEquals(horizontalZ, horizontalX);
    }

    @Test void cacheKeyIsStableForTheSameStateValuesRegardlessOfMapOrdering() {
        Map<String, String> a = new HashMap<>();
        a.put("axis", "y");
        a.put("waterlogged", "false");
        Map<String, String> b = new HashMap<>();
        b.put("waterlogged", "false");
        b.put("axis", "y");
        assertEquals(BlockModelResolver.cacheKey("minecraft:oak_log", a), BlockModelResolver.cacheKey("minecraft:oak_log", b));
    }

    @Test void cacheKeyDiffersForDifferentBlocksWithTheSameStateValues() {
        String oak = BlockModelResolver.cacheKey("minecraft:oak_log", Map.of("axis", "y"));
        String spruce = BlockModelResolver.cacheKey("minecraft:spruce_log", Map.of("axis", "y"));
        assertTrue(!oak.equals(spruce));
    }

    private static BlockModelResolver.JsonLoader fixtureOf(Map<String, String> documents) {
        Map<String, Object> parsed = new HashMap<>();
        documents.forEach((key, json) -> parsed.put(key, MiniJson.parse(json)));
        return (namespace, path) -> parsed.get(namespace + ":" + path);
    }

    @Test void partialExtentElementReturnsNoEvidence() {
        // A bottom slab: one element, but it only fills the bottom half of the block (to.y = 8, not
        // 16). The sampler assumes a face spans the full block; a half-height element doesn't.
        BlockModelResolver.JsonLoader loader = fixtureOf(Map.of(
                "minecraft:blockstates/stone_slab", """
                        {"variants": {"type=bottom": {"model": "minecraft:block/stone_slab"}}}""",
                "minecraft:models/block/stone_slab", """
                        {"elements": [{"from": [0, 0, 0], "to": [16, 8, 16], "faces": {
                            "down": {"texture": "#bottom"}, "up": {"texture": "#top"}}}]}"""));
        assertFalse(BlockModelResolver.resolve("minecraft", "stone_slab", Map.of("type", "bottom"), loader).isPresent());
    }

    @Test void multiElementModelReturnsNoEvidence() {
        // A stair-shaped model: two cuboids. Which one a tire touches on a given world face
        // depends on exactly where it lands, which this doesn't model.
        BlockModelResolver.JsonLoader loader = fixtureOf(Map.of(
                "minecraft:blockstates/stone_stairs", """
                        {"variants": {"": {"model": "minecraft:block/stone_stairs"}}}""",
                "minecraft:models/block/stone_stairs", """
                        {"elements": [
                            {"from": [0, 0, 0], "to": [16, 8, 16], "faces": {"up": {"texture": "#top"}}},
                            {"from": [8, 8, 0], "to": [16, 16, 16], "faces": {"up": {"texture": "#top"}}}
                        ]}"""));
        assertFalse(BlockModelResolver.resolve("minecraft", "stone_stairs", Map.of(), loader).isPresent());
    }

    @Test void elementLevelRotationReturnsNoEvidence() {
        BlockModelResolver.JsonLoader loader = fixtureOf(Map.of(
                "minecraft:blockstates/tilted_block", """
                        {"variants": {"": {"model": "minecraft:block/tilted_block"}}}""",
                "minecraft:models/block/tilted_block", """
                        {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                            "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45},
                            "faces": {"up": {"texture": "#top"}}}]}"""));
        assertFalse(BlockModelResolver.resolve("minecraft", "tilted_block", Map.of(), loader).isPresent());
    }

    @Test void croppedFaceUvIsOmittedButOtherFacesStillResolve() {
        BlockModelResolver.JsonLoader loader = fixtureOf(Map.of(
                "minecraft:blockstates/cropped_block", """
                        {"variants": {"": {"model": "minecraft:block/cropped_block"}}}""",
                "minecraft:models/block/cropped_block", """
                        {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                            "up": {"texture": "minecraft:block/cropped_top", "uv": [0, 0, 8, 8]},
                            "down": {"texture": "minecraft:block/cropped_bottom"}}}]}"""));
        Map<Face, String> faces = BlockModelResolver.resolve("minecraft", "cropped_block", Map.of(), loader).orElseThrow();
        assertFalse(faces.containsKey(Face.UP), "cropped face UV should be omitted, not guessed");
        assertTrue(faces.containsKey(Face.DOWN), "an uncropped face on the same element should still resolve");
    }
}
