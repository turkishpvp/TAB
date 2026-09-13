package me.neznamy.tab.shared.features.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LayoutPageTest {

    @Test
    void pagesCycleAndWrapAroundToTheFirstOne() {
        assertEquals(2, LayoutManagerImpl.nextPage(1, 3));
        assertEquals(3, LayoutManagerImpl.nextPage(2, 3));
        assertEquals(1, LayoutManagerImpl.nextPage(3, 3));
    }

    @Test
    void singlePageAlwaysStaysOnTheFirstPage() {
        assertEquals(1, LayoutManagerImpl.nextPage(1, 1));
    }
}
