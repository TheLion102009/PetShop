package the.lion.petshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuGeneratorTest {

    private static final MenuGenerator.Layout LAYOUT = new MenuGenerator.Layout(new int[]{590, 945, 1300}, 668, 240, 500, 32);
    private static final List<String> LABELS = List.of("Equip", "Equipped");
    private static final Pattern ID_LINE = Pattern.compile("^(?: {2}- | {4})id: (.+)$");
    private static final Pattern POSITION = Pattern.compile("^ {4}position: \\{ x: (-?\\d+), y: (-?\\d+) }$");
    private static final Pattern SIZE = Pattern.compile("^ {4}size: \\{ width: (\\d+), height: (\\d+) }$");

    @Test
    void textWidthMatchesEditor() {
        assertEquals(93, MenuGenerator.textWidth("Elytra", 0.375));
        assertEquals(216, MenuGenerator.textWidth("Diamond Sword", 0.375));
        assertEquals(249, MenuGenerator.textWidth("Ender Pearls x4", 0.375));
        assertEquals(144, MenuGenerator.textWidth("250 coins", 0.375));
        assertEquals(126, MenuGenerator.textWidth("40 coins", 0.375));
        assertEquals(132, MenuGenerator.textWidth("Equipped", 0.375));
        assertEquals(162, MenuGenerator.textWidth("2000 coins", 0.375));
        assertEquals(256, MenuGenerator.textWidth("Server shop", 0.5));
        assertEquals(212, MenuGenerator.textWidth("Click an item to buy it", 0.25));
        assertEquals(81, MenuGenerator.textWidth("Close", 0.375));
        assertEquals(117, MenuGenerator.textWidth("Balance", 0.375));
    }

    @Test
    void thumbHeightFollowsFormula() {
        assertEquals(500, MenuGenerator.thumbHeight(0, LAYOUT));
        assertEquals(500, MenuGenerator.thumbHeight(1, LAYOUT));
        assertEquals(500, MenuGenerator.thumbHeight(6, LAYOUT));
        assertEquals(347, MenuGenerator.thumbHeight(7, LAYOUT));
        assertEquals(74, MenuGenerator.thumbHeight(40, LAYOUT));
        assertEquals(32, MenuGenerator.thumbHeight(300, LAYOUT));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 6, 40})
    void generatedFileIsValidAndComplete(int pets) throws IOException {
        List<MenuGenerator.Card> cards = cards(pets);
        String output = MenuGenerator.generate(resource("/menu-template.yml"), cards, LAYOUT);
        writeOutput("shop-" + pets + ".yml", output);

        Map<String, Object> root = new Yaml().load(output);
        assertEquals("/shop", root.get("command"));
        assertNotNull(root.get("scrollables"));
        List<Map<String, Object>> elements = elements(root);

        Set<String> ids = new HashSet<>();
        for (Map<String, Object> element : elements) {
            assertTrue(ids.add((String) element.get("id")), "duplicate id " + element.get("id"));
        }
        assertEquals(5 * pets, elements.stream().filter(e -> ((String) e.get("id")).startsWith("card_")).count());
        for (String fixed : List.of("panel_edge", "panel", "title", "title_right", "title_rule", "balance_label", "balance",
                "btn_close_edge", "btn_close", "btn_close_label", "rows_track", "rows_thumb")) {
            assertTrue(ids.contains(fixed), "missing " + fixed);
        }

        for (int i = 0; i < pets; i++) {
            int n = i + 1;
            MenuGenerator.Card card = cards.get(i);
            int x = new int[]{590, 945, 1300}[i % 3];
            int y = 668 - 240 * (i / 3);
            Map<String, Object> edge = element(elements, "card_" + n + "_edge");
            Map<String, Object> body = element(elements, "card_" + n);
            Map<String, Object> item = element(elements, "card_" + n + "_item");
            Map<String, Object> name = element(elements, "card_" + n + "_name");
            Map<String, Object> price = element(elements, "card_" + n + "_price");
            for (Map<String, Object> element : List.of(edge, body, item, name, price)) {
                assertEquals("rows", element.get("scrollRegion"));
                assertEquals("card" + n, element.get("scrollGroup"));
            }
            assertPosition(edge, x, y);
            assertPosition(body, x, y);
            assertPosition(item, x, y + 36);
            assertPosition(name, x, y - 46);
            assertPosition(price, x, y - 78);
            assertEquals(card.name(), name.get("text"));
            assertEquals(MenuGenerator.textWidth(card.name(), 0.375), size(name).get("width"));
            assertEquals(24, size(name).get("height"));
            assertEquals("%petshop_label_" + card.key() + "%", price.get("text"));
            int widest = card.labels().stream().mapToInt(label -> MenuGenerator.textWidth(label, 0.375)).max().orElseThrow();
            assertEquals(widest, size(price).get("width"));
            assertEquals(card.itemId(), item.get("itemId"));
            List<?> actions = (List<?>) body.get("actions");
            assertEquals(1, actions.size());
            assertEquals(Map.of("type", "RUN_COMMAND", "target", "petshop click " + card.key()), actions.getFirst());
            List<?> hover = (List<?>) body.get("hoverActions");
            assertEquals("card_" + n + "_edge", ((Map<?, ?>) hover.get(1)).get("element"));
            assertEquals(8, ((Map<?, ?>) body.get("corner")).get("size"));
            assertNotNull(((Map<?, ?>) body.get("timelines")).get("grow"));
        }

        assertEquals(MenuGenerator.thumbHeight(pets, LAYOUT), size(element(elements, "rows_thumb")).get("height"));
        assertEquals(500, size(element(elements, "rows_track")).get("height"));
        String count = Integer.toString(pets);
        assertEquals(MenuGenerator.textWidth(count + "/" + count + " pets owned", 0.25), size(element(elements, "title_right")).get("width"));
        assertTrue(output.endsWith("\n"));
    }

    @Test
    void sixPetsKeepTheTemplateStructure() throws IOException {
        assertSameStructure(resource("/menu-template.yml"), "scroll-template-6.yml");
    }

    @Test
    void sixPetsKeepTheOriginalShopStructure() throws IOException {
        assertSameStructure(resource("/shop-original.yml"), "original-template-6.yml");
    }

    @Test
    void namesAndItemIdsAreYamlSafe() {
        List<MenuGenerator.Card> cards = List.of(
                new MenuGenerator.Card("bob", "Bob's Pet: #1 {x} [y] %z", "nexo:yes", LABELS),
                new MenuGenerator.Card("tim", "Tim", "yes", LABELS),
                new MenuGenerator.Card("ann", "Ann", "weird id: #x", LABELS),
                new MenuGenerator.Card("eve", "Line\nbreak", "nexo:", LABELS));
        String output = MenuGenerator.generate(resource("/menu-template.yml"), cards, LAYOUT);
        List<Map<String, Object>> elements = elements(new Yaml().load(output));
        assertEquals("Bob's Pet: #1 {x} [y] %z", element(elements, "card_1_name").get("text"));
        assertEquals("nexo:yes", element(elements, "card_1_item").get("itemId"));
        assertEquals("yes", element(elements, "card_2_item").get("itemId"));
        assertEquals("weird id: #x", element(elements, "card_3_item").get("itemId"));
        assertEquals("Line break", element(elements, "card_4_name").get("text"));
        assertEquals("nexo:", element(elements, "card_4_item").get("itemId"));
    }

    @Test
    void templateWithoutCardOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MenuGenerator.generate("command: /x\nelements:\n  - type: IMAGE\n    id: a\n", List.of(), LAYOUT));
        assertThrows(IllegalArgumentException.class, () -> MenuGenerator.generate("command: /x\n", List.of(), LAYOUT));
    }

    private static void assertSameStructure(String template, String outputName) throws IOException {
        List<MenuGenerator.Card> cards = new ArrayList<>(cards(6));
        cards.set(2, new MenuGenerator.Card("long_name_pet", "A pet with a much longer name", "nexo:long_name_pet_icon", LABELS));
        String output = MenuGenerator.generate(template, cards, LAYOUT);
        writeOutput(outputName, output);
        assertFalse(new Yaml().<Map<String, Object>>load(output).isEmpty());

        String[] expected = template.split("\n", -1);
        String[] actual = output.split("\n", -1);
        assertEquals(expected.length, actual.length, "line count");
        String id = "";
        int differences = 0;
        for (int i = 0; i < expected.length; i++) {
            Matcher idMatcher = ID_LINE.matcher(expected[i]);
            if (idMatcher.matches()) {
                id = idMatcher.group(1);
                assertEquals(expected[i], actual[i], "element ids and their order stay the same");
            }
            if (expected[i].equals(actual[i])) {
                continue;
            }
            differences++;
            String line = expected[i];
            boolean card = id.startsWith("card_");
            boolean allowed;
            if (card && line.startsWith("    position: ")) {
                Matcher a = POSITION.matcher(line);
                Matcher b = POSITION.matcher(actual[i]);
                allowed = a.matches() && b.matches() && a.group(2).equals(b.group(2));
            } else if ((id.endsWith("_name") || id.endsWith("_price") || id.equals("title_right")) && line.startsWith("    size: ")) {
                Matcher a = SIZE.matcher(line);
                Matcher b = SIZE.matcher(actual[i]);
                allowed = a.matches() && b.matches() && a.group(2).equals(b.group(2));
            } else if (card && (id.endsWith("_name") || id.endsWith("_price")) && line.startsWith("    text: ")) {
                allowed = true;
            } else if (card && id.endsWith("_item") && line.startsWith("    itemId: ")) {
                allowed = true;
            } else {
                allowed = card && line.contains("type: RUN_COMMAND, target: ");
            }
            assertTrue(allowed, "unexpected change in " + id + " line " + (i + 1) + ":\n  " + line + "\n  " + actual[i]);
        }
        assertTrue(differences > 0);
    }

    private static List<MenuGenerator.Card> cards(int count) {
        List<MenuGenerator.Card> cards = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String key = "pet_" + i;
            cards.add(new MenuGenerator.Card(key, "Pet " + i, i % 2 == 0 ? "PLAYER_HEAD" : "nexo:" + key + "_icon",
                    List.of((1000 * i) + " coins", "Equip", "Equipped")));
        }
        return cards;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> elements(Map<String, Object> root) {
        return (List<Map<String, Object>>) root.get("elements");
    }

    private static Map<String, Object> element(List<Map<String, Object>> elements, String id) {
        return elements.stream().filter(e -> id.equals(e.get("id"))).findFirst().orElseThrow(() -> new AssertionError("no element " + id));
    }

    private static Map<?, ?> size(Map<String, Object> element) {
        return (Map<?, ?>) element.get("size");
    }

    private static void assertPosition(Map<String, Object> element, int x, int y) {
        Map<?, ?> position = (Map<?, ?>) element.get("position");
        assertEquals(x, position.get("x"), element.get("id") + " x");
        assertEquals(y, position.get("y"), element.get("id") + " y");
    }

    private static String resource(String name) {
        try (InputStream in = MenuGeneratorTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeOutput(String name, String content) throws IOException {
        String dir = System.getProperty("petshop.testOutput");
        if (dir != null) {
            Path path = Path.of(dir, name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
    }
}
