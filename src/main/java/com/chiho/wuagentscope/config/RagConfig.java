package com.chiho.wuagentscope.config;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.PgVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.chiho.wuagentscope.service.DocumentLoaderService;

/**
 * RAG 配置
 * <p>
 * 基于 AgentScope 官方 agentscope-extensions-rag-simple 模块，
 * 使用 PgVectorStore 实现向量持久化存储。
 * <p>
 * 启动时自动加载 classpath:document/ 下的文档，SHA-256 去重避免重复嵌入。
 *
 * @author Chiho
 */
@Configuration
@Slf4j
public class RagConfig {

    /**
     * 配置 Ollama Embedding 模型
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${rag.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${rag.embedding.model:mxbai-embed-large}") String modelName,
            @Value("${rag.embedding.dimensions:1024}") int dimensions) {

        log.info("初始化 Embedding 模型: baseUrl={}, model={}, dimensions={}", baseUrl, modelName, dimensions);

        return OllamaTextEmbedding.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .dimensions(dimensions)
                .build();
    }

    /**
     * 配置 PgVector 向量存储
     * <p>
     * PgVectorStore 管理自己的 JDBC 连接（不走 Spring DataSource），
     * 实现 AutoCloseable，通过 destroyMethod="close" 确保连接释放。
     */
    @Bean(destroyMethod = "close")
    public PgVectorStore vectorStore(
            @Value("${rag.vector-store.jdbc-url}") String jdbcUrl,
            @Value("${rag.vector-store.username}") String username,
            @Value("${rag.vector-store.password}") String password,
            @Value("${rag.vector-store.schema:public}") String schema,
            @Value("${rag.vector-store.table-name:vector_store}") String tableName,
            @Value("${rag.embedding.dimensions:1024}") int dimensions) {

        log.info("初始化 PgVector 向量存储: jdbcUrl={}, schema={}, table={}, dimensions={}",
                jdbcUrl, schema, tableName, dimensions);

        try {
            return PgVectorStore.builder()
                    .jdbcUrl(jdbcUrl)
                    .username(username)
                    .password(password)
                    .schema(schema)
                    .tableName(tableName)
                    .dimensions(dimensions)
                    .distanceType(PgVectorStore.DistanceType.COSINE)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize PgVectorStore", e);
        }
    }

    /**
     * 配置 Knowledge 实例
     */
    @Bean
    public SimpleKnowledge knowledge(EmbeddingModel embeddingModel, PgVectorStore vectorStore) {
        log.info("初始化 SimpleKnowledge (PgVectorStore)");
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(vectorStore)
                .build();
    }

    /**
     * 配置 Knowledge 检索工具（AGENTIC 模式，通用 Agent 可选调用）
     */
    @Bean
    public KnowledgeRetrievalTools knowledgeRetrievalTools(SimpleKnowledge knowledge) {
        RetrieveConfig config = RetrieveConfig.builder()
                .limit(5)
                .scoreThreshold(0.5)
                .build();

        log.info("初始化 KnowledgeRetrievalTools: limit={}, scoreThreshold={}",
                config.getLimit(), config.getScoreThreshold());

        return new KnowledgeRetrievalTools(knowledge, config);
    }

    /**
     * 配置 GenericRAGHook（GENERIC 模式，自动检索注入上下文）
     * <p>
     * 用于 car_advisor 等需要 100% 检索的专业 Agent，
     * 在每次 LLM 推理前自动检索知识库并注入结果。
     */
    @Bean
    public GenericRAGHook genericRAGHook(SimpleKnowledge knowledge) {
        RetrieveConfig config = RetrieveConfig.builder()
                .limit(2)
                .scoreThreshold(0.5)
                .build();

        log.info("初始化 GenericRAGHook: limit={}, scoreThreshold={}",
                config.getLimit(), config.getScoreThreshold());

        return new GenericRAGHook(knowledge, config);
    }

    /**
     * 启动时自动加载文档到向量库
     * <p>
     * 使用 SHA-256 对文档内容哈希，查询 pgvector 表的 payload JSONB 字段判断是否已存在，
     * 避免重启时重复嵌入（嵌入调用耗时较长）。
     */
    @Bean
    public ApplicationRunner ragDocumentLoader(
            DocumentLoaderService documentLoaderService,
            SimpleKnowledge knowledge,
            PgVectorStore vectorStore) {

        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                log.info("RAG: 开始加载文档...");
                long startTime = System.currentTimeMillis();

                List<Document> docs = documentLoaderService.loadMarkdownDocuments();
                if (docs.isEmpty()) {
                    log.info("RAG: 无文档需要加载");
                    return;
                }

                // SHA-256 去重
                List<Document> newDocs = new ArrayList<>();
                for (Document doc : docs) {
                    try {
                        String hash = sha256Hex(doc.getMetadata().getContentText());
                        // payload is unmodifiable, create new metadata with content_hash
                        DocumentMetadata oldMeta = doc.getMetadata();
                        DocumentMetadata newMeta = DocumentMetadata.builder()
                                .content(oldMeta.getContent())
                                .docId(oldMeta.getDocId())
                                .chunkId(oldMeta.getChunkId())
                                .payload(oldMeta.getPayload())  // copy existing payload (contains filename)
                                .addPayload("content_hash", hash)
                                .build();
                        Document enrichedDoc = new Document(newMeta);
                        if (!existsByContentHash(vectorStore, hash)) {
                            newDocs.add(enrichedDoc);
                        }
                    } catch (NoSuchAlgorithmException e) {
                        newDocs.add(doc);
                    }
                }

                if (newDocs.isEmpty()) {
                    log.info("RAG: 所有文档已存在，跳过加载（{} 个 chunk 已在向量库中）", docs.size());
                    return;
                }

                // 嵌入并存储
                knowledge.addDocuments(newDocs).block();

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("RAG: 已加载 {} 个新 chunk（跳过 {} 个已存在），耗时 {} 秒",
                        newDocs.size(), docs.size() - newDocs.size(), elapsed / 1000);
            }

            private boolean existsByContentHash(PgVectorStore store, String hash) {
                String sql = "SELECT EXISTS (SELECT 1 FROM agent_store.vector_store"
                        + " WHERE payload::jsonb ->> 'content_hash' = ?)";
                try (PreparedStatement ps = store.getConnection().prepareStatement(sql)) {
                    ps.setString(1, hash);
                    ResultSet rs = ps.executeQuery();
                    return rs.next() && rs.getBoolean(1);
                } catch (Exception e) {
                    log.warn("RAG: 检查文档哈希失败，将重新加载: {}", e.getMessage());
                    return false;
                }
            }

            private String sha256Hex(String value) throws NoSuchAlgorithmException {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            }
        };
    }
}
