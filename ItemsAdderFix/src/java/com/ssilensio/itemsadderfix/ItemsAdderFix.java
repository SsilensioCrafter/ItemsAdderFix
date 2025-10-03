package com.ssilensio.itemsadderfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerDigType;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.ssilensio.itemsadderfix.logging.HandledErrorLogger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ItemsAdderFix extends JavaPlugin {
    private static final String CONFIG_LOGGING_ENABLED = "logging.handled_errors.enabled";
    private static final String CONFIG_LOGGING_FILE = "logging.handled_errors.file";
    private static final String CONFIG_LOGGING_INCLUDE_ORIGINAL = "logging.handled_errors.include_original_payload";
    private static final String CONFIG_LOGGING_INCLUDE_NORMALIZED = "logging.handled_errors.include_normalized_payload";

    private ProtocolManager protocolManager;
    private final Set<PacketAdapter> listeners = new LinkedHashSet<>();
    private HoverEventUuidNormalizer normalizer;
    private final BlockDigSanitizer blockDigSanitizer = new BlockDigSanitizer();
    private HandledErrorLogger handledErrorLogger;
    private boolean debugLogging;
    private boolean logFixes;
    private boolean convertIntArrayPayloads;
    private boolean convertUuidObjectPayloads;
    private boolean normalizationEnabled;
    private boolean preventUnloadedChunkDig;

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

        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().info("Plugin disabled via configuration. No packets will be processed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        debugLogging = getConfig().getBoolean("debug", false);
        normalizationEnabled = getConfig().getBoolean("normalization.hover_event_uuid.enabled", true);
        convertIntArrayPayloads = getConfig().getBoolean("normalization.hover_event_uuid.convert.int_array", true);
        convertUuidObjectPayloads = getConfig().getBoolean("normalization.hover_event_uuid.convert.uuid_object", true);
        preventUnloadedChunkDig = getConfig().getBoolean("sanitization.prevent_unloaded_chunk_dig", true);

        boolean includeOriginal = getConfig().getBoolean(CONFIG_LOGGING_INCLUDE_ORIGINAL, true);
        boolean includeNormalized = getConfig().getBoolean(CONFIG_LOGGING_INCLUDE_NORMALIZED, true);
        String fileName = getConfig().getString(CONFIG_LOGGING_FILE, "handled-errors.xml");
        logFixes = getConfig().getBoolean(CONFIG_LOGGING_ENABLED, true) && (includeOriginal || includeNormalized);

        if (logFixes) {
            handledErrorLogger = new HandledErrorLogger(getLogger(), getDataFolder(), fileName, includeOriginal, includeNormalized);
            if (!handledErrorLogger.initialize()) {
                handledErrorLogger = null;
                logFixes = false;
            }
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        normalizer = new HoverEventUuidNormalizer();

        if (normalizationEnabled) {
            registerHoverEventNormalizer();
        } else {
            getLogger().info("Hover event normalization is disabled via configuration.");
        }

        if (preventUnloadedChunkDig) {
            registerBlockDigSanitizer();
        }

        printBanner();
    }

    @Override
    public void onDisable() {
        if (protocolManager != null) {
            for (PacketAdapter listener : listeners) {
                protocolManager.removePacketListener(listener);
            }
            listeners.clear();
        }
    }

    private boolean isProtocolLibPresent() {
        Plugin plugin = getServer().getPluginManager().getPlugin("ProtocolLib");
        return plugin != null && plugin.isEnabled();
    }

    private void registerHoverEventNormalizer() {
        Set<PacketType> monitoredTypes = collectServerPlayPackets();
        HoverEventUuidNormalizer.NormalizationOptions options = new HoverEventUuidNormalizer.NormalizationOptions(
                convertIntArrayPayloads,
                convertUuidObjectPayloads
        );

        PacketAdapter adapter = new PacketAdapter(this, ListenerPriority.LOWEST, monitoredTypes.toArray(PacketType[]::new)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    normalizePacket(event.getPacket(), options);
                } catch (Exception ex) {
                    getLogger().log(Level.SEVERE, "Failed to normalize packet " + event.getPacketType(), ex);
                }
            }
        };

        registerListener(adapter);
    }

    private void registerBlockDigSanitizer() {
        PacketAdapter adapter = new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Client.BLOCK_DIG) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                PlayerDigType digType = event.getPacket().getPlayerDigTypes().readSafely(0);
                StructureModifier<BlockPosition> positionModifier = event.getPacket().getBlockPositionModifier();
                BlockPosition position = positionModifier != null ? positionModifier.readSafely(0) : null;
                BlockDigSanitizer.Result result = blockDigSanitizer.evaluate(
                        digType,
                        position,
                        chunkChecker(event.getPlayer()),
                        blockPositionProvider(event.getPlayer())
                );

                if (result.shouldCancel()) {
                    event.setCancelled(true);
                    if (debugLogging && event.getPlayer() != null) {
                        getLogger().info(() -> "Cancelled dig packet from " + event.getPlayer().getName()
                                + " at " + position + " because the chunk is not loaded.");
                    }
                    return;
                }

                BlockPosition replacement = result.replacement();
                if (replacement != null && positionModifier != null) {
                    positionModifier.writeSafely(0, replacement);
                    if (debugLogging && event.getPlayer() != null) {
                        BlockPosition original = position;
                        getLogger().info(() -> "Replaced dig packet position from " + original
                                + " to " + replacement + " for " + event.getPlayer().getName());
                    }
                }
            }
        };

        registerListener(adapter);
    }

    private BlockDigSanitizer.ChunkLoadChecker chunkChecker(Player player) {
        if (player == null) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        return world::isChunkLoaded;
    }

    private BlockDigSanitizer.BlockPositionProvider blockPositionProvider(Player player) {
        if (player == null) {
            return null;
        }
        return () -> {
            Location location = player.getLocation();
            if (location == null) {
                return null;
            }
            return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        };
    }

    private void registerListener(PacketAdapter adapter) {
        listeners.add(adapter);
        protocolManager.addPacketListener(adapter);
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
            if (logFixes && handledErrorLogger != null) {
                handledErrorLogger.logNormalization(record.originalPayload(), record.normalizedUuid());
            }
            if (debugLogging) {
                getLogger().info(() -> "Normalized hoverEvent UUID " + record.originalPayload()
                        + " -> " + record.normalizedUuid());
            }
        };

        normalizeComponentModifier(packet.getChatComponents(), options, fixLogger);

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
}
