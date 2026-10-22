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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuGeneratorTest {

    private static final MenuGenerator.Layout LAYOUT = new MenuGenerator.Layout(new int[]{590, 945, 1300}, 668, 240, 32, "#FFC24A");
    private static final MenuGenerator.Layout PLAIN = new MenuGenerator.Layout(new int[]{590, 945, 1300}, 668, 240, 32, "");
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
        assertEquals(210, MenuGenerator.textWidth("Jupiter Cubee", 0.375));
        assertEquals(252, MenuGenerator.textWidth("Ebony Cat Cubee", 0.375));
    }

    @Test
    void thumbHeightFollowsFormula() {
        assertEquals(500, MenuGenerator.thumbHeight(0, LAYOUT, 500, 500));
        assertEquals(500, MenuGenerator.thumbHeight(1, LAYOUT, 500, 500));
        assertEquals(500, MenuGenerator.thumbHeight(6, LAYOUT, 500, 500));
        assertEquals(347, MenuGenerator.thumbHeight(7, LAYOUT, 500, 500));
        assertEquals(74, MenuGenerator.thumbHeight(40, LAYOUT, 500, 500));
        assertEquals(32, MenuGenerator.thumbHeight(300, LAYOUT, 500, 500));
        assertEquals(486, MenuGenerator.thumbHeight(4, LAYOUT, 486, 486));
        assertEquals(164, MenuGenerator.thumbHeight(16, LAYOUT, 486, 486));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 6, 16, 40})
    void everyCardHasTheBuyAndTheOwnedLayout(int pets) throws IOException {
        List<MenuGenerator.Card> cards = cards(pets);
        String output = MenuGenerator.generate(resource("/menu-template.yml"), cards, LAYOUT);
        writeOutput("petsshop-" + pets + ".yml", output);

        Map<String, Object> root = new Yaml().load(output);
        assertEquals("/petsshop", root.get("command"));
        assertNotNull(root.get("scrollables"));
        assertEquals(List.of("HEARTS", "HOTBAR", "HAND"), root.get("hideVanilla"));
        List<Map<String, Object>> elements = elements(root);
        assertUniqueIds(elements);
        assertEquals(7L * pets, elements.stream().filter(e -> ((String) e.get("id")).startsWith("card_")).count());
        for (String fixed : List.of("panel_edge", "panel", "title", "title_rule", "balance_label", "balance", "rows_track", "rows_thumb", "image_1")) {
            element(elements, fixed);
        }

        for (int i = 0; i < pets; i++) {
            int n = i + 1;
            MenuGenerator.Card card = cards.get(i);
            int x = new int[]{590, 945, 1300}[i % 3];
            int y = 668 - 240 * (i / 3);
            String toBuy = "{= !%petshop_owned_" + card.key() + "% }";
            String isOwned = "%petshop_owned_" + card.key() + "%";

            Map<String, Object> edge = element(elements, "card_" + n + "_edge");
            assertPosition(edge, x, y);
            assertEquals(List.of(Map.of("type", "RUN_COMMAND", "target", "petshop click " + card.key())), edge.get("actions"));
            assertEquals(1000, edge.get("clickCooldown"));
            assertEquals("{= pick(%petshop_state_" + card.key() + "%, 'equipped', '#FFC24A', '#5A6273') }", edge.get("color"));
            assertNull(edge.get("visible"));

            Map<String, Object> body = element(elements, "card_" + n);
            assertPosition(body, x, y);
            assertNull(body.get("actions"), "only the edge takes clicks, so the whole grey area works");
            assertNull(body.get("visible"));

            Map<String, Object> item = element(elements, "card_" + n + "_item");
            assertPosition(item, x, y + 36);
            assertEquals(128, size(item).get("width"));
            assertEquals(card.itemId(), item.get("itemId"));
            assertEquals(toBuy, item.get("visible"));

            Map<String, Object> ownedItem = element(elements, "card_" + n + "_item_owned");
            assertPosition(ownedItem, x, y + 22);
            assertEquals(155, size(ownedItem).get("width"));
            assertEquals(card.itemId(), ownedItem.get("itemId"));
            assertEquals(isOwned, ownedItem.get("visible"));

            Map<String, Object> name = element(elements, "card_" + n + "_name");
            assertPosition(name, x, y - 46);
            assertEquals(card.name(), name.get("text"));
            assertEquals(MenuGenerator.textWidth(card.name(), 0.375), size(name).get("width"));
            assertEquals(toBuy, name.get("visible"));

            Map<String, Object> ownedName = element(elements, "card_" + n + "_name_owned");
            assertPosition(ownedName, x, y - 82);
            assertEquals(card.name(), ownedName.get("text"));
            assertEquals(isOwned, ownedName.get("visible"));

            Map<String, Object> price = element(elements, "card_" + n + "_price");
            assertPosition(price, x, y - 78);
            assertEquals("%petshop_label_" + card.key() + "%", price.get("text"));
            int widest = card.labels().stream().mapToInt(label -> MenuGenerator.textWidth(label, 0.375)).max().orElseThrow();
            assertEquals(widest, size(price).get("width"));
            assertEquals(toBuy, price.get("visible"));

            for (Map<String, Object> part : List.of(edge, body, item, ownedItem, name, ownedName, price)) {
                assertEquals("rows", part.get("scrollRegion"));
                assertEquals("row" + (i / 3 + 1), part.get("scrollGroup"));
            }
        }
        assertEquals(MenuGenerator.thumbHeight(pets, LAYOUT, 486, 486), size(element(elements, "rows_thumb")).get("height"));
        assertEquals(486, size(element(elements, "rows_track")).get("height"));
        assertTrue(output.endsWith("HAND\n"));
    }

    @Test
    void cardElementsKeepTheTemplateFieldOrder() {
        String output = MenuGenerator.generate(resource("/menu-template.yml"), cards(1), LAYOUT);
        String template = resource("/menu-template.yml");
        assertTrue(output.contains("""
                  - type: IMAGE
                    id: card_1_edge
                    sprite: rect.png
                    color: "{= pick(%petshop_state_pet_1%, 'equipped', '#FFC24A', '#5A6273') }"
                    position: { x: 590, y: 668 }
                    size: { width: 338, height: 228 }
                    layer: 5
                    corner: { shape: CUT, size: 9 }
                    clickCooldown: 1000
                    actions:
                      - { type: RUN_COMMAND, target: petshop click pet_1 }
                    scrollRegion: rows
                    scrollGroup: row1
                  - type: IMAGE
                    id: card_1
                """), output);
        assertTrue(output.contains("""
                  - type: ITEM
                    id: card_1_item_owned
                    itemId: nexo:pet_1_icon
                    position: { x: 590, y: 690 }
                    size: { width: 155, height: 155 }
                    layer: 7
                    visible: '%petshop_owned_pet_1%'
                    scrollRegion: rows
                    scrollGroup: row1
                """), output);
        String head = template.substring(0, template.indexOf("  - type: IMAGE\n    id: card_1_edge"));
        String foot = template.substring(template.indexOf("  - type: TEXT\n    id: balance_label"));
        assertTrue(output.startsWith(head));
        assertTrue(output.endsWith(foot));
    }

    @Test
    void usersOwnTemplateIsDetectedAutomatically() throws IOException {
        List<MenuGenerator.Card> cards = cards(16);
        String output = MenuGenerator.generate(resource("/petsshop-user.yml"), cards, LAYOUT);
        writeOutput("petsshop-user-16.yml", output);
        List<Map<String, Object>> elements = elements(new Yaml().load(output));
        assertUniqueIds(elements);
        assertEquals(16L * 7, elements.stream().filter(e -> ((String) e.get("id")).startsWith("card_")).count());
        Map<String, Object> item = element(elements, "card_16_item");
        assertEquals(88, size(item).get("width"), "card_1 of that file is the layout to buy");
        assertPosition(item, 590, 668 - 5 * 240 + 36);
        Map<String, Object> ownedItem = element(elements, "card_16_item_owned");
        assertEquals(155, size(ownedItem).get("width"), "card_3 of that file is the owned layout");
        assertPosition(ownedItem, 590, 668 - 5 * 240 + 22);
        assertEquals(List.of(Map.of("type", "RUN_COMMAND", "target", "petshop click pet_16")), element(elements, "card_16").get("actions"));
        assertEquals(MenuGenerator.thumbHeight(16, LAYOUT, 486, 486), size(element(elements, "rows_thumb")).get("height"));
        assertEquals(List.of("HEARTS", "HOTBAR", "HAND"), new Yaml().<Map<String, Object>>load(output).get("hideVanilla"));
    }

    @Test
    void sixPetsKeepTheOriginalShopStructure() throws IOException {
        String template = resource("/shop-original.yml");
        List<MenuGenerator.Card> cards = new ArrayList<>(cards(6));
        cards.set(2, new MenuGenerator.Card("long_name_pet", "A pet with a much longer name", "nexo:long_name_pet_icon", LABELS));
        String output = MenuGenerator.generate(template, cards, PLAIN);
        writeOutput("original-template-6.yml", output);
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
            } else if ((id.endsWith("_name") || id.endsWith("_price")) && line.startsWith("    size: ")) {
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
        assertEquals("Bob's Pet: #1 {x} [y] %z", element(elements, "card_1_name_owned").get("text"));
        assertEquals("nexo:yes", element(elements, "card_1_item").get("itemId"));
        assertEquals("yes", element(elements, "card_2_item").get("itemId"));
        assertEquals("weird id: #x", element(elements, "card_3_item_owned").get("itemId"));
        assertEquals("Line break", element(elements, "card_4_name").get("text"));
        assertEquals("nexo:", element(elements, "card_4_item").get("itemId"));
    }

    @Test
    void brokenTemplatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MenuGenerator.generate("command: /x\nelements:\n  - type: IMAGE\n    id: a\n", List.of(), LAYOUT));
        assertThrows(IllegalArgumentException.class, () -> MenuGenerator.generate("command: /x\n", List.of(), LAYOUT));
        assertThrows(IllegalArgumentException.class, () -> MenuGenerator.generate("elements:\n  - type: TEXT\n    id: card_1_name\n", List.of(), LAYOUT));
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

    private static void assertUniqueIds(List<Map<String, Object>> elements) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> element : elements) {
            assertTrue(ids.add((String) element.get("id")), "duplicate id " + element.get("id"));
        }
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
