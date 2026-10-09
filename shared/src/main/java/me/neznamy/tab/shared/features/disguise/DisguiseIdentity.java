package me.neznamy.tab.shared.features.disguise;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What TAB shows for a disguised player instead of the real identity: the disguise name and a ping
 * derived from the disguise. Two identities are equal when they describe the same disguise session.
 * <p>
 * The ping formula must stay identical to BoltTweaks' {@code DisguisePing}, so the tablist bars and
 * the practice scoreboard agree. Both have a test pinning the same values.
 */
@Getter
@EqualsAndHashCode
public final class DisguiseIdentity {

    /** A legacy color or format code (section sign and one character) */
    private static final Pattern LEGACY_CODE = Pattern.compile("§.");

    /** Lowest base ping a disguise can get */
    public static final int MIN_BASE_PING = 25;

    /** Highest base ping a disguise can get */
    public static final int MAX_BASE_PING = 90;

    /** Shown ping moves this much around the base, at most */
    public static final int JITTER = 5;

    /** How long one jitter value is shown */
    public static final long JITTER_WINDOW_MILLIS = 5000L;

    /** Name the disguise uses */
    @NotNull private final String name;

    /** Stable random seed of this disguise session */
    private final long seed;

    /**
     * Creates the identity of one disguise session.
     *
     * @param   realId
     *          Player's real UUID
     * @param   name
     *          Disguise name
     * @param   disguiseId
     *          UUID the disguise uses, {@code null} if unknown
     */
    public DisguiseIdentity(@NotNull UUID realId, @NotNull String name, @Nullable UUID disguiseId) {
        this.name = name;
        this.seed = seed(realId, name, disguiseId);
    }

    /**
     * Ping to show at the given time: a stable base between {@link #MIN_BASE_PING} and {@link #MAX_BASE_PING}
     * plus a jitter of up to {@link #JITTER} that changes every {@link #JITTER_WINDOW_MILLIS}.
     *
     * @param   nowMillis
     *          Current time in milliseconds
     * @return  Ping to show
     */
    public int ping(long nowMillis) {
        return ping(seed, nowMillis);
    }

    /**
     * Seed of a disguise session.
     *
     * @param   realId
     *          Player's real UUID
     * @param   name
     *          Disguise name
     * @param   disguiseId
     *          UUID the disguise uses, {@code null} if unknown
     * @return  Seed
     */
    public static long seed(@NotNull UUID realId, @NotNull String name, @Nullable UUID disguiseId) {
        long seed = realId.getMostSignificantBits() ^ Long.rotateLeft(realId.getLeastSignificantBits(), 17);
        seed = mix(seed ^ name.toLowerCase(Locale.ROOT).hashCode());
        if (disguiseId != null) {
            seed = mix(seed ^ disguiseId.getMostSignificantBits() ^ Long.rotateLeft(disguiseId.getLeastSignificantBits(), 29));
        }
        return seed;
    }

    /**
     * Ping to show for a seed at the given time.
     *
     * @param   seed
     *          Seed of the disguise session
     * @param   nowMillis
     *          Current time in milliseconds
     * @return  Ping to show
     */
    public static int ping(long seed, long nowMillis) {
        int base = MIN_BASE_PING + (int) Math.floorMod(mix(seed), (long) (MAX_BASE_PING - MIN_BASE_PING + 1));
        long window = Math.floorDiv(nowMillis, JITTER_WINDOW_MILLIS);
        int jitter = (int) Math.floorMod(mix(seed ^ mix(window)), (long) (JITTER * 2 + 1)) - JITTER;
        return base + jitter;
    }

    /**
     * SplitMix64 finalizer.
     *
     * @param   value
     *          Value to mix
     * @return  Mixed value
     */
    /**
     * Tells whether a legacy formatted text shows the given name (color codes ignored, case-sensitive).
     *
     * @param   legacyText
     *          Text with legacy color codes
     * @param   name
     *          Name to look for
     * @return  {@code true} if the text contains the name
     */
    public static boolean showsName(@NotNull String legacyText, @NotNull String name) {
        return LEGACY_CODE.matcher(legacyText).replaceAll("").contains(name);
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
