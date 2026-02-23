package com.embabel.air.ai.rag;

import com.embabel.agent.rag.ingestion.HierarchicalContentReader;
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.model.ContentRoot;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.store.ChunkingContentElementRepository;
import com.embabel.vaadin.document.DocumentInfoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Service for managing document retrieval.
 */
@Service
public class DocumentService implements DocumentInfoProvider {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    private final HierarchicalContentReader contentReader = new TikaHierarchicalContentReader();
    private final ChunkingContentElementRepository contentElementRepository;

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

    @Override
    public List<DocumentInfoProvider.DocumentInfo> getDocuments() {
        return StreamSupport.stream(contentElementRepository.findAll(ContentRoot.class).spliterator(), false)
                .map(doc -> new DocumentInfoProvider.DocumentInfo(
                        doc.getUri(),
                        doc.getTitle(),
                        extractContext(doc.getMetadata()),
                        0,
                        doc.getIngestionTimestamp()
                ))
                .toList();
    }

    private String extractContext(Map<String, ?> metadata) {
        if (metadata == null) {
            return "";
        }
        var context = metadata.get("context");
        return context != null ? context.toString() : "";
    }

    @Override
    public boolean deleteDocument(String uri) {
        logger.info("Deleting document: {}", uri);
        var result = contentElementRepository.deleteRootAndDescendants(uri);
        return result != null;
    }

    @Override
    public int getDocumentCount() {
        return contentElementRepository.info().getDocumentCount();
    }

    @Override
    public int getChunkCount() {
        return contentElementRepository.info().getChunkCount();
    }

}
