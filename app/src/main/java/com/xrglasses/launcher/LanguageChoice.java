package com.xrglasses.launcher;

/** Stable preference codes; independent of Android for unit testing. */
public final class LanguageChoice {
    private LanguageChoice() {}
    public static String normalize(String tag) {
        if (tag == null) return "";
        String language = tag.replace('_', '-').toLowerCase(java.util.Locale.ROOT);
        if (language.equals("en") || language.startsWith("en-")) return "en";
        if (language.equals("zh") || language.equals("zh-cn") || language.equals("zh-hans") || language.startsWith("zh-hans-")) return "zh-CN";
        return "";
    }
}
