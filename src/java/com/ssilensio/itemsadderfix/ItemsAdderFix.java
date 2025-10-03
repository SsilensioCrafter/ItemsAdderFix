package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

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

public final class ItemsAdderFix extends JavaPlugin {
    private ProtocolManager protocolManager;
    private PacketAdapter listener;

    private boolean pluginEnabled;
    private boolean normalizationEnabled;
    private boolean convertIntArrayPayloads;
    private boolean convertUuidObjectPayloads;
    private boolean debugLogging;
    private boolean logFixes;
    private boolean includeOriginalPayload;
    private boolean includeNormalizedPayload;
    private String handledErrorsFileName;
    private File handledErrorsFile;

    private final Object handledErrorsLock = new Object();
    private HoverEventUuidNormalizer normalizer;

    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String[] BANNER_LINES = {
            "███████   █████       ███████  ███████  ███ ███",
            "  ███    ███ ███      ███        ███     ████ ",
            "  ███    ███████      ██████     ███      ███ ",
            "  ███    ███ ███      ███        ███     ████ ",
            ANSI_BOLD + "███████  ███ ███      ███      ███████  ███ ███" + ANSI_RESET
    };

    @Override
    public void onEnable() {
        if (!isProtocolLibPresent()) {
            getLogger().severe("ProtocolLib is required to run ItemsAdderFix. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        reloadConfig();

        pluginEnabled = getConfig().getBoolean("enabled", true);
        debugLogging = getConfig().getBoolean("debug", false);
        normalizationEnabled = getConfig().getBoolean("normalization.hover_event_uuid.enabled", true);
        convertIntArrayPayloads = getConfig().getBoolean("normalization.hover_event_uuid.convert.int_array", true);
        convertUuidObjectPayloads = getConfig().getBoolean("normalization.hover_event_uuid.convert.uuid_object", true);

        includeOriginalPayload = getConfig().getBoolean("logging.handled_errors.include_original_payload", true);
        includeNormalizedPayload = getConfig().getBoolean("logging.handled_errors.include_normalized_payload", true);
        handledErrorsFileName = getConfig().getString("logging.handled_errors.file", "handled-errors.xml");
        if (handledErrorsFileName == null || handledErrorsFileName.isBlank()) {
            handledErrorsFileName = "handled-errors.xml";
        }
        logFixes = getConfig().getBoolean("logging.handled_errors.enabled", true)
                && (includeOriginalPayload || includeNormalizedPayload);

        if (!pluginEnabled) {
            getLogger().info("Plugin disabled via configuration. No packets will be processed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (logFixes) {
            initializeHandledErrorsLog();
        } else {
            handledErrorsFile = null;
        }

        normalizer = new HoverEventUuidNormalizer();

        if (normalizationEnabled) {
            registerPacketListener();
        } else {
            getLogger().info("Hover event normalization is disabled via configuration.");
        }

        printBanner();
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

    private void registerPacketListener() {
        protocolManager = ProtocolLibrary.getProtocolManager();
        Set<PacketType> monitoredTypes = collectServerPlayPackets();
        HoverEventUuidNormalizer.NormalizationOptions options = new HoverEventUuidNormalizer.NormalizationOptions(
                convertIntArrayPayloads,
                convertUuidObjectPayloads
        );

        listener = new PacketAdapter(this, ListenerPriority.LOWEST, monitoredTypes.toArray(PacketType[]::new)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    normalizePacket(event.getPacket(), options);
                } catch (Exception ex) {
                    getLogger().log(Level.SEVERE, "Failed to normalize packet " + event.getPacketType(), ex);
                }
            }
        };
        protocolManager.addPacketListener(listener);
    }

    private Set<PacketType> collectServerPlayPackets() {
        Set<PacketType> types = new LinkedHashSet<>();
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
        return types;
    }

    private void normalizePacket(PacketContainer packet, HoverEventUuidNormalizer.NormalizationOptions options) {
        if (packet == null) {
            return;
        }

        Consumer<HoverEventUuidNormalizer.NormalizationRecord> fixLogger = record -> {
            if (logFixes) {
                logFix(record.originalPayload(), record.normalizedUuid());
            }
            if (debugLogging) {
                getLogger().info(() -> "Normalized hoverEvent UUID " + record.originalPayload()
                        + " -> " + record.normalizedUuid());
            }
        };

        normalizeComponentModifier(packet.getChatComponents(), options, fixLogger);

        // Some packets expose chat components via their generic modifier instead of getChatComponents().
        StructureModifier<WrappedChatComponent> modifier = packet.getModifier().withType(WrappedChatComponent.class);
        if (modifier != null && modifier != packet.getChatComponents()) {
            normalizeComponentModifier(modifier, options, fixLogger);
        }
    }

    private void normalizeComponentModifier(StructureModifier<WrappedChatComponent> modifier,
                                            HoverEventUuidNormalizer.NormalizationOptions options,
                                            Consumer<HoverEventUuidNormalizer.NormalizationRecord> fixLogger) {
        if (modifier == null) {
            return;
        }

        for (int index = 0; index < modifier.size(); index++) {
            WrappedChatComponent component = modifier.readSafely(index);
            if (component == null) {
                continue;
            }

            String json = component.getJson();
            if (json == null || json.isEmpty()) {
                continue;
            }

            String normalized = normalizer.normalize(json, options, fixLogger);
            if (!Objects.equals(json, normalized)) {
                modifier.writeSafely(index, WrappedChatComponent.fromJson(normalized));
            }
        }
    }

    private void printBanner() {
        getLogger().info(" ");
        for (String line : BANNER_LINES) {
            getLogger().info(line);
        }
        getLogger().info(" ");
    }

    private void logFix(String before, String after) {
        if (!logFixes || handledErrorsFile == null) {
            return;
        }
        if (!includeOriginalPayload && !includeNormalizedPayload) {
            return;
        }
        writeHandledError(before, after);
    }

    private void initializeHandledErrorsLog() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Unable to create plugin data folder; handled error logging disabled.");
            handledErrorsFile = null;
            return;
        }

        handledErrorsFile = new File(dataFolder, handledErrorsFileName);

        synchronized (handledErrorsLock) {
            if (!handledErrorsFile.exists() || handledErrorsFile.length() == 0) {
                try {
                    DocumentBuilder builder = newDocumentBuilder();
                    Document document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                } catch (ParserConfigurationException | TransformerException | IOException ex) {
                    getLogger().log(Level.WARNING, "Unable to initialize " + handledErrorsFileName, ex);
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
                getLogger().log(Level.WARNING, "Failed to verify " + handledErrorsFileName + "; recreating file.", ex);
                try {
                    DocumentBuilder builder = newDocumentBuilder();
                    Document document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                } catch (ParserConfigurationException | TransformerException | IOException recreateEx) {
                    getLogger().log(Level.WARNING, "Unable to recreate " + handledErrorsFileName, recreateEx);
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

                boolean wroteContent = false;
                if (includeOriginalPayload) {
                    Element original = document.createElement("original");
                    original.appendChild(document.createCDATASection(before));
                    entry.appendChild(original);
                    wroteContent = true;
                }
                if (includeNormalizedPayload) {
                    Element normalized = document.createElement("normalized");
                    normalized.appendChild(document.createCDATASection(after));
                    entry.appendChild(normalized);
                    wroteContent = true;
                }

                if (!wroteContent) {
                    return;
                }

                root.appendChild(entry);

                writeDocument(document);
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Unable to write handled error entry to " + handledErrorsFileName, ex);
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
