package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class TouchSurfaceRegionTest {
    private TouchSurfaceRegion region(float x, float y) {
        return TouchSurfaceRegion.at(x, y, 1080, 2400, 288, 384, 240);
    }
    @Test public void upperLeftIsExit() { assertEquals(TouchSurfaceRegion.EXIT, region(100, 100)); }
    @Test public void bodyLeftIsNotMultitask() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, region(10, 1000));
        assertEquals(TouchSurfaceRegion.TOUCHPAD, region(359, 2111));
    }
    @Test public void bottomLeftThirdIsMultitask() {
        assertEquals(TouchSurfaceRegion.MULTITASK, region(0, 2112));
        assertEquals(TouchSurfaceRegion.MULTITASK, region(359, 2399));
    }
    @Test public void bottomRightIsExternalBack() {
        assertEquals(TouchSurfaceRegion.EXTERNAL_BACK, region(360, 2112));
        assertEquals(TouchSurfaceRegion.EXTERNAL_BACK, region(1079, 2399));
    }
    @Test public void exitEdgesAreExclusive() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, region(384, 100));
        assertEquals(TouchSurfaceRegion.TOUCHPAD, region(100, 240));
    }
    @Test public void bodyRightIsTouchpad() { assertEquals(TouchSurfaceRegion.TOUCHPAD, region(900, 1000)); }
    @Test public void invalidGeometryIsSafe() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, TouchSurfaceRegion.at(0, 0, 0, 0, 96, 128, 80));
        assertEquals(TouchSurfaceRegion.TOUCHPAD, region(-1, 2399));
    }
    @Test public void landscapeKeepsBottomStripOnly() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, TouchSurfaceRegion.at(100, 500, 2400, 1080, 288, 384, 240));
        assertEquals(TouchSurfaceRegion.MULTITASK, TouchSurfaceRegion.at(799, 900, 2400, 1080, 288, 384, 240));
        assertEquals(TouchSurfaceRegion.EXTERNAL_BACK, TouchSurfaceRegion.at(800, 900, 2400, 1080, 288, 384, 240));
    }
}
