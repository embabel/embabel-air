package com.embabel.air.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Loads default documents on startup if not already present.
 */
@Component
class DocumentLoader implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DocumentLoader.class);

    private final DocumentService documentService;

    DocumentLoader(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public void run(String... args) {
        if (documentService.getDocumentCount() > 0) {
            logger.info("Documents already loaded, skipping default document ingestion");
            return;
        }

        logger.info("Loading default documents...");
        documentService.ingestUrl("documents/rebooking_policy.md");
        logger.info("Default documents loaded");
    }
}
