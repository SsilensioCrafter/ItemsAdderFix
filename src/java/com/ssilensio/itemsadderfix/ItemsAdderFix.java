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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
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
    private File handledErrorsFile;
    private final Object handledErrorsLock = new Object();

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
        if (logFixes) {
            initializeHandledErrorsLog();
        } else {
            handledErrorsFile = null;
        }

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
        String[] lines = {
                "███████   █████       ███████  ███████  ███ ███",
                "  ███    ███ ███      ███        ███     ████ ",
                "  ███    ███████      ██████     ███      ███ ",
                "  ███    ███ ███      ███        ███     ████ ",
                "███████  ███ ███      ███      ███████  ███ ███"
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
        writeHandledError(before, after);
    }

    private void initializeHandledErrorsLog() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Unable to create plugin data folder; handled error logging disabled.");
            handledErrorsFile = null;
            return;
        }

        handledErrorsFile = new File(dataFolder, "handled-errors.xml");

        synchronized (handledErrorsLock) {
            if (!handledErrorsFile.exists() || handledErrorsFile.length() == 0) {
                try {
                    DocumentBuilder builder = newDocumentBuilder();
                    Document document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                } catch (ParserConfigurationException | TransformerException | IOException ex) {
                    getLogger().log(Level.WARNING, "Unable to initialize handled-errors.xml", ex);
                    handledErrorsFile = null;
                }
                return;
            }

            try {
                DocumentBuilder builder = newDocumentBuilder();
                Document document;
                try (FileInputStream inputStream = new FileInputStream(handledErrorsFile)) {
                    document = builder.parse(inputStream);
                }
                if (document.getDocumentElement() == null) {
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                }
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Failed to verify handled-errors.xml; recreating file.", ex);
                try {
                    DocumentBuilder builder = newDocumentBuilder();
                    Document document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                } catch (ParserConfigurationException | TransformerException | IOException recreateEx) {
                    getLogger().log(Level.WARNING, "Unable to recreate handled-errors.xml", recreateEx);
                    handledErrorsFile = null;
                }
            }
        }
    }

    private void writeHandledError(String before, String after) {
        if (handledErrorsFile == null) {
            return;
        }

        synchronized (handledErrorsLock) {
            try {
                DocumentBuilder builder = newDocumentBuilder();
                Document document;
                if (handledErrorsFile.exists() && handledErrorsFile.length() > 0) {
                    try (FileInputStream inputStream = new FileInputStream(handledErrorsFile)) {
                        document = builder.parse(inputStream);
                    }
                } else {
                    document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                }

                Element root = document.getDocumentElement();
                if (root == null) {
                    root = document.createElement("handledErrors");
                    document.appendChild(root);
                }

                Element entry = document.createElement("handledError");
                entry.setAttribute("timestamp", Instant.now().toString());

                Element original = document.createElement("original");
                original.appendChild(document.createCDATASection(before));
                Element normalized = document.createElement("normalized");
                normalized.appendChild(document.createCDATASection(after));

                entry.appendChild(original);
                entry.appendChild(normalized);
                root.appendChild(entry);

                writeDocument(document);
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Unable to write handled error entry to handled-errors.xml", ex);
            }
        }
    }

    private DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setIgnoringComments(true);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException ignored) {
            // If a feature isn't supported, we proceed with the defaults.
        }
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // Attributes may not be supported by all parser implementations.
        }
        return factory.newDocumentBuilder();
    }

    private Transformer newTransformer() throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException | TransformerException ignored) {
            // Some implementations may not support these attributes.
        }
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        return transformer;
    }

    private void writeDocument(Document document) throws TransformerException, IOException {
        if (handledErrorsFile == null) {
            return;
        }

        Transformer transformer = newTransformer();
        DOMSource source = new DOMSource(document);
        try (FileOutputStream outputStream = new FileOutputStream(handledErrorsFile, false)) {
            StreamResult result = new StreamResult(outputStream);
            transformer.transform(source, result);
        }
    }
}
