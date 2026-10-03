package me.neznamy.tab.shared.task;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PluginMessageDecodeTaskTest {
    @Test
    void emptyAndUnknownMessageTypesAreIgnored() {
        for (byte[] message : new byte[][] { {}, {11}, {(byte) 128}, {(byte) 255} }) {
            assertDoesNotThrow(() -> new PluginMessageDecodeTask(UUID.randomUUID(), message).run());
        }
    }
}
