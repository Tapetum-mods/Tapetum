// Historical decoder, excluded from the standalone engine build.
package dev.tapetum.shaders.shaderpack.glsl;

/**
 * GLSL input contract for Sodium 0.9.x's 20-byte compact vertices (GLSL 150 or newer).
 * Encoding remains Sodium's responsibility; Tapetum does not bundle its encoder.
 */
public final class SodiumTerrainInputs {
    /** Bind before linking; explicit GLSL layout qualifiers would reject older GLSL 150 packs. */
    public static final java.util.Map<String, Integer> ATTRIBUTE_BINDINGS = java.util.Map.of(
        "tapetum_PackedPosition", 0, "tapetum_Color", 1, "tapetum_PackedUV", 2, "tapetum_PackedLight", 3);

    private static final String LIGHT_DATA = "in uvec4 tapetum_PackedLight;";

    private static final String POSITION = """
        in uvec2 tapetum_PackedPosition;
        uniform vec3 tapetum_RegionOffset;
        vec3 tapetum_terrainPosition() {
            uvec3 shift = uvec3(0u, 10u, 20u);
            uvec3 upper = (uvec3(tapetum_PackedPosition.x) >> shift) & 1023u;
            uvec3 lower = (uvec3(tapetum_PackedPosition.y) >> shift) & 1023u;
            vec3 local = vec3(upper * 1024u + lower) / 32768.0 - 8.0;
            uint section = tapetum_PackedLight.w;
            vec3 sectionOrigin = vec3(section / 32u, section % 4u, (section / 4u) % 8u) * 16.0;
            return local + sectionOrigin + tapetum_RegionOffset;
        }
        """;

    private static final String UV = """
        in uvec2 tapetum_PackedUV;
        uniform float tapetum_TexCoordShrink;
        vec2 tapetum_terrainUV() {
            vec2 direction = vec2(tapetum_PackedUV >> 15u) * 2.0 - 1.0;
            return vec2(tapetum_PackedUV & 32767u) / 32768.0 + direction * tapetum_TexCoordShrink;
        }
        """;

    private static final String LIGHT = """
        vec2 tapetum_terrainLight() {
            return max(vec2(tapetum_PackedLight.xy) - 8.0, vec2(0.0));
        }
        """;

    private SodiumTerrainInputs() {}

    static void declare(FixedFunctionRewriter rewriter) {
        // Functions must follow their inputs, and the shared light/section attribute occurs once.
        rewriter.require("tapetum_terrainPosition", LIGHT_DATA)
            .require("tapetum_terrainLight", LIGHT_DATA)
            .require("tapetum_terrainPosition", POSITION)
            .require("tapetum_terrainUV", UV)
            .require("tapetum_terrainLight", LIGHT);
    }
}
