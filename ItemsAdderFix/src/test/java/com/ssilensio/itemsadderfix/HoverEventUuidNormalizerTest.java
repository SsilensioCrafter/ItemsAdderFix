package com.ssilensio.itemsadderfix;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HoverEventUuidNormalizerTest {
    private final HoverEventUuidNormalizer normalizer = new HoverEventUuidNormalizer();
    private final HoverEventUuidNormalizer.NormalizationOptions options =
            new HoverEventUuidNormalizer.NormalizationOptions(true, true);

    @Test
    void convertsSignedByteArrayUuidPayload() {
        UUID expected = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");
        JsonArray payload = uuidToJsonByteArray(expected, value -> value);

        String normalized = HoverEventUuidNormalizer.extractUuidFromIntArray(payload);
        assertEquals(expected.toString(), normalized);
    }

    @Test
    void convertsOversizedByteArrayUuidPayload() {
        UUID expected = UUID.fromString("0f25d8f8-0e46-42fb-86cf-4b761cddf0aa");
        JsonArray payload = uuidToJsonByteArray(expected, value -> (value & 0xFF) + 256);

        String normalized = HoverEventUuidNormalizer.extractUuidFromIntArray(payload);
        assertEquals(expected.toString(), normalized);
    }

    @Test
    void convertsFourIntegerUuidPayloadWithNegativeNumbers() {
        UUID expected = UUID.fromString("d4f90264-12e7-4e12-9d46-9b58a3a1c0ad");
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(expected.getMostSignificantBits());
        buffer.putLong(expected.getLeastSignificantBits());
        JsonArray payload = new JsonArray();
        buffer.rewind();
        for (int i = 0; i < 4; i++) {
            int chunk = buffer.getInt();
            payload.add(new JsonPrimitive(chunk));
        }

        String normalized = HoverEventUuidNormalizer.extractUuidFromIntArray(payload);
        assertEquals(expected.toString(), normalized);
    }

    @Test
    void normalizesNestedHoverEventPayloads() {
        UUID expected = UUID.fromString("8c2d12d7-0a8f-4e36-9c07-4f8e8d86a321");
        JsonObject tooltip = new JsonObject();
        tooltip.add("id", uuidToJsonByteArray(expected, value -> value));
        tooltip.addProperty("name", "Villager");

        JsonObject contents = new JsonObject();
        contents.add("type", new JsonPrimitive("minecraft:villager"));
        contents.add("id", uuidToJsonByteArray(expected, value -> (value & 0xFF) + 1024));
        contents.add("nbt", new JsonPrimitive("{}"));

        JsonObject hoverEvent = new JsonObject();
        hoverEvent.addProperty("action", "show_entity");
        hoverEvent.add("value", tooltip);
        hoverEvent.add("contents", contents);

        JsonObject component = new JsonObject();
        component.addProperty("text", "Villager");
        component.add("hoverEvent", hoverEvent);

        AtomicReference<String> lastOriginal = new AtomicReference<>();
        AtomicReference<String> lastNormalized = new AtomicReference<>();

        String normalized = normalizer.normalize(component.toString(), options, record -> {
            lastOriginal.set(record.originalPayload());
            lastNormalized.set(record.normalizedUuid());
        });

        assertNotNull(normalized);
        JsonObject normalizedJson = JsonParser.parseString(normalized).getAsJsonObject();
        JsonObject normalizedHoverEvent = normalizedJson.getAsJsonObject("hoverEvent");
        assertEquals(expected.toString(), normalizedHoverEvent.getAsJsonObject("value").get("id").getAsString());
        assertEquals(expected.toString(), normalizedHoverEvent.getAsJsonObject("contents").get("id").getAsString());

        assertNotNull(lastOriginal.get());
        assertNotNull(lastNormalized.get());
    }

    @Test
    void ignoresAlreadyNormalizedPayloads() {
        JsonObject tooltip = new JsonObject();
        tooltip.addProperty("id", UUID.randomUUID().toString());

        JsonObject hoverEvent = new JsonObject();
        hoverEvent.addProperty("action", "show_entity");
        hoverEvent.add("value", tooltip);

        JsonObject component = new JsonObject();
        component.add("hoverEvent", hoverEvent);

        String result = normalizer.normalize(component.toString(), options, record -> {
            throw new AssertionError("No normalization should occur");
        });

        assertEquals(component.toString(), result);
    }

    @Test
    void returnsNullWhenArraySizeUnexpected() {
        JsonArray payload = new JsonArray();
        payload.add(new JsonPrimitive(1));
        payload.add(new JsonPrimitive(2));
        payload.add(new JsonPrimitive(3));
        assertNull(HoverEventUuidNormalizer.extractUuidFromIntArray(payload));
    }

    private JsonArray uuidToJsonByteArray(UUID uuid, java.util.function.IntUnaryOperator mapper) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());

        JsonArray array = new JsonArray();
        for (byte value : buffer.array()) {
            int mapped = mapper.applyAsInt(value);
            array.add(new JsonPrimitive(mapped));
        }
        return array;
    }
}
