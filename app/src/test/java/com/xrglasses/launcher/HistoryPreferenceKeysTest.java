package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryPreferenceKeysTest {
    @Test public void clearsLegacyRecentTimestamp() { assertTrue(HistoryPreferenceKeys.isRecent("recent:org.example")); }
    @Test public void clearsComponentAndLabelTogether() { assertTrue(HistoryPreferenceKeys.isRecent("recent_component:org.example/Main")); assertTrue(HistoryPreferenceKeys.isRecent("recent_label:org.example/Main")); }
    @Test public void doesNotClearActiveApp() { assertFalse(HistoryPreferenceKeys.isRecent("active_external_package")); assertFalse(HistoryPreferenceKeys.isRecent("active_external_timestamp")); }
    @Test public void keepsDraftAndLocale() { assertFalse(HistoryPreferenceKeys.isRecent("text_draft")); assertFalse(HistoryPreferenceKeys.isRecent("language")); assertFalse(HistoryPreferenceKeys.isRecent(null)); }
}
