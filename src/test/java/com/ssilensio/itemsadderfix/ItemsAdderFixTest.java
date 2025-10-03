package com.ssilensio.itemsadderfix;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemsAdderFixTest {
    @Test
    void convertsSignedByteArrayUuidPayload() throws Exception {
        UUID expected = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(expected.getMostSignificantBits());
        buffer.putLong(expected.getLeastSignificantBits());
        byte[] uuidBytes = buffer.array();

        JsonArray payload = new JsonArray();
        for (byte value : uuidBytes) {
            payload.add(new JsonPrimitive((int) value));
        }

        String normalized = ItemsAdderFix.extractUuidFromIntArray(payload);
        assertEquals(expected.toString(), normalized);
    }
}
