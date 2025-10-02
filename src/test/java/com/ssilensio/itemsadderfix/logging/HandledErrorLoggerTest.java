package com.ssilensio.itemsadderfix.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandledErrorLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsLogFileAndAppendsHandledErrorEntries() throws Exception {
        File dataFolder = tempDir.toFile();
        Logger logger = Logger.getLogger("HandledErrorLoggerTest");
        HandledErrorLogger handledErrorLogger = new HandledErrorLogger(logger, dataFolder);

        assertTrue(handledErrorLogger.initialize());

        File logFile = dataFolder.toPath().resolve("handled-errors.xml").toFile();
        assertTrue(logFile.exists(), "handled-errors.xml should be created during initialization");

        assertTrue(handledErrorLogger.logNormalization("[0,1,2,3]", "c0ffee-cafe-babe-face-feeddeadbeef"));

        Document document = parseDocument(logFile);
        NodeList handledErrors = document.getDocumentElement().getElementsByTagName("handledError");
        assertEquals(1, handledErrors.getLength(), "Exactly one handled error entry should be recorded");

        Element entry = (Element) handledErrors.item(0);
        assertTrue(entry.hasAttribute("timestamp"));
        assertEquals("[0,1,2,3]", entry.getElementsByTagName("original").item(0).getTextContent());
        assertEquals("c0ffee-cafe-babe-face-feeddeadbeef", entry.getElementsByTagName("normalized").item(0).getTextContent());
    }

    @Test
    void skipsWritingWhenOriginalOrNormalizedMissing() throws Exception {
        File dataFolder = tempDir.resolve("skip").toFile();
        Logger logger = Logger.getLogger("HandledErrorLoggerSkipTest");
        HandledErrorLogger handledErrorLogger = new HandledErrorLogger(logger, dataFolder);

        assertTrue(handledErrorLogger.initialize());
        File logFile = dataFolder.toPath().resolve("handled-errors.xml").toFile();

        assertFalse(handledErrorLogger.logNormalization(null, "value"));
        assertFalse(handledErrorLogger.logNormalization("", "value"));
        assertFalse(handledErrorLogger.logNormalization("value", null));
        assertFalse(handledErrorLogger.logNormalization("value", "   "));

        Document document = parseDocument(logFile);
        NodeList handledErrors = document.getDocumentElement().getElementsByTagName("handledError");
        assertEquals(0, handledErrors.getLength(), "No handled error entries should be recorded when data is invalid");
    }

    private Document parseDocument(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        disableExternalEntities(factory);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (FileInputStream stream = new FileInputStream(file)) {
            return builder.parse(stream);
        }
    }

    private void disableExternalEntities(DocumentBuilderFactory factory) throws ParserConfigurationException {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException ignored) {
        }
    }
}
