package com.xrglasses.launcher;

import org.junit.Test;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class LocalizedResourcesTest {
    private File resource(String relative) {
        File file = new File("src/main/res/" + relative);
        return file.exists() ? file : new File("app/src/main/res/" + relative);
    }
    private Map<String, String> strings(String folder) throws Exception {
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource(folder + "/strings.xml")).getElementsByTagName("string");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String key = nodes.item(i).getAttributes().getNamedItem("name").getNodeValue();
            assertNull("Duplicate resource: " + key, result.put(key, nodes.item(i).getTextContent()));
        }
        return result;
    }
    private List<String> placeholders(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("%[0-9]+\\$[ds]").matcher(value);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
    @Test public void translationsHaveIdenticalKeys() throws Exception { assertEquals(strings("values").keySet(), strings("values-zh-rCN").keySet()); }
    @Test public void formatArgumentsMatchAcrossLocales() throws Exception {
        Map<String, String> en = strings("values"), zh = strings("values-zh-rCN");
        for (String key : en.keySet()) assertEquals(key, placeholders(en.get(key)), placeholders(zh.get(key)));
    }
    @Test public void translatedScreensAreNotEmpty() throws Exception {
        for (String locale : new String[]{"values", "values-zh-rCN"}) for (Map.Entry<String, String> item : strings(locale).entrySet()) assertFalse(item.getKey(), item.getValue().trim().isEmpty());
    }
    @Test public void appCountsHaveLocaleAppropriateQuantities() throws Exception {
        for (String locale : new String[]{"values", "values-zh-rCN"}) {
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource(locale + "/strings.xml")).getElementsByTagName("plurals");
            assertEquals(1, nodes.getLength());
            assertEquals("apps_count", nodes.item(0).getAttributes().getNamedItem("name").getNodeValue());
            assertTrue(nodes.item(0).getTextContent().contains("%1$d"));
            assertTrue(((org.w3c.dom.Element) nodes.item(0)).getElementsByTagName("item").getLength() >= 1);
        }
    }
    @Test public void copyrightHolderIsCkarInEveryLocale() throws Exception {
        for (String locale : new String[]{"values", "values-zh-rCN"}) {
            assertEquals("© 2026 Ckar", strings(locale).get("copyright"));
            assertTrue(strings(locale).get("copyright_note").contains("Ckar"));
        }
    }
    @Test public void supportedLanguagesAreDeclared() throws Exception {
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource("xml/locales_config.xml")).getElementsByTagName("locale");
        assertEquals(2, nodes.getLength());
        assertEquals("en", nodes.item(0).getAttributes().getNamedItem("android:name").getNodeValue());
        assertEquals("zh-CN", nodes.item(1).getAttributes().getNamedItem("android:name").getNodeValue());
    }
}
