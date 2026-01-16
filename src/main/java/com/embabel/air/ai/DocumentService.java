package com.embabel.air.ai;

import com.embabel.agent.rag.ingestion.HierarchicalContentReader;
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.store.ChunkingContentElementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for managing document retrieval.
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    private final ChunkingContentElementRepository contentRepository;
    private final List<DocumentInfo> documents = new CopyOnWriteArrayList<>();
    private final HierarchicalContentReader contentReader = new TikaHierarchicalContentReader();


    /**
     * Summary info about an ingested document.
     */
    public record DocumentInfo(String uri, String title, String context, Instant ingestedAt) {
    }

    public DocumentService(ChunkingContentElementRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    public NavigableDocument ingestUrl(String url) {
        logger.info("Ingesting URL: {}", url);
        var document = contentReader.parseResource(url);
        contentRepository.writeAndChunkDocument(document);
        logger.info("Ingested URL: {}", url);
        return document;
    }

    /**
     * Get list of all ingested documents.
     */
    public List<DocumentInfo> getDocuments() {
        return List.copyOf(documents);
    }

    /**
     * Delete a document by its URI.
     */
    public boolean deleteDocument(String uri) {
        logger.info("Deleting document: {}", uri);
        var result = contentRepository.deleteRootAndDescendants(uri);
        if (result != null) {
            documents.removeIf(doc -> doc.uri().equals(uri));
            return true;
        }
        return false;
    }

    /**
     * Get total document count.
     */
    public int getDocumentCount() {
        return contentRepository.info().getDocumentCount();
    }

    /**
     * Get total chunk count.
     */
    public int getChunkCount() {
        return contentRepository.info().getChunkCount();
    }

}
