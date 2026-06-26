package com.chiho.wuagentscope.service;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch document loading service.
 * Loads Markdown documents from classpath:document directory,
 * chunks them using AgentScope TextReader, and adds filename metadata.
 *
 * @author Chiho
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentLoaderService {

    private final ResourcePatternResolver resourcePatternResolver;

    /**
     * Load all Markdown documents from classpath:document directory.
     * Uses PARAGRAPH strategy with chunkSize=2000 (documents are short, keep as single chunk).
     * Adds filename to each chunk's payload for deduplication and deletion.
     *
     * @return document list, empty list if loading fails
     */
    public List<Document> loadMarkdownDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        // chunkSize=512 匹配 mxbai-embed-large 的上下文窗口（512 tokens），
        // 中文约 1 字 ≈ 1-2 token，取 400 字符为安全上限
        TextReader reader = new TextReader(400, SplitStrategy.PARAGRAPH, 0);

        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/**/*.md");
            log.info("RAG: found {} document files", resources.length);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                try {
                    ReaderInput input = ReaderInput.fromFile(resource.getFile().toPath());
                    List<Document> docs = reader.read(input).block();

                    if (docs != null && !docs.isEmpty()) {
                        for (Document doc : docs) {
                            // DocumentMetadata.payload is unmodifiable, create new metadata with filename
                            DocumentMetadata oldMeta = doc.getMetadata();
                            DocumentMetadata newMeta = DocumentMetadata.builder()
                                    .content(oldMeta.getContent())
                                    .docId(oldMeta.getDocId())
                                    .chunkId(oldMeta.getChunkId())
                                    .addPayload("filename", filename)
                                    .build();
                            allDocuments.add(new Document(newMeta));
                        }
                        log.debug("RAG: loaded document [{}] -> {} chunks", filename, docs.size());
                    }
                } catch (Exception e) {
                    log.warn("RAG: failed to load document [{}]: {}", filename, e.getMessage());
                    log.info("EXCEPTION: ", e);
                }
            }
        } catch (Exception e) {
            log.error("RAG: failed to scan document directory: {}", e.getMessage(), e);
        }

        int docCount = allDocuments.isEmpty() ? 0 : (int) allDocuments.stream()
                .map(d -> d.getPayloadValue("filename"))
                .distinct().count();
        log.info("RAG: loaded {} documents, {} chunks total", docCount, allDocuments.size());
        return allDocuments;
    }
}
