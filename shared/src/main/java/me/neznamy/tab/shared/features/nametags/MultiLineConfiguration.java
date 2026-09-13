package me.neznamy.tab.shared.features.nametags;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.neznamy.tab.shared.config.PropertyConfiguration;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

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

    private final boolean lowerWhenSneaking;

    @NotNull private final String disableCondition;

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
                "custom-line-spacing", "lower-when-sneaking", "disable-condition"));
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
        for (Map.Entry<Object, Object> entry : section.getMap("custom-line-spacing", Collections.emptyMap()).entrySet()) {
            String line = String.valueOf(entry.getKey()).toLowerCase(Locale.US);
            if (!lines.contains(line)) {
                section.startupWarn("custom-line-spacing defines spacing for line \"" + line + "\", which is not in the list of lines.");
                continue;
            }
            try {
                customLineSpacing.put(line, height(section, "custom-line-spacing." + line, Double.parseDouble(String.valueOf(entry.getValue())), 0, MAX_SPACING));
            } catch (NumberFormatException e) {
                section.startupWarn("custom-line-spacing of line \"" + line + "\" is not a number: " + entry.getValue());
            }
        }

        // Allow defining and changing lines as group/user properties
        addValidProperty("customtagname");
        for (String line : lines) {
            if (!line.equals(NAMETAG_LINE)) addValidProperty(line);
        }
        return new MultiLineConfiguration(section, lines, firstLineHeight, lineSpacing, customLineSpacing,
                section.getBoolean("lower-when-sneaking", true), section.getString("disable-condition", "%world%=disabledworld"));
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
