package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDigSanitizerTest {
    private final BlockDigSanitizer sanitizer = new BlockDigSanitizer();

    @Test
    void cancelsWhenChunkNotLoaded() {
        BlockPosition position = new BlockPosition(32, 70, -48);
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.START_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> false,
                null
        );
        assertTrue(result.shouldCancel());
        assertNull(result.replacement());
    }

    @Test
    void allowsWhenChunkLoaded() {
        BlockPosition position = new BlockPosition(128, 64, 128);
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.START_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> true,
                null
        );
        assertFalse(result.shouldCancel());
        assertNull(result.replacement());
    }

    @Test
    void ignoresNonStartDigTypesForCancellation() {
        BlockPosition position = new BlockPosition(0, 64, 0);
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> false,
                null
        );
        assertFalse(result.shouldCancel());
        assertNull(result.replacement());
    }

    @Test
    void ignoresWhenCheckerThrows() {
        BlockPosition position = new BlockPosition(0, 64, 0);
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.START_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> {
                    throw new IllegalStateException("boom");
                },
                null
        );
        assertFalse(result.shouldCancel());
        assertNull(result.replacement());
    }

    @Test
    void replacesZeroPositionDrops() {
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.DROP_ITEM,
                new BlockPosition(0, 0, 0),
                null,
                () -> new BlockPosition(12, 64, -5)
        );
        assertFalse(result.shouldCancel());
        BlockPosition replacement = result.replacement();
        assertNotNull(replacement);
        assertEquals(12, replacement.getX());
        assertEquals(64, replacement.getY());
        assertEquals(-5, replacement.getZ());
    }

    @Test
    void leavesDropsUnchangedWhenPositionAlreadyValid() {
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.DROP_ALL_ITEMS,
                new BlockPosition(10, 70, 10),
                null,
                () -> new BlockPosition(12, 64, -5)
        );
        assertFalse(result.shouldCancel());
        assertNull(result.replacement());
    }

    @Test
    void ignoresProviderFailures() {
        BlockDigSanitizer.Result result = sanitizer.evaluate(
                EnumWrappers.PlayerDigType.DROP_ITEM,
                new BlockPosition(0, 0, 0),
                null,
                () -> {
                    throw new IllegalStateException("boom");
                }
        );
        assertFalse(result.shouldCancel());
        assertNull(result.replacement());
    }
}
