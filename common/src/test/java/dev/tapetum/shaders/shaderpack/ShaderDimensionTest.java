package dev.tapetum.shaders.shaderpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderDimensionTest {
    @Test
    void mapsVanillaDimensionIds() {
        assertEquals(ShaderDimension.OVERWORLD, ShaderDimension.fromDimensionId("minecraft:overworld"));
        assertEquals(ShaderDimension.NETHER, ShaderDimension.fromDimensionId("minecraft:the_nether"));
        assertEquals(ShaderDimension.END, ShaderDimension.fromDimensionId("minecraft:the_end"));
    }

    @Test
    void moddedIdsUseLegacyOverworldDefaultWithoutMatchingBySuffix() {
        assertEquals(ShaderDimension.OVERWORLD, ShaderDimension.fromDimensionId("mod:the_nether"));
        assertEquals(ShaderDimension.OVERWORLD, ShaderDimension.fromDimensionId("mod:moon"));
    }

    @Test
    void menuHasAnOverworldDefault() {
        assertEquals(ShaderDimension.OVERWORLD, ShaderDimension.fromDimensionId(null));
    }
}
