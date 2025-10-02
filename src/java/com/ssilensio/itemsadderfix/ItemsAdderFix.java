package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public final class ItemsAdderFix extends JavaPlugin {
    private final Gson gson = new Gson();
    private ProtocolManager protocolManager;
    private PacketAdapter listener;
    private boolean logFixes;

    @Override
    public void onEnable() {
        if (!isProtocolLibPresent()) {
            getLogger().severe("ProtocolLib is required to run ItemsAdderFix. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        reloadConfig();
        logFixes = getConfig().getBoolean("log-fixes", true);

        protocolManager = ProtocolLibrary.getProtocolManager();
        PacketType[] monitoredTypes = collectServerPlayPackets();
        listener = new PacketAdapter(this, ListenerPriority.LOWEST, monitoredTypes) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    normalizePacket(event.getPacket());
                } catch (Exception ex) {
                    getLogger().log(Level.SEVERE, "Failed to normalize packet " + event.getPacketType(), ex);
                }
            }
        };

        protocolManager.addPacketListener(listener);
        printBanner();
        getLogger().info("ItemsAdderFix enabled. Hover event UUID normalization active.");
    }

    @Override
    public void onDisable() {
        if (protocolManager != null && listener != null) {
            protocolManager.removePacketListener(listener);
        }
    }

    private boolean isProtocolLibPresent() {
        Plugin plugin = getServer().getPluginManager().getPlugin("ProtocolLib");
        return plugin != null && plugin.isEnabled();
    }

    private void normalizePacket(PacketContainer packet) {
        if (packet == null) {
            return;
        }

        var components = packet.getChatComponents();
        if (components == null) {
            return;
        }

        for (int i = 0; i < components.size(); i++) {
            WrappedChatComponent component;
            try {
                component = components.readSafely(i);
            } catch (Exception ignored) {
                continue;
            }

            if (component == null) {
                continue;
            }

            String json = component.getJson();
            if (json == null || json.isEmpty()) {
                continue;
            }

            String normalized = normalizeJson(json);
            if (!Objects.equals(json, normalized)) {
                components.write(i, WrappedChatComponent.fromJson(normalized));
            }
        }
    }

    private PacketType[] collectServerPlayPackets() {
        List<PacketType> types = new ArrayList<>();
        for (PacketType type : PacketType.values()) {
            if (!type.isSupported()) {
                continue;
            }
            if (type.getProtocol() != PacketType.Protocol.PLAY) {
                continue;
            }
            if (type.getSender() != PacketType.Sender.SERVER) {
                continue;
            }
            types.add(type);
        }
        return types.toArray(new PacketType[0]);
    }

    private String normalizeJson(String json) {
        JsonElement element;
        try {
            element = gson.fromJson(json, JsonElement.class);
        } catch (JsonParseException ex) {
            return json;
        }

        if (element == null || element.isJsonNull()) {
            return json;
        }

        boolean changed = normalizeElement(element);
        return changed ? gson.toJson(element) : json;
    }

    private boolean normalizeElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }

        boolean changed = false;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if ("hoverEvent".equals(entry.getKey()) && entry.getValue().isJsonObject()) {
                    changed |= normalizeHoverEvent(entry.getValue().getAsJsonObject());
                } else {
                    changed |= normalizeElement(entry.getValue());
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                changed |= normalizeElement(child);
            }
        }

        return changed;
    }

    private boolean normalizeHoverEvent(JsonObject hoverEvent) {
        boolean changed = false;

        String action = getString(hoverEvent, "action");
        if (action != null && "show_entity".equalsIgnoreCase(action)) {
            changed |= normalizeShowEntityPayload(hoverEvent, "value");
            changed |= normalizeShowEntityPayload(hoverEvent, "contents");
        }

        for (Map.Entry<String, JsonElement> entry : hoverEvent.entrySet()) {
            changed |= normalizeElement(entry.getValue());
        }

        return changed;
    }

    private boolean normalizeShowEntityPayload(JsonObject hoverEvent, String key) {
        if (!hoverEvent.has(key)) {
            return false;
        }

        JsonElement payload = hoverEvent.get(key);
        boolean changed = false;

        if (payload.isJsonObject()) {
            changed |= normalizeEntityTooltip(payload.getAsJsonObject());
        } else if (payload.isJsonArray()) {
            JsonArray array = payload.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    changed |= normalizeEntityTooltip(element.getAsJsonObject());
                }
                changed |= normalizeElement(element);
            }
        } else {
            changed |= normalizeElement(payload);
        }

        return changed;
    }

    private boolean normalizeEntityTooltip(JsonObject tooltip) {
        boolean changed = false;

        if (tooltip.has("id")) {
            JsonElement idElement = tooltip.get("id");
            String uuidString = extractUuid(idElement);
            if (uuidString != null) {
                String original = gson.toJson(idElement);
                tooltip.addProperty("id", uuidString);
                changed = true;
                logFix(original, uuidString);
            }
        }

        for (Map.Entry<String, JsonElement> entry : tooltip.entrySet()) {
            if ("id".equals(entry.getKey())) {
                continue;
            }
            changed |= normalizeElement(entry.getValue());
        }

        return changed;
    }

    private String extractUuid(JsonElement element) {
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
            JsonArray array = element.getAsJsonArray();
            if (array.size() != 4) {
                return null;
            }

            long[] parts = new long[4];
            for (int i = 0; i < 4; i++) {
                JsonElement part = array.get(i);
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                parts[i] = part.getAsLong() & 0xFFFFFFFFL;
            }

            long most = (parts[0] << 32) | parts[1];
            long least = (parts[2] << 32) | parts[3];
            return new UUID(most, least).toString();
        }

        if (element.isJsonObject()) {
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
        if (object.has(key)) {
            JsonElement element = object.get(key);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return element.getAsString();
            }
        }
        return null;
    }

    private void printBanner() {
        final String reset = "\u001B[0m";
        final String primary = "\u001B[38;5;219m";
        final String accent = "\u001B[38;5;39m";
        String[] lines = {
                primary + "██╗██╗   ██╗███████╗██╗██╗  ██╗" + reset,
                primary + "██║██║   ██║██╔════╝██║██║ ██╔╝" + reset,
                accent + "██║██║   ██║█████╗  ██║█████╔╝ " + reset,
                accent + "██║╚██╗ ██╔╝██╔══╝  ██║██╔═██╗ " + reset,
                primary + "██║ ╚████╔╝ ███████╗██║██║  ██╗" + reset,
                primary + "╚═╝  ╚═══╝  ╚══════╝╚═╝╚═╝  ╚═╝" + reset,
                accent + "           I A F I X" + reset
        };

        System.out.println();
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println();
    }

    private void logFix(String before, String after) {
        if (!logFixes) {
            return;
        }
        getLogger().info(() -> "Normalized hoverEvent entity id from XML payload " + before + " to " + after + ".");
    }
}
