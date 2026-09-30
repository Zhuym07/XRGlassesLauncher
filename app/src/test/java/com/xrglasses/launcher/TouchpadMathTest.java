package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class TouchpadMathTest {
    @Test public void movementUsesGain() {
        assertEquals(116f, TouchpadMath.move(100, 10, 1.6f, 1920), 0.001f);
    }
    @Test public void movementClampsToDisplayBounds() {
        assertEquals(0f, TouchpadMath.move(2, -100, 1.6f, 1920), 0f);
        assertEquals(1919f, TouchpadMath.move(1910, 100, 1.6f, 1920), 0f);
    }
    @Test public void invalidDisplaySizeCannotProduceNegativeCoordinates() {
        assertEquals(0f, TouchpadMath.move(0, 100, 1.6f, 0), 0f);
    }
    @Test public void tapSlopUsesRadialDistance() {
        assertFalse(TouchpadMath.moved(3, 4, 0, 0, 5));
        assertTrue(TouchpadMath.moved(4, 4, 0, 0, 5));
    }
    @Test public void scrollClampSupportsBothDirections() {
        assertEquals(-240f, TouchpadMath.clamp(-400, -240, 240), 0f);
        assertEquals(240f, TouchpadMath.clamp(400, -240, 240), 0f);
    }
}
