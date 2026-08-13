package com.yuyu.salmonmind.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.yuyu.salmonmind.knowledge.KnowledgeBase;
import com.yuyu.salmonmind.knowledge.KnowledgeBase.AddRevision;
import com.yuyu.salmonmind.knowledge.KnowledgeBase.CreateSource;
import com.yuyu.salmonmind.knowledge.KnowledgeBase.KnowledgeQuery;
import com.yuyu.salmonmind.knowledge.KnowledgeBase.RevisionFormat;
import com.yuyu.salmonmind.knowledge.KnowledgeBase.SourceKind;
import com.yuyu.salmonmind.knowledge.KnowledgeException;
import com.yuyu.salmonmind.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "salmon.knowledge.chunk-max-chars=40",
                "salmon.knowledge.embedding-batch-size=2"
        }
)
@Import(KnowledgeModuleIntegrationTest.TestAdapters.class)
class KnowledgeModuleIntegrationTest {

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private InMemoryContentStore contentStore;

    @Autowired
    private InMemorySearchIndex searchIndex;

    @Test
    void storesTextSourcesAndRebuildsTheDisposableIndex() {
        var project = knowledgeBase.createSource(new CreateSource("Fish Project", SourceKind.PROJECT));
        var notes = knowledgeBase.createSource(new CreateSource("Developer Notes", SourceKind.NOTE));

        knowledgeBase.addRevision(revision(project.id(), "project.md", RevisionFormat.MARKDOWN,
                "old project text"));
        var latestProject = knowledgeBase.addRevision(revision(
                project.id(), "project.md", RevisionFormat.MARKDOWN,
                "alpha project evidence\nimplementation details"
        ));
        knowledgeBase.addRevision(revision(
                notes.id(), "notes.txt", RevisionFormat.TEXT,
                "beta personal document evidence"
        ));

        assertThat(knowledgeBase.sources()).hasSize(2);
        assertThat(knowledgeBase.revisions(project.id()))
                .extracting(KnowledgeBase.SourceRevision::number)
                .containsExactly(1, 2);
        assertThat(contentStore.objects()).hasSize(3);

        var first = knowledgeBase.rebuild();
        assertThat(first.revisionCount()).isEqualTo(2);
        assertThat(first.evidenceCount()).isPositive();

        var evidence = knowledgeBase.retrieve(new KnowledgeQuery("alpha", 3));
        assertThat(evidence.evidence()).isNotEmpty();
        assertThat(evidence.evidence().getFirst().sourceRevisionId()).isEqualTo(latestProject.id());
        assertThat(evidence.evidence().getFirst().location()).startsWith("project.md:C");

        searchIndex.deleteAll();
        assertThatThrownBy(() -> knowledgeBase.retrieve(new KnowledgeQuery("alpha")))
                .isInstanceOfSatisfying(KnowledgeException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(KnowledgeException.Kind.INDEX_UNAVAILABLE));

        var rebuilt = knowledgeBase.rebuild();
        assertThat(rebuilt.id()).isNotEqualTo(first.id());
        assertThat(knowledgeBase.retrieve(new KnowledgeQuery("beta")).evidence()).isNotEmpty();

        assertThatThrownBy(() -> knowledgeBase.addRevision(new AddRevision(
                notes.id(), "bad.txt", RevisionFormat.TEXT, new byte[]{(byte) 0xc3, 0x28}
        ))).isInstanceOfSatisfying(KnowledgeException.class, exception ->
                assertThat(exception.kind()).isEqualTo(KnowledgeException.Kind.REVISION_REJECTED));
    }

    private static AddRevision revision(
            java.util.UUID sourceId,
            String name,
            RevisionFormat format,
            String text) {
        return new AddRevision(sourceId, name, format, text.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAdapters {

        @Bean
        @Primary
        InMemoryContentStore inMemoryContentStore() {
            return new InMemoryContentStore();
        }

        @Bean
        @Primary
        InMemorySearchIndex inMemorySearchIndex() {
            return new InMemorySearchIndex();
        }

        @Bean
        @Primary
        ModelGateway knowledgeTestModel() {
            return new TestModel();
        }
    }

    static final class InMemoryContentStore implements ContentStore {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void put(String objectKey, byte[] content, String mediaType) {
            objects.put(objectKey, content.clone());
        }

        @Override
        public byte[] get(String objectKey) {
            byte[] content = objects.get(objectKey);
            if (content == null) {
                throw new KnowledgeInfrastructureException(
                        KnowledgeInfrastructureException.Code.CONTENT_STORE
                );
            }
            return content.clone();
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }

        Map<String, byte[]> objects() {
            return Map.copyOf(objects);
        }
    }

    static final class InMemorySearchIndex implements SearchIndex {

        private final Map<String, List<IndexDocument>> indices = new ConcurrentHashMap<>();

        @Override
        public void create(IndexSpec spec) {
            indices.put(spec.name(), new ArrayList<>());
        }

        @Override
        public void index(String name, List<IndexDocument> documents) {
            List<IndexDocument> index = indices.get(name);
            if (index == null) {
                throw unavailable();
            }
            index.addAll(documents);
        }

        @Override
        public void refresh(String name) {
            if (!indices.containsKey(name)) {
                throw unavailable();
            }
        }

        @Override
        public List<SearchHit> search(String name, List<Double> queryVector, int limit) {
            List<IndexDocument> documents = indices.get(name);
            if (documents == null) {
                throw unavailable();
            }
            return documents.stream()
                    .sorted(Comparator.comparingDouble(
                            (IndexDocument document) -> dot(document.embedding(), queryVector)
                    ).reversed())
                    .limit(limit)
                    .map(document -> new SearchHit(
                            document.evidenceId(),
                            document.text(),
                            document.contentSha256(),
                            dot(document.embedding(), queryVector)
                    ))
                    .toList();
        }

        void deleteAll() {
            indices.clear();
        }

        private static double dot(List<Double> left, List<Double> right) {
            double score = 0;
            for (int index = 0; index < left.size(); index++) {
                score += left.get(index) * right.get(index);
            }
            return score;
        }

        private static KnowledgeInfrastructureException unavailable() {
            return new KnowledgeInfrastructureException(
                    KnowledgeInfrastructureException.Code.SEARCH_INDEX
            );
        }
    }

    private static final class TestModel implements ModelGateway {

        @Override
        public Completion complete(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingBatch embed(EmbeddingInput input) {
            return new EmbeddingBatch(
                    "test-embedding",
                    input.texts().stream().map(TestModel::embedding).toList()
            );
        }

        private static Embedding embedding(String text) {
            String normalized = text.toLowerCase(java.util.Locale.ROOT);
            return new Embedding(List.of(
                    normalized.contains("alpha") ? 1.0 : 0.0,
                    normalized.contains("beta") ? 1.0 : 0.0,
                    0.1
            ));
        }
    }
}
