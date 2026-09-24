package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.tenancy.NamespaceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The admission a registration or a replacement passes: asked only once the id is known to be
 * the caller's, and before anything is recorded or started.
 *
 * <p>Admitting a namespace can create it, and a namespace, once created, is a tenant: it counts
 * against {@code excalibur.graph.max-namespaces} and is listed to every caller. A request that
 * fails must not leave one behind, so the admission is asked only after every check the
 * registry itself makes - a duplicate id, a full registry, a replacement naming another
 * namespace or no registered source - and a failed admission undoes the claim it followed.</p>
 */
@DisplayName("Admitting a registration's namespace")
class InMemorySourceRegistryAdmissionTest {

    private static final String ID = "docs";
    private static final NamespaceId ACME = NamespaceId.of("acme");

    @TempDir
    Path dataDir;

    private InMemorySourceRegistry registry;
    private SourceCatalog catalog;
    private IngestionOrchestrator orchestrator;
    private final List<SourceDescriptor> admitted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new InMemorySourceRegistry(2);
        catalog = SourceCatalog.open(dataDir);
        orchestrator = mock(IngestionOrchestrator.class);
        registry.setSourceCatalog(catalog);
        registry.setIngestionOrchestrator(orchestrator);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private static SourceDescriptor descriptor(String id, NamespaceId namespace) {
        return new SourceDescriptor("filesystem", id, Map.of("root_path", "/a"), namespace);
    }

    @Nested
    @DisplayName("registering")
    class Registering {

        @Test
        @DisplayName("a registration that goes through is admitted once, before it is recorded")
        void admittedBeforeRecording() throws IOException {
            registry.register(new FakeSource(ID), descriptor(ID, ACME), descriptor -> {
                assertThat(catalog.all()).as("nothing recorded yet").isEmpty();
                assertThat(registry.isRegistered(ID)).as("the id is already claimed").isTrue();
                admitted.add(descriptor);
            });

            assertThat(admitted).extracting(SourceDescriptor::namespace).containsExactly(ACME);
            assertThat(registry.namespaceOf(ID)).contains(ACME);
            verify(orchestrator).startIngestion(any());
        }

        @Test
        @DisplayName("a duplicate id is refused without being admitted")
        void aDuplicateIsNotAdmitted() throws IOException {
            registry.register(new FakeSource(ID), descriptor(ID, NamespaceId.SHARED));

            assertThatThrownBy(() -> registry.register(new FakeSource(ID), descriptor(ID, ACME),
                    admitted::add))
                .isInstanceOf(SourceRegistrationException.class)
                .extracting(e -> ((SourceRegistrationException) e).reason())
                .isEqualTo(SourceRegistrationException.Reason.DUPLICATE);
            assertThat(admitted).isEmpty();
        }

        @Test
        @DisplayName("a full registry is refused without being admitted")
        void aFullRegistryIsNotAdmitted() {
            registry.register(new FakeSource("one"), descriptor("one", NamespaceId.SHARED));
            registry.register(new FakeSource("two"), descriptor("two", NamespaceId.SHARED));

            assertThatThrownBy(() -> registry.register(new FakeSource(ID), descriptor(ID, ACME),
                    admitted::add))
                .isInstanceOf(SourceRegistrationException.class);
            assertThat(admitted).isEmpty();
        }

        @Test
        @DisplayName("a refused admission gives the id back and records and starts nothing")
        void aRefusalUndoesTheClaim() throws IOException {
            assertThatThrownBy(() -> registry.register(new FakeSource(ID), descriptor(ID, ACME),
                    descriptor -> {
                        throw new NamespaceRefusedException(descriptor.namespace(),
                            NamespaceRefusedException.Reason.LIMIT_REACHED, "full");
                    }))
                .isInstanceOf(NamespaceRefusedException.class);

            assertThat(registry.isRegistered(ID)).isFalse();
            assertThat(registry.namespaceOf(ID)).isEmpty();
            assertThat(catalog.all()).isEmpty();
            verify(orchestrator, never()).startIngestion(any());

            registry.register(new FakeSource(ID), descriptor(ID, ACME), admitted::add);
            assertThat(registry.isRegistered(ID)).isTrue();
        }

        @Test
        @DisplayName("an admission that cannot create the namespace gives the id back")
        void aFailedAdmissionUndoesTheClaim() {
            assertThatThrownBy(() -> registry.register(new FakeSource(ID), descriptor(ID, ACME),
                    descriptor -> {
                        throw new IOException("disk full");
                    }))
                .isInstanceOf(IOException.class);

            assertThat(registry.isRegistered(ID)).isFalse();
            assertThat(catalog.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("replacing")
    class Replacing {

        @Test
        @DisplayName("an id nothing is registered under is refused without being admitted")
        void unknownIsNotAdmitted() {
            assertThatThrownBy(() -> registry.replace(new FakeSource(ID), descriptor(ID, ACME),
                    admitted::add))
                .isInstanceOf(SourceRegistrationException.class)
                .extracting(e -> ((SourceRegistrationException) e).reason())
                .isEqualTo(SourceRegistrationException.Reason.UNKNOWN);
            assertThat(admitted).isEmpty();
        }

        @Test
        @DisplayName("another namespace is refused without being admitted")
        void anotherNamespaceIsNotAdmitted() {
            registry.register(new FakeSource(ID), descriptor(ID, NamespaceId.SHARED));

            assertThatThrownBy(() -> registry.replace(new FakeSource(ID), descriptor(ID, ACME),
                    admitted::add))
                .isInstanceOf(SourceRegistrationException.class)
                .extracting(e -> ((SourceRegistrationException) e).reason())
                .isEqualTo(SourceRegistrationException.Reason.NAMESPACE_MISMATCH);
            assertThat(admitted).isEmpty();
        }

        @Test
        @DisplayName("a refused admission leaves the registration as it was")
        void aRefusalChangesNothing() throws IOException {
            FakeSource original = new FakeSource(ID);
            registry.register(original, descriptor(ID, ACME));
            FakeSource replacement = new FakeSource(ID);

            assertThatThrownBy(() -> registry.replace(replacement,
                    new SourceDescriptor("filesystem", ID, Map.of("root_path", "/b"), ACME),
                    descriptor -> {
                        throw new NamespaceRefusedException(descriptor.namespace(),
                            NamespaceRefusedException.Reason.NOT_CREATED, "dropped");
                    }))
                .isInstanceOf(NamespaceRefusedException.class);

            assertThat(registry.get(ID)).isSameAs(original);
            assertThat(catalog.all()).singleElement()
                .satisfies(d -> assertThat(d.config()).containsEntry("root_path", "/a"));
        }

        @Test
        @DisplayName("a replacement that goes through is admitted before it is recorded")
        void admittedBeforeRecording() throws IOException {
            registry.register(new FakeSource(ID), descriptor(ID, ACME));

            registry.replace(new FakeSource(ID),
                new SourceDescriptor("filesystem", ID, Map.of("root_path", "/b"), ACME),
                descriptor -> {
                    assertThat(catalog.all()).singleElement()
                        .satisfies(d -> assertThat(d.config()).containsEntry("root_path", "/a"));
                    admitted.add(descriptor);
                });

            assertThat(admitted).extracting(SourceDescriptor::namespace).containsExactly(ACME);
        }
    }

    /** A source that crawls nothing. */
    private static final class FakeSource implements Source {

        private final String id;

        FakeSource(String id) {
            this.id = id;
        }

        @Override
        public String getSourceId() {
            return id;
        }

        @Override
        public String getSourceType() {
            return "filesystem";
        }

        @Override
        public Stream<String> crawl() {
            return Stream.empty();
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            return null;
        }
    }
}
