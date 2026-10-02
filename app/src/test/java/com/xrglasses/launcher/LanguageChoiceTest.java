package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanguageChoiceTest {
    @Test public void systemChoiceIsEmpty() { assertEquals("", LanguageChoice.normalize("")); assertEquals("", LanguageChoice.normalize(null)); }
    @Test public void simplifiedChineseUsesStableCode() { assertEquals("zh-CN", LanguageChoice.normalize("zh_CN")); assertEquals("zh-CN", LanguageChoice.normalize("zh-Hans-CN")); }
    @Test public void englishVariantsUseEnglish() { assertEquals("en", LanguageChoice.normalize("en-US")); assertEquals("en", LanguageChoice.normalize("EN")); }
    @Test public void unsupportedLanguageFallsBackToSystem() { assertEquals("", LanguageChoice.normalize("fr")); assertEquals("", LanguageChoice.normalize("zh-TW")); }
}
