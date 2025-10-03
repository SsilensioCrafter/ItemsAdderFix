package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;

final class BlockDigSanitizer {
    interface ChunkLoadChecker {
        boolean isChunkLoaded(int chunkX, int chunkZ);
    }

    boolean shouldCancel(EnumWrappers.PlayerDigType digType,
                         BlockPosition position,
                         ChunkLoadChecker checker) {
        if (digType != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
            return false;
        }
        if (position == null || checker == null) {
            return false;
        }

        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;
        try {
            return !checker.isChunkLoaded(chunkX, chunkZ);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
