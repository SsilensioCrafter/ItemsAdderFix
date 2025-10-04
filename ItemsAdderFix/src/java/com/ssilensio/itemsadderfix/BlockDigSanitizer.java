package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerDigType;

final class BlockDigSanitizer {
    interface ChunkLoadChecker {
        boolean isChunkLoaded(int chunkX, int chunkZ);
    }

    interface BlockPositionProvider {
        BlockPosition currentBlockPosition();
    }

    static final class Result {
        private static final Result ALLOW = new Result(false, null);
        private static final Result CANCEL = new Result(true, null);

        private final boolean cancel;
        private final BlockPosition replacement;

        private Result(boolean cancel, BlockPosition replacement) {
            this.cancel = cancel;
            this.replacement = replacement;
        }

        static Result allow() {
            return ALLOW;
        }

        static Result cancel() {
            return CANCEL;
        }

        static Result replace(BlockPosition replacement) {
            return replacement == null ? ALLOW : new Result(false, replacement);
        }

        boolean shouldCancel() {
            return cancel;
        }

        BlockPosition replacement() {
            return replacement;
        }
    }

    Result evaluate(PlayerDigType digType,
                    BlockPosition position,
                    ChunkLoadChecker checker,
                    BlockPositionProvider blockPositionProvider) {
        if (digType == null) {
            return Result.allow();
        }

        if (digType == PlayerDigType.START_DESTROY_BLOCK) {
            return sanitizeBlockStart(position, checker, blockPositionProvider);
        }
        if (digType == PlayerDigType.DROP_ITEM
                || digType == PlayerDigType.DROP_ALL_ITEMS
                || digType == PlayerDigType.RELEASE_USE_ITEM) {
            return sanitizeNonBlockAction(position, blockPositionProvider);
        }
        return Result.allow();
    }

    private Result sanitizeBlockStart(BlockPosition position,
                                      ChunkLoadChecker checker,
                                      BlockPositionProvider provider) {
        if (position == null) {
            return Result.allow();
        }

        BlockPosition target = position;
        boolean replacementApplied = false;

        if (provider != null && (position.getX() | position.getY() | position.getZ()) == 0) {
            try {
                BlockPosition candidate = provider.currentBlockPosition();
                if (candidate != null) {
                    target = candidate;
                    replacementApplied = true;
                }
            } catch (RuntimeException ex) {
                // Ignore and fall back to the original position
            }
        }

        if (checker == null) {
            return replacementApplied ? Result.replace(target) : Result.allow();
        }

        int chunkX = target.getX() >> 4;
        int chunkZ = target.getZ() >> 4;
        try {
            if (!checker.isChunkLoaded(chunkX, chunkZ)) {
                return Result.cancel();
            }
            return replacementApplied ? Result.replace(target) : Result.allow();
        } catch (RuntimeException ex) {
            return replacementApplied ? Result.replace(target) : Result.allow();
        }
    }

    private Result sanitizeNonBlockAction(BlockPosition position, BlockPositionProvider provider) {
        if (provider == null) {
            return Result.allow();
        }
        if (position != null && (position.getX() | position.getY() | position.getZ()) != 0) {
            return Result.allow();
        }
        try {
            return Result.replace(provider.currentBlockPosition());
        } catch (RuntimeException ex) {
            return Result.allow();
        }
    }
}
