package me.neznamy.tab.shared.features.disguise;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisguiseIdentityTest {

    private static final UUID REAL = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID DISGUISE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void pingMatchesBoltTweaksFormula() {
        // Same vectors as BoltTweaks DisguisePingTest: tablist and practice scoreboard must agree
        long seed = DisguiseIdentity.seed(REAL, "Kartal", DISGUISE);
        assertEquals(-4313851633079654931L, seed);
        assertEquals(70, DisguiseIdentity.ping(seed, 0L));
        assertEquals(70, DisguiseIdentity.ping(seed, 4_999L));
        assertEquals(77, DisguiseIdentity.ping(seed, 5_000L));
        long withoutDisguiseId = DisguiseIdentity.seed(REAL, "Kartal", null);
        assertEquals(4843789333684406259L, withoutDisguiseId);
        assertEquals(56, DisguiseIdentity.ping(withoutDisguiseId, 1_700_000_000_000L));
    }

    @Test
    void pingStaysInPlausibleRangeAndJitterIsSmall() {
        for (int i = 0; i < 500; i++) {
            DisguiseIdentity identity = new DisguiseIdentity(UUID.randomUUID(), "Name" + i, UUID.randomUUID());
            int first = identity.ping(0L);
            for (long t = 0; t < 120_000L; t += 1_000L) {
                int ping = identity.ping(t);
                assertTrue(ping >= DisguiseIdentity.MIN_BASE_PING - DisguiseIdentity.JITTER, "ping " + ping);
                assertTrue(ping <= DisguiseIdentity.MAX_BASE_PING + DisguiseIdentity.JITTER, "ping " + ping);
                assertTrue(Math.abs(ping - first) <= DisguiseIdentity.JITTER * 2, "jitter " + (ping - first));
            }
        }
    }

    @Test
    void sameSessionIsStableAndNewDisguiseDiffers() {
        DisguiseIdentity a = new DisguiseIdentity(REAL, "Kartal", DISGUISE);
        DisguiseIdentity b = new DisguiseIdentity(REAL, "kartal", DISGUISE);
        assertEquals(a.getSeed(), b.getSeed(), "name case must not matter");
        Set<Integer> bases = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            bases.add(new DisguiseIdentity(REAL, "Name" + i, UUID.randomUUID()).ping(0L));
        }
        assertTrue(bases.size() > 10, "different disguises must get different pings");
        assertNotEquals(new DisguiseIdentity(REAL, "Kartal", DISGUISE), new DisguiseIdentity(REAL, "Sahin", DISGUISE));
    }

    @Test
    void showsNameIgnoresColorCodes() {
        assertTrue(DisguiseIdentity.showsName("§f§cKartal", "Kartal"));
        assertTrue(DisguiseIdentity.showsName("§c❤ §7§oKar§ltal §8[TPVP]", "Kartal"));
        assertFalse(DisguiseIdentity.showsName("§f§cRealName", "Kartal"), "previous identity is stale");
        assertFalse(DisguiseIdentity.showsName("§fkartal", "Kartal"), "names are case-sensitive");
    }
}
