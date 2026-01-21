package com.embabel.air.ai.rag;

import com.embabel.agent.rag.ingestion.HierarchicalContentReader;
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.model.ContentRoot;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.store.ChunkingContentElementRepository;
import io.vavr.collection.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Service for managing document retrieval.
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    private final HierarchicalContentReader contentReader = new TikaHierarchicalContentReader();
    private final ChunkingContentElementRepository contentElementRepository;

    /**
     * Summary info about an ingested document.
     */
    public record DocumentInfo(String uri, String title, String context, Instant ingestedAt) {
    }

    public DocumentService(
            ChunkingContentElementRepository chunkingContentElementRepository) {
        this.contentElementRepository = chunkingContentElementRepository;
    }

    public NavigableDocument ingestUrl(String url) {
        logger.info("Ingesting URL: {}", url);
        var document = contentReader.parseResource(url);
        contentElementRepository.writeAndChunkDocument(document);
        logger.info("Ingested URL: {}", url);
        return document;
    }

    /**
     * Get list of all ingested documents.
     */
    public List<DocumentInfo> getDocuments() {
        return List.ofAll(contentElementRepository.findAll(ContentRoot.class))
                .map(doc -> new DocumentInfo(
                        doc.getUri(),
                        doc.getTitle(),
                        extractContext(doc.getMetadata()),
                        doc.getIngestionTimestamp()
                ));
    }

    private String extractContext(Map<String, ?> metadata) {
        if (metadata == null) {
            return "";
        }
        var context = metadata.get("context");
        return context != null ? context.toString() : "";
    }

    /**
     * Delete a document by its URI.
     */
    public boolean deleteDocument(String uri) {
        logger.info("Deleting document: {}", uri);
        var result = contentElementRepository.deleteRootAndDescendants(uri);
        return result != null;
    }

    /**
     * Get total document count.
     */
    public int getDocumentCount() {
        return contentElementRepository.info().getDocumentCount();
    }

    /**
     * Get total chunk count.
     */
    public int getChunkCount() {
        return contentElementRepository.info().getChunkCount();
    }

}
