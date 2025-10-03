package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDigSanitizerTest {
    private final BlockDigSanitizer sanitizer = new BlockDigSanitizer();

    @Test
    void cancelsWhenChunkNotLoaded() {
        BlockPosition position = new BlockPosition(32, 70, -48);
        boolean cancel = sanitizer.shouldCancel(
                EnumWrappers.PlayerDigType.START_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> false
        );
        assertTrue(cancel);
    }

    @Test
    void allowsWhenChunkLoaded() {
        BlockPosition position = new BlockPosition(128, 64, 128);
        boolean cancel = sanitizer.shouldCancel(
                EnumWrappers.PlayerDigType.START_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> true
        );
        assertFalse(cancel);
    }

    @Test
    void ignoresNonStartDigTypes() {
        BlockPosition position = new BlockPosition(0, 64, 0);
        boolean cancel = sanitizer.shouldCancel(
                EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> false
        );
        assertFalse(cancel);
    }

    @Test
    void ignoresWhenCheckerThrows() {
        BlockPosition position = new BlockPosition(0, 64, 0);
        boolean cancel = sanitizer.shouldCancel(
                EnumWrappers.PlayerDigType.START_DESTROY_BLOCK,
                position,
                (chunkX, chunkZ) -> {
                    throw new IllegalStateException("boom");
                }
        );
        assertFalse(cancel);
    }
}
