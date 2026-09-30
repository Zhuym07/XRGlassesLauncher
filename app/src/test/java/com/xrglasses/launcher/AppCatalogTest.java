package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppCatalogTest {
    @Test public void recentAppsSortFirst() {
        assertTrue(AppCatalog.compare(200, "Z", 100, "A") < 0);
        assertTrue(AppCatalog.compare(0, "A", 100, "Z") > 0);
    }
    @Test public void equalRecencySortsLabelsIgnoringCase() {
        assertTrue(AppCatalog.compare(0, "alpha", 0, "Beta") < 0);
        assertEquals(0, AppCatalog.compare(0, "Alpha", 0, "ALPHA"));
    }
    @Test public void timestampOrderingDoesNotOverflow() {
        assertTrue(AppCatalog.compare(Long.MAX_VALUE, "Z", 0, "A") < 0);
    }
}
