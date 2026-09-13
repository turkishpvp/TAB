package me.neznamy.tab.shared.features.nametags;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.neznamy.tab.shared.config.PropertyConfiguration;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Class for storing multi-line nametag configuration settings.
 */
@Getter
@AllArgsConstructor
public class MultiLineConfiguration {

    /** Name of the line displaying tagprefix + customtagname + tagsuffix */
    public static final String NAMETAG_LINE = "nametag";

    /** Maximum amount of lines, limited by fake entity id block size */
    public static final int MAX_LINES = 20;

    @NotNull private final ConfigurationSection section;

    /** Lines from top to bottom */
    @NotNull private final List<String> lines;

    @NotNull private final String disableCondition;

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
        section.checkForUnknownKey(Arrays.asList("enabled", "lines", "disable-condition"));
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
        // Allow defining and changing lines as group/user properties
        addValidProperty("customtagname");
        for (String line : lines) {
            if (!line.equals(NAMETAG_LINE)) addValidProperty(line);
        }
        return new MultiLineConfiguration(section, lines, section.getString("disable-condition", "%world%=disabledworld"));
    }

    private static void addValidProperty(@NotNull String property) {
        if (!PropertyConfiguration.VALID_PROPERTIES.contains(property)) {
            PropertyConfiguration.VALID_PROPERTIES.add(property);
        }
    }
}
