package com.ssilensio.itemsadderfix.logging;

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
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists normalized hover event conversions into handled-errors.xml so users
 * can inspect which payloads were fixed. The logger is deliberately strict
 * about the data it accepts and will quietly skip malformed requests to avoid
 * polluting the log with unrelated entries.
 */
public final class HandledErrorLogger {
    private final Logger logger;
    private final File dataFolder;
    private final String fileName;
    private final boolean includeOriginal;
    private final boolean includeNormalized;
    private final Object lock = new Object();
    private File handledErrorsFile;
    private boolean initialized;

    public HandledErrorLogger(Logger logger,
                              File dataFolder,
                              String fileName,
                              boolean includeOriginal,
                              boolean includeNormalized) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataFolder = dataFolder;
        this.fileName = (fileName == null || fileName.isBlank()) ? "handled-errors.xml" : fileName;
        this.includeOriginal = includeOriginal;
        this.includeNormalized = includeNormalized;
    }

    public boolean initialize() {
        if (initialized) {
            return handledErrorsFile != null;
        }

        if (!includeOriginal && !includeNormalized) {
            return false;
        }

        if (dataFolder == null) {
            return false;
        }

        synchronized (lock) {
            if (initialized) {
                return handledErrorsFile != null;
            }

            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("Unable to create plugin data folder; handled error logging disabled.");
                return false;
            }

            handledErrorsFile = new File(dataFolder, fileName);

            try {
                DocumentBuilder builder = newDocumentBuilder();
                Document document;
                if (!handledErrorsFile.exists() || handledErrorsFile.length() == 0) {
                    document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                } else {
                    try (FileInputStream inputStream = new FileInputStream(handledErrorsFile)) {
                        document = builder.parse(inputStream);
                    }
                    if (document.getDocumentElement() == null) {
                        document.appendChild(document.createElement("handledErrors"));
                        writeDocument(document);
                    }
                }
                initialized = true;
                return true;
            } catch (ParserConfigurationException | IOException | TransformerException ex) {
                logger.log(Level.WARNING, "Unable to initialize " + fileName, ex);
                handledErrorsFile = null;
                return false;
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed to verify " + fileName + "; recreating file.", ex);
                try {
                    DocumentBuilder builder = newDocumentBuilder();
                    Document document = builder.newDocument();
                    document.appendChild(document.createElement("handledErrors"));
                    writeDocument(document);
                    initialized = true;
                    return true;
                } catch (ParserConfigurationException | TransformerException | IOException recreateEx) {
                    logger.log(Level.WARNING, "Unable to recreate " + fileName, recreateEx);
                    handledErrorsFile = null;
                    return false;
                }
            }
        }
    }

    public boolean logNormalization(String original, String normalized) {
        if (!initialized || handledErrorsFile == null) {
            return false;
        }
        if (!includeOriginal && !includeNormalized) {
            return false;
        }
        if (includeOriginal && (original == null || original.isBlank())) {
            return false;
        }
        if (includeNormalized && (normalized == null || normalized.isBlank())) {
            return false;
        }

        synchronized (lock) {
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

                if (includeOriginal) {
                    Element originalElement = document.createElement("original");
                    originalElement.appendChild(document.createCDATASection(original));
                    entry.appendChild(originalElement);
                }

                if (includeNormalized) {
                    Element normalizedElement = document.createElement("normalized");
                    normalizedElement.appendChild(document.createCDATASection(normalized));
                    entry.appendChild(normalizedElement);
                }

                root.appendChild(entry);

                writeDocument(document);
                return true;
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Unable to write handled error entry to " + fileName, ex);
                return false;
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
            // Fall back to defaults when a feature is unavailable.
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
