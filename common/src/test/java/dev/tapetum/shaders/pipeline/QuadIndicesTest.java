package dev.tapetum.shaders.pipeline;

import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuadIndicesTest {
    @Test void preservesWindingAndOffsetsAcrossQuads() {
        var buffer = IntBuffer.allocate(12);
        QuadIndices.write(buffer, 8);
        assertArrayEquals(new int[] {0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4}, buffer.array());
        assertEquals(12, buffer.position());
    }

    @Test void validatesBeforeWriting() {
        for (int vertices : new int[] {-4, 1, 5, Integer.MAX_VALUE, QuadIndices.MAX_VERTICES + 4}) {
            assertThrows(IllegalArgumentException.class, () -> QuadIndices.indexCount(vertices));
        }
        var buffer = IntBuffer.allocate(5);
        assertThrows(IllegalArgumentException.class, () -> QuadIndices.write(buffer, 4));
        assertEquals(0, buffer.position());
        assertEquals(0, QuadIndices.indexCount(0));
        assertEquals(1_572_864, QuadIndices.indexCount(QuadIndices.MAX_VERTICES));
    }

    @Test void honorsTheDestinationPosition() {
        var buffer = IntBuffer.allocate(8);
        buffer.position(2);
        QuadIndices.write(buffer, 4);
        assertEquals(8, buffer.position());
        assertEquals(0, buffer.get(2));
        assertEquals(3, buffer.get(6));
    }
}
