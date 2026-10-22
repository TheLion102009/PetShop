package the.lion.petshop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the menu file line by line from the template, so indentation and field order stay exactly as in the template.
 * The first card with a price element is the layout of a pet to buy, the first card without one the layout of an owned pet.
 */
final class MenuGenerator {

    record Card(String key, String name, String itemId, List<String> labels) {
    }

    record Layout(int[] columns, int firstRowY, int rowSpacing, int minThumb, String selectedColor) {
    }

    private static final Pattern CARD_ID = Pattern.compile("card_(\\d+)(?:_([A-Za-z0-9_]+))?");
    private static final Pattern ID = Pattern.compile("^(?: {2}- | {4})id: *(.+?) *$");
    private static final Pattern ID_LINE = Pattern.compile("^((?: {2}- | {4})id: *)(.+?)( *)$");
    private static final Pattern POSITION = Pattern.compile("^( {4}position: \\{ x: )(-?\\d+(?:\\.\\d+)?)(, y: )(-?\\d+(?:\\.\\d+)?)( }.*)$");
    private static final Pattern SIZE = Pattern.compile("^( {4}size: \\{ width: )(-?\\d+)(, height: )(-?\\d+)( }.*)$");
    private static final Pattern TEXT = Pattern.compile("^ {4}text: *(.*)$");
    private static final Pattern ITEM_ID = Pattern.compile("^ {4}itemId: .*$");
    private static final Pattern COLOR = Pattern.compile("^ {4}color: *'?(#[0-9A-Fa-f]{6})'? *$");
    private static final Pattern LAYER = Pattern.compile("^ {4}layer: .*$");
    private static final Pattern SCROLL_GROUP = Pattern.compile("^( {4}scrollGroup: *)(.*?)( *)$");
    private static final Pattern GLYPH_SCALE = Pattern.compile("^ {4}glyphScale: *(\\d+(?:\\.\\d+)?) *$");
    private static final Pattern REGION_HEIGHT = Pattern.compile("height: *(\\d+)");
    private static final Pattern RUN_COMMAND = Pattern.compile("(type: RUN_COMMAND, target: )([^,}]*?)( *[,}])");
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-./:]*");

    private MenuGenerator() {
    }

    private static final class Block {
        final List<String> lines = new ArrayList<>();
        String id;
        String role;
    }

    /** All elements of one template card and the position of its card_N element. */
    private record Prototype(int number, List<Block> blocks, double x, double y) {

        Block role(String role) {
            for (Block block : blocks) {
                if (block.role.equals(role)) {
                    return block;
                }
            }
            return null;
        }
    }

    static String generate(String template, List<Card> cards, Layout layout) {
        String text = template.replace("\r\n", "\n").replace('\r', '\n');
        boolean trailingNewline = text.endsWith("\n");
        String[] lines = (trailingNewline ? text.substring(0, text.length() - 1) : text).split("\n", -1);

        int elementsLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripTrailing().equals("elements:")) {
                elementsLine = i;
                break;
            }
        }
        if (elementsLine < 0) {
            throw new IllegalArgumentException("the template has no top-level 'elements:' list");
        }
        if (layout.columns().length == 0) {
            throw new IllegalArgumentException("menu-layout.columns is empty");
        }

        List<String> preamble = new ArrayList<>(List.of(lines).subList(0, elementsLine + 1));
        List<Block> blocks = new ArrayList<>();
        List<String> trailer = new ArrayList<>();
        Block current = null;
        for (int i = elementsLine + 1; i < lines.length; i++) {
            String line = lines[i];
            if (!trailer.isEmpty() || (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#"))) {
                trailer.add(line);
            } else if (line.startsWith("  - ")) {
                current = new Block();
                current.lines.add(line);
                blocks.add(current);
            } else if (current == null) {
                preamble.add(line);
            } else {
                current.lines.add(line);
            }
        }

        List<Block> head = new ArrayList<>();
        List<Block> foot = new ArrayList<>();
        Map<Integer, List<Block>> cardBlocks = new LinkedHashMap<>();
        for (Block block : blocks) {
            for (String line : block.lines) {
                Matcher m = ID.matcher(line);
                if (m.matches()) {
                    block.id = unquote(m.group(1));
                    break;
                }
            }
            Matcher card = block.id == null ? null : CARD_ID.matcher(block.id);
            if (card != null && card.matches()) {
                block.role = card.group(2) == null ? "" : card.group(2).toLowerCase(Locale.ROOT);
                cardBlocks.computeIfAbsent(Integer.parseInt(card.group(1)), n -> new ArrayList<>()).add(block);
            } else if (cardBlocks.isEmpty()) {
                head.add(block);
            } else {
                foot.add(block);
            }
        }

        Prototype buy = null;
        Prototype owned = null;
        for (int number : cardBlocks.keySet().stream().sorted().toList()) {
            List<Block> card = cardBlocks.get(number);
            boolean priced = card.stream().anyMatch(b -> b.role.equals("price"));
            if (priced && buy == null) {
                buy = prototype(number, card);
            } else if (!priced && owned == null) {
                owned = prototype(number, card);
            }
        }
        if (buy == null) {
            buy = owned;
            owned = null;
        }
        if (buy == null) {
            throw new IllegalArgumentException("the template has no card element (card_1, card_1_edge, ...)");
        }

        double viewHeight = regionHeight(preamble);
        Block track = foot.stream().filter(b -> "rows_track".equals(b.id)).findFirst().orElse(null);
        double trackHeight = track == null ? Double.NaN : height(track);
        if (Double.isNaN(trackHeight)) {
            trackHeight = Double.isNaN(viewHeight) ? 500 : viewHeight;
        }
        if (Double.isNaN(viewHeight)) {
            viewHeight = trackHeight;
        }

        StringBuilder out = new StringBuilder(text.length() + cards.size() * 4000);
        for (String line : preamble) {
            out.append(line).append('\n');
        }
        for (Block block : head) {
            if ("title_right".equals(block.id)) {
                appendTitleRight(out, block, cards.size());
            } else {
                appendLines(out, block.lines);
            }
        }
        int columns = layout.columns().length;
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            int row = i / columns;
            Placement at = new Placement(i + 1, layout.columns()[i % columns], layout.firstRowY() - (double) layout.rowSpacing() * row, row + 1);
            String toBuy = "'{= !%petshop_owned_" + card.key() + "% }'";
            String isOwned = "'%petshop_owned_" + card.key() + "%'";
            for (Block block : buy.blocks()) {
                List<String> forBuy = render(block, buy, card, at, layout, "");
                Block other = owned == null ? null : owned.role(block.role);
                if (owned == null) {
                    appendLines(out, forBuy);
                } else if (other == null) {
                    appendLines(out, withVisible(forBuy, toBuy));
                } else {
                    List<String> forOwned = render(other, owned, card, at, layout, "");
                    if (forBuy.equals(forOwned)) {
                        appendLines(out, forBuy);
                    } else {
                        appendLines(out, withVisible(forBuy, toBuy));
                        appendLines(out, withVisible(render(other, owned, card, at, layout, "_owned"), isOwned));
                    }
                }
            }
            if (owned != null) {
                for (Block block : owned.blocks()) {
                    if (buy.role(block.role) == null) {
                        appendLines(out, withVisible(render(block, owned, card, at, layout, "_owned"), isOwned));
                    }
                }
            }
        }
        for (Block block : foot) {
            if ("rows_thumb".equals(block.id)) {
                int thumb = thumbHeight(cards.size(), layout, trackHeight, viewHeight);
                for (String line : block.lines) {
                    Matcher m = SIZE.matcher(line);
                    out.append(m.matches() ? m.group(1) + m.group(2) + m.group(3) + thumb + m.group(5) : line).append('\n');
                }
            } else {
                appendLines(out, block.lines);
            }
        }
        for (String line : trailer) {
            out.append(line).append('\n');
        }
        if (!trailingNewline && !out.isEmpty()) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private record Placement(int number, double x, double y, int row) {
    }

    private static Prototype prototype(int number, List<Block> card) {
        Block anchor = card.stream().filter(b -> b.role.isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("card " + number + " has no element with id card_" + number));
        double[] position = position(anchor);
        if (position == null) {
            throw new IllegalArgumentException("card_" + number + " has no 'position: { x: .., y: .. }' line");
        }
        return new Prototype(number, card, position[0], position[1]);
    }

    /** One template element turned into the element of card {@code at.number()} for this pet. */
    private static List<String> render(Block block, Prototype prototype, Card card, Placement at, Layout layout, String idSuffix) {
        Pattern number = Pattern.compile("(?<![A-Za-z0-9_])card_?" + prototype.number() + "(?![0-9])");
        double glyphScale = glyphScale(block);
        double dx = at.x() - prototype.x();
        double dy = at.y() - prototype.y();
        List<String> result = new ArrayList<>(block.lines.size() + 1);
        for (String line : block.lines) {
            line = number.matcher(line).replaceAll(m -> m.group().contains("_") ? "card_" + at.number() : "card" + at.number());
            Matcher id = ID_LINE.matcher(line);
            if (id.matches() && !idSuffix.isEmpty()) {
                line = id.group(1) + id.group(2) + idSuffix + id.group(3);
            }
            Matcher position = POSITION.matcher(line);
            if (position.matches()) {
                line = position.group(1) + number(Double.parseDouble(position.group(2)) + dx)
                        + position.group(3) + number(Double.parseDouble(position.group(4)) + dy) + position.group(5);
            }
            Matcher group = SCROLL_GROUP.matcher(line);
            if (group.matches()) {
                line = group.group(1) + group.group(2).replaceAll("\\d+$", "") + at.row() + group.group(3);
            }
            Matcher command = RUN_COMMAND.matcher(line);
            if (command.find()) {
                line = line.substring(0, command.start(2)) + "petshop click " + card.key() + line.substring(command.end(2));
            }
            switch (block.role) {
                case "name" -> {
                    if (TEXT.matcher(line).matches()) {
                        line = "    text: " + quote(card.name());
                    } else {
                        line = withWidth(line, textWidth(card.name(), glyphScale));
                    }
                }
                case "price" -> {
                    if (TEXT.matcher(line).matches()) {
                        line = "    text: " + quote("%petshop_label_" + card.key() + "%");
                    } else {
                        int width = 0;
                        for (String label : card.labels()) {
                            width = Math.max(width, textWidth(label, glyphScale));
                        }
                        line = withWidth(line, width);
                    }
                }
                case "item" -> {
                    if (ITEM_ID.matcher(line).matches()) {
                        line = "    itemId: " + scalar(card.itemId());
                    }
                }
                case "edge" -> {
                    Matcher color = COLOR.matcher(line);
                    if (color.matches() && !layout.selectedColor().isEmpty()) {
                        line = "    color: \"{= pick(%petshop_state_" + card.key() + "%, 'equipped', '" + layout.selectedColor()
                                + "', '" + color.group(1) + "') }\"";
                    }
                }
                default -> {
                }
            }
            result.add(line);
        }
        return result;
    }

    /** Adds a visible line after the layer line, or at the end of the element. */
    private static List<String> withVisible(List<String> lines, String condition) {
        List<String> result = new ArrayList<>(lines.size() + 1);
        boolean added = false;
        for (String line : lines) {
            result.add(line);
            if (!added && LAYER.matcher(line).matches()) {
                result.add("    visible: " + condition);
                added = true;
            }
        }
        if (!added) {
            result.add("    visible: " + condition);
        }
        return result;
    }

    private static void appendTitleRight(StringBuilder out, Block block, int total) {
        String text = null;
        for (String line : block.lines) {
            Matcher m = TEXT.matcher(line);
            if (m.matches()) {
                text = unquote(m.group(1));
            }
        }
        if (text == null || !text.contains("%petshop_")) {
            appendLines(out, block.lines);
            return;
        }
        String count = Integer.toString(total);
        int width = textWidth(text.replace("%petshop_owned%", count).replace("%petshop_total%", count), glyphScale(block));
        for (String line : block.lines) {
            out.append(withWidth(line, width)).append('\n');
        }
    }

    private static void appendLines(StringBuilder out, List<String> lines) {
        for (String line : lines) {
            out.append(line).append('\n');
        }
    }

    private static String withWidth(String line, int width) {
        Matcher m = SIZE.matcher(line);
        return m.matches() ? m.group(1) + width + m.group(3) + m.group(4) + m.group(5) : line;
    }

    private static double[] position(Block block) {
        for (String line : block.lines) {
            Matcher m = POSITION.matcher(line);
            if (m.matches()) {
                return new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(4))};
            }
        }
        return null;
    }

    private static double height(Block block) {
        for (String line : block.lines) {
            Matcher m = SIZE.matcher(line);
            if (m.matches()) {
                return Double.parseDouble(m.group(4));
            }
        }
        return Double.NaN;
    }

    /** Height of the first scroll region in the screen keys above the elements. */
    private static double regionHeight(List<String> preamble) {
        boolean inScrollables = false;
        for (String line : preamble) {
            if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("-")) {
                inScrollables = line.startsWith("scrollables:") || line.startsWith("scrollable:");
                continue;
            }
            Matcher m = REGION_HEIGHT.matcher(line);
            if (inScrollables && m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        return Double.NaN;
    }

    private static double glyphScale(Block block) {
        for (String line : block.lines) {
            Matcher m = GLYPH_SCALE.matcher(line);
            if (m.matches()) {
                return Double.parseDouble(m.group(1));
            }
        }
        return 1.0;
    }

    private static String number(double value) {
        long rounded = Math.round(value);
        return Math.abs(value - rounded) < 1e-9 ? Long.toString(rounded) : Double.toString(value);
    }

    /** Width the menu editor uses: sum of glyph widths times glyphScale * 8. */
    static int textWidth(String text, double glyphScale) {
        int sum = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            sum += switch (cp) {
                case ' ' -> 4;
                case 'i', '.', ',', ':', ';', '!', '\'', '|' -> 2;
                case 'l' -> 3;
                case 'I', 't' -> 4;
                case 'f', 'k' -> 5;
                default -> 6;
            };
        }
        return (int) Math.round(sum * glyphScale * 8);
    }

    /** Thumb length: the track times the visible part of all rows, kept between min-thumb and the track. */
    static int thumbHeight(int pets, Layout layout, double trackHeight, double viewHeight) {
        int rows = (pets + layout.columns().length - 1) / layout.columns().length;
        if (rows == 0) {
            return (int) Math.round(trackHeight);
        }
        long height = Math.round(trackHeight * viewHeight / ((double) rows * layout.rowSpacing()));
        return (int) Math.max(layout.minThumb(), Math.min(Math.round(trackHeight), height));
    }

    /** YAML single-quoted scalar. */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                sb.append("''");
            } else {
                sb.append(Character.isISOControl(c) ? ' ' : c);
            }
        }
        return sb.append('\'').toString();
    }

    /** Plain scalar when YAML reads it back as the same string, otherwise single-quoted. */
    static String scalar(String value) {
        if (PLAIN_SCALAR.matcher(value).matches() && !value.endsWith(":")) {
            switch (value.toLowerCase(Locale.ROOT)) {
                case "y", "n", "yes", "no", "on", "off", "true", "false", "null" -> {
                    return quote(value);
                }
                default -> {
                    return value;
                }
            }
        }
        return quote(value);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == '\'' && last == '\'') {
                return value.substring(1, value.length() - 1).replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
