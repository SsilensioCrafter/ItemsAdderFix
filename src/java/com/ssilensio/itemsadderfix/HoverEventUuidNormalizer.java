package com.ssilensio.itemsadderfix;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

final class HoverEventUuidNormalizer {
    private final Gson gson = new Gson();

    String normalize(String json,
                     NormalizationOptions options,
                     Consumer<NormalizationRecord> recordConsumer) {
        JsonElement element;
        try {
            element = gson.fromJson(json, JsonElement.class);
        } catch (JsonParseException ex) {
            return json;
        }

        if (element == null || element.isJsonNull()) {
            return json;
        }

        boolean changed = normalizeElement(element, options, recordConsumer);
        return changed ? gson.toJson(element) : json;
    }

    private boolean normalizeElement(JsonElement element,
                                     NormalizationOptions options,
                                     Consumer<NormalizationRecord> recordConsumer) {
        if (element == null || element.isJsonNull()) {
            return false;
        }

        boolean changed = false;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                JsonElement value = object.get(key);
                if ("hoverEvent".equals(key) && value != null && value.isJsonObject()) {
                    changed |= normalizeHoverEvent(value.getAsJsonObject(), options, recordConsumer);
                } else {
                    changed |= normalizeElement(value, options, recordConsumer);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                changed |= normalizeElement(child, options, recordConsumer);
            }
        }

        return changed;
    }

    private boolean normalizeHoverEvent(JsonObject hoverEvent,
                                        NormalizationOptions options,
                                        Consumer<NormalizationRecord> recordConsumer) {
        boolean changed = false;

        String action = getString(hoverEvent, "action");
        if (action != null && "show_entity".equalsIgnoreCase(action)) {
            changed |= normalizeShowEntityPayload(hoverEvent, "value", options, recordConsumer);
            changed |= normalizeShowEntityPayload(hoverEvent, "contents", options, recordConsumer);
        }

        for (String key : hoverEvent.keySet()) {
            if ("value".equals(key) || "contents".equals(key)) {
                continue;
            }
            changed |= normalizeElement(hoverEvent.get(key), options, recordConsumer);
        }

        return changed;
    }

    private boolean normalizeShowEntityPayload(JsonObject hoverEvent,
                                               String key,
                                               NormalizationOptions options,
                                               Consumer<NormalizationRecord> recordConsumer) {
        if (!hoverEvent.has(key)) {
            return false;
        }

        JsonElement payload = hoverEvent.get(key);
        boolean changed = false;

        if (payload.isJsonObject()) {
            changed |= normalizeEntityTooltip(payload.getAsJsonObject(), options, recordConsumer);
        } else if (payload.isJsonArray()) {
            for (JsonElement element : payload.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    changed |= normalizeEntityTooltip(element.getAsJsonObject(), options, recordConsumer);
                }
                changed |= normalizeElement(element, options, recordConsumer);
            }
        } else {
            changed |= normalizeElement(payload, options, recordConsumer);
        }

        return changed;
    }

    private boolean normalizeEntityTooltip(JsonObject tooltip,
                                           NormalizationOptions options,
                                           Consumer<NormalizationRecord> recordConsumer) {
        boolean changed = false;

        if (tooltip.has("id")) {
            JsonElement idElement = tooltip.get("id");
            String uuidString = extractUuid(idElement, options);
            if (uuidString != null) {
                String original = gson.toJson(idElement);
                tooltip.addProperty("id", uuidString);
                changed = true;

                if (recordConsumer != null) {
                    recordConsumer.accept(new NormalizationRecord(original, uuidString));
                }
            }
        }

        for (String key : tooltip.keySet()) {
            if ("id".equals(key)) {
                continue;
            }
            changed |= normalizeElement(tooltip.get(key), options, recordConsumer);
        }

        return changed;
    }

    private String extractUuid(JsonElement element, NormalizationOptions options) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                return null;
            }
            return null;
        }

        if (element.isJsonArray()) {
            if (!options.convertIntArrayPayloads()) {
                return null;
            }
            return extractUuidFromIntArray(element.getAsJsonArray());
        }

        if (element.isJsonObject()) {
            if (!options.convertUuidObjectPayloads()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("most") && object.has("least")) {
                try {
                    long most = object.get("most").getAsLong();
                    long least = object.get("least").getAsLong();
                    return new UUID(most, least).toString();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }

        return null;
    }

    private String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    static String extractUuidFromIntArray(JsonArray array) {
        Objects.requireNonNull(array, "array");

        int size = array.size();
        if (size == 4) {
            long[] parts = new long[4];
            for (int i = 0; i < 4; i++) {
                JsonElement part = array.get(i);
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                long value = part.getAsLong();
                parts[i] = value & 0xFFFFFFFFL;
            }

            long most = (parts[0] << 32) | parts[1];
            long least = (parts[2] << 32) | parts[3];
            return new UUID(most, least).toString();
        }

        if (size == 16) {
            byte[] bytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                JsonElement part = array.get(i);
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                long value = part.getAsLong();
                bytes[i] = (byte) (value & 0xFF);
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long most = buffer.getLong();
            long least = buffer.getLong();
            return new UUID(most, least).toString();
        }

        return null;
    }

    record NormalizationOptions(boolean convertIntArrayPayloads, boolean convertUuidObjectPayloads) {}

    record NormalizationRecord(String originalPayload, String normalizedUuid) {}
}
