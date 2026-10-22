package the.lion.petshop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the menu file line by line from the template, so indentation and field order stay exactly as in the template.
 */
final class MenuGenerator {

    record Card(String key, String name, String itemId, List<String> labels) {
    }

    record Layout(int[] columns, int firstRowY, int rowSpacing, int viewHeight, int minThumb) {
    }

    private static final Pattern CARD_ID = Pattern.compile("card_(\\d+)(?:_([A-Za-z0-9_]+))?");
    private static final Pattern ID = Pattern.compile("^(?: {2}- | {4})id: *(.+?) *$");
    private static final Pattern POSITION = Pattern.compile("^( {4}position: \\{ x: )(-?\\d+(?:\\.\\d+)?)(, y: )(-?\\d+(?:\\.\\d+)?)( }.*)$");
    private static final Pattern SIZE = Pattern.compile("^( {4}size: \\{ width: )(-?\\d+)(, height: )(-?\\d+)( }.*)$");
    private static final Pattern TEXT = Pattern.compile("^ {4}text: *(.*)$");
    private static final Pattern ITEM_ID = Pattern.compile("^ {4}itemId: .*$");
    private static final Pattern GLYPH_SCALE = Pattern.compile("^ {4}glyphScale: *(\\d+(?:\\.\\d+)?) *$");
    private static final Pattern RUN_COMMAND = Pattern.compile("(type: RUN_COMMAND, target: )([^,}]*?)( *[,}])");
    private static final Pattern CARD_1_ID = Pattern.compile("(?<![A-Za-z0-9_])card_1(?![0-9])");
    private static final Pattern CARD_1_GROUP = Pattern.compile("(?<![A-Za-z0-9_])card1(?![0-9])");
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-./:]*");

    private MenuGenerator() {
    }

    private static final class Block {
        final List<String> lines = new ArrayList<>();
        String id;
        int cardNumber;
        String role;
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
        List<Block> cardTemplate = new ArrayList<>();
        List<Block> foot = new ArrayList<>();
        boolean seenCard = false;
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
                seenCard = true;
                block.cardNumber = Integer.parseInt(card.group(1));
                block.role = card.group(2) == null ? "" : card.group(2).toLowerCase(Locale.ROOT);
                if (block.cardNumber == 1) {
                    cardTemplate.add(block);
                }
            } else if (seenCard) {
                foot.add(block);
            } else {
                head.add(block);
            }
        }

        Block anchor = cardTemplate.stream().filter(b -> b.role.isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the template has no element with id card_1"));
        double[] anchorPos = position(anchor);
        if (anchorPos == null) {
            throw new IllegalArgumentException("card_1 has no 'position: { x: .., y: .. }' line");
        }
        if (layout.columns().length == 0) {
            throw new IllegalArgumentException("menu-layout.columns is empty");
        }

        StringBuilder out = new StringBuilder(text.length() + cards.size() * 2400);
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
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            int number = i + 1;
            double dx = layout.columns()[i % layout.columns().length] - anchorPos[0];
            double dy = layout.firstRowY() - (double) layout.rowSpacing() * (i / layout.columns().length) - anchorPos[1];
            for (Block block : cardTemplate) {
                appendCardElement(out, block, card, number, dx, dy);
            }
        }
        for (Block block : foot) {
            if ("rows_thumb".equals(block.id)) {
                int height = thumbHeight(cards.size(), layout);
                for (String line : block.lines) {
                    Matcher m = SIZE.matcher(line);
                    out.append(m.matches() ? m.group(1) + m.group(2) + m.group(3) + height + m.group(5) : line).append('\n');
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

    private static void appendCardElement(StringBuilder out, Block block, Card card, int number, double dx, double dy) {
        double glyphScale = glyphScale(block);
        for (String line : block.lines) {
            line = CARD_1_ID.matcher(line).replaceAll("card_" + number);
            line = CARD_1_GROUP.matcher(line).replaceAll("card" + number);
            Matcher position = POSITION.matcher(line);
            if (position.matches()) {
                line = position.group(1) + number(Double.parseDouble(position.group(2)) + dx)
                        + position.group(3) + number(Double.parseDouble(position.group(4)) + dy) + position.group(5);
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
                case "" -> {
                    Matcher command = RUN_COMMAND.matcher(line);
                    if (command.find()) {
                        line = line.substring(0, command.start(2)) + "petshop click " + card.key() + line.substring(command.end(2));
                    }
                }
                default -> {
                }
            }
            out.append(line).append('\n');
        }
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

    static int thumbHeight(int pets, Layout layout) {
        int rows = (pets + layout.columns().length - 1) / layout.columns().length;
        if (rows == 0) {
            return layout.viewHeight();
        }
        long height = Math.round((double) layout.viewHeight() * layout.viewHeight() / ((double) rows * layout.rowSpacing()));
        return (int) Math.max(layout.minThumb(), Math.min(layout.viewHeight(), height));
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
