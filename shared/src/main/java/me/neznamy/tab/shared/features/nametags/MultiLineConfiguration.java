package me.neznamy.tab.shared.features.nametags;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.neznamy.tab.shared.config.PropertyConfiguration;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class for storing multi-line nametag configuration settings.
 */
@Getter
@AllArgsConstructor
public class MultiLineConfiguration {

    /** Name of the line displaying tagprefix + customtagname + tagsuffix */
    public static final String NAMETAG_LINE = "nametag";

    /** Maximum amount of lines (20 above, nametag, 20 below), limited by fake entity id block size */
    public static final int MAX_LINES = 41;

    /** Maximum heights, keep amount of spacer entities bounded */
    private static final double MAX_SPACING = 2;
    private static final double MAX_FIRST_LINE_HEIGHT = 5;

    @NotNull private final ConfigurationSection section;

    /** Lines from top to bottom */
    @NotNull private final List<String> lines;

    /** Height of the lowest line above player's feet, {@code null} for automatic */
    @Nullable private final Double firstLineHeight;

    /** Default space between lines */
    private final double lineSpacing;

    /** Space between a line and the line below it, by line name */
    @NotNull private final Map<String, Double> customLineSpacing;

    /** Condition a line requires to be displayed, by line name. Values are condition names or expressions */
    @NotNull private final Map<String, String> lineConditions;

    /** Texts of a line with the condition required to display them, by line name */
    @NotNull private final Map<String, List<LineCase>> lineCases;

    private final boolean lowerWhenSneaking;

    @NotNull private final String disableCondition;

    /** Whether players should see their own lines (visible in third person view) */
    private final boolean showToSelf;

    /** Condition a player must meet to see their own lines, empty to always show them */
    @NotNull private final String showToSelfCondition;

    /**
     * Returns space between given line and the line below it.
     *
     * @param   line
     *          Line name
     * @return  Space below the line in blocks
     */
    public double getSpacingBelow(@NotNull String line) {
        return customLineSpacing.getOrDefault(line, lineSpacing);
    }

    /**
     * One of the texts a line can display and the condition required to display it.
     */
    @Getter
    @AllArgsConstructor
    public static class LineCase {

        /** Condition name or expression required to display the text, {@code null} to always display it */
        @Nullable private final String condition;

        /** Text to display, multiple lines separated with new lines */
        @NotNull private final String text;
    }

    /**
     * Returns instance of this class created from given configuration section. If there are
     * issues in the configuration, console warns are printed.
     *
     * @param   section
     *          Configuration section to load from
     * @return  Loaded instance from given configuration section
     */
    @NotNull
    public static MultiLineConfiguration fromSection(@NotNull ConfigurationSection section) {
        section.checkForUnknownKey(Arrays.asList("enabled", "lines", "first-line-height", "line-spacing",
                "custom-line-spacing", "line-conditions", "line-cases", "lower-when-sneaking", "disable-condition",
                "show-to-self", "show-to-self-condition"));
        List<String> lines = new ArrayList<>();
        for (String line : section.getStringList("lines", Arrays.asList("abovename", NAMETAG_LINE, "belowname"))) {
            String name = line.toLowerCase(Locale.US);
            if (lines.contains(name)) {
                section.startupWarn("Multi-line nametag line \"" + name + "\" is defined twice. Ignoring the duplicate.");
                continue;
            }
            if (lines.size() == MAX_LINES) {
                section.startupWarn("Multi-line nametags support at most " + MAX_LINES + " lines. Ignoring line \"" + name + "\".");
                continue;
            }
            lines.add(name);
        }

        Double firstLineHeight = null;
        if (section.getObject("first-line-height") != null) {
            firstLineHeight = height(section, "first-line-height", section.getNumber("first-line-height").doubleValue(), 1.5, MAX_FIRST_LINE_HEIGHT);
        }
        double lineSpacing = height(section, "line-spacing", section.getNumber("line-spacing", 0.26).doubleValue(), 0, MAX_SPACING);
        Map<String, Double> customLineSpacing = new HashMap<>();
        for (Map.Entry<Object, Object> entry : section.<Object, Object>getMap("custom-line-spacing", Collections.emptyMap()).entrySet()) {
            String line = lineName(section, entry.getKey(), lines, "custom-line-spacing");
            if (line == null) continue;
            try {
                customLineSpacing.put(line, height(section, "custom-line-spacing." + line, Double.parseDouble(String.valueOf(entry.getValue())), 0, MAX_SPACING));
            } catch (NumberFormatException e) {
                section.startupWarn("custom-line-spacing of line \"" + line + "\" is not a number: " + entry.getValue());
            }
        }

        Map<String, String> lineConditions = new HashMap<>();
        for (Map.Entry<Object, Object> entry : section.<Object, Object>getMap("line-conditions", Collections.emptyMap()).entrySet()) {
            String line = lineName(section, entry.getKey(), lines, "line-conditions");
            if (line == null) continue;
            lineConditions.put(line, String.valueOf(entry.getValue()));
        }

        Map<String, List<LineCase>> lineCases = new HashMap<>();
        for (Map.Entry<Object, Object> entry : section.<Object, Object>getMap("line-cases", Collections.emptyMap()).entrySet()) {
            String line = lineName(section, entry.getKey(), lines, "line-cases");
            if (line == null) continue;
            List<LineCase> cases = readCases(section, line, entry.getValue());
            if (!cases.isEmpty()) lineCases.put(line, cases);
        }

        // Allow defining and changing lines as group/user properties
        addValidProperty("customtagname");
        for (String line : lines) {
            if (!line.equals(NAMETAG_LINE)) addValidProperty(line);
        }
        return new MultiLineConfiguration(section, lines, firstLineHeight, lineSpacing, customLineSpacing, lineConditions, lineCases,
                section.getBoolean("lower-when-sneaking", true), section.getString("disable-condition", "%world%=disabledworld"),
                section.getBoolean("show-to-self", false), section.getString("show-to-self-condition", ""));
    }

    /**
     * Reads texts and their conditions of a single line.
     *
     * @param   section
     *          Configuration section for printing warns
     * @param   line
     *          Name of the line the cases belong to
     * @param   value
     *          Configured value of the line
     * @return  Cases of the line in the configured order
     */
    @NotNull
    private static List<LineCase> readCases(@NotNull ConfigurationSection section, @NotNull String line, @Nullable Object value) {
        if (!(value instanceof List)) {
            section.startupWarn("line-cases of line \"" + line + "\" must be a list of texts with conditions.");
            return Collections.emptyList();
        }
        List<LineCase> cases = new ArrayList<>();
        for (Object element : (List<?>) value) {
            if (!(element instanceof Map)) {
                section.startupWarn("Each entry of line-cases of line \"" + line + "\" must have a \"text\" and optionally a \"condition\".");
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) element;
            for (Object key : map.keySet()) {
                if (!"condition".equals(key) && !"text".equals(key)) {
                    section.startupWarn("Unknown key \"" + key + "\" in line-cases of line \"" + line + "\", expected \"condition\" or \"text\".");
                }
            }
            Object text = map.get("text");
            if (text == null) {
                section.startupWarn("An entry of line-cases of line \"" + line + "\" is missing \"text\".");
                continue;
            }
            Object condition = map.get("condition");
            cases.add(new LineCase(condition == null ? null : String.valueOf(condition), toText(text)));
        }
        return cases;
    }

    /**
     * Converts configured text into a single string, joining lists with new lines the same way
     * group and user properties are joined.
     *
     * @param   value
     *          Configured value
     * @return  Text to display
     */
    @NotNull
    private static String toText(@NotNull Object value) {
        if (value instanceof List) {
            return ((List<?>) value).stream().map(String::valueOf).collect(Collectors.joining("\n"));
        }
        return String.valueOf(value);
    }

    /**
     * Returns configured line name if such line exists, {@code null} with a warn if it does not.
     *
     * @param   section
     *          Configuration section for printing warns
     * @param   key
     *          Configured key
     * @param   lines
     *          Configured lines
     * @param   option
     *          Name of the option the key belongs to, used in the warn message
     * @return  Line name or {@code null} if there is no such line
     */
    @Nullable
    private static String lineName(@NotNull ConfigurationSection section, @NotNull Object key, @NotNull List<String> lines, @NotNull String option) {
        String line = String.valueOf(key).toLowerCase(Locale.US);
        if (lines.contains(line)) return line;
        section.startupWarn(option + " contains line \"" + line + "\", which is not in the list of lines.");
        return null;
    }

    private static double height(@NotNull ConfigurationSection section, @NotNull String path, double value, double min, double max) {
        if (value < min || value > max) {
            double clamped = Math.max(min, Math.min(max, value));
            section.startupWarn("\"" + path + "\" is set to " + value + ", but it must be between " + min + " and " + max + ". Using " + clamped + ".");
            return clamped;
        }
        return value;
    }

    private static void addValidProperty(@NotNull String property) {
        if (!PropertyConfiguration.VALID_PROPERTIES.contains(property)) {
            PropertyConfiguration.VALID_PROPERTIES.add(property);
        }
    }
}
