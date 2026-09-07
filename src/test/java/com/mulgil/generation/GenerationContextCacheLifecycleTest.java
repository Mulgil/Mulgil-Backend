package com.mulgil.generation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class GenerationContextCacheLifecycleTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void bypassesRepositoryAndProvider_whenFeatureIsOff() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        GenerationContextCacheLifecycle lifecycle = lifecycle(false, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

        GenerationContextCacheLifecycle.Prepared prepared = lifecycle.prepare(request(OWNER,
                "a".repeat(64), "source-grounded-v2"), "gemini", "us-central1");

        assertThat(prepared.status()).isEqualTo("disabled");
        assertThat(prepared.reference()).isEmpty();
        assertThat(repository.reserveCalls).isZero();
        assertThat(provider.totalCalls()).isZero();
    }

    @Test
    void reusesOneLiveEntry_whenEntireCacheKeyMatches() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        GenerationModelPort.GenerationRequest request = request(OWNER,
                "a".repeat(64), "source-grounded-v2");

        GenerationContextCacheLifecycle.Prepared first = lifecycle.prepare(request, "gemini", "us-central1");
        GenerationContextCacheLifecycle.Prepared second = lifecycle.prepare(request, "gemini", "us-central1");

        assertThat(first.status()).isEqualTo("miss");
        assertThat(second.status()).isEqualTo("hit");
        assertThat(first.reference()).contains(second.reference().orElseThrow());
        assertThat(provider.creates).hasValue(1);
        assertThat(repository.entries).hasSize(1);
    }

    @Test
    void startsFreshEntry_whenProductionContractReplacesV2() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        lifecycle.prepare(request(OWNER, "a".repeat(64), "source-grounded-v2"),
                "gemini", "us-central1");

        GenerationContextCacheLifecycle.Prepared current = lifecycle.prepare(request(OWNER,
                "a".repeat(64), GenerationScheduler.PROMPT_VERSION), "gemini", "us-central1");

        assertThat(current.status()).isEqualTo("miss");
        assertThat(provider.creates).hasValue(2);
        assertThat(repository.entries).hasSize(2);
    }

    @Test
    void misses_whenAnyOwnerSnapshotModelLocationOrContractPartDiffers() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        lifecycle.prepare(request(OWNER, "a".repeat(64), "source-grounded-v2"),
                "gemini", "us-central1");

        List<GenerationContextCacheLifecycle.Prepared> mismatches = List.of(
                lifecycle.prepare(request(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        "a".repeat(64), "source-grounded-v2"), "gemini", "us-central1"),
                lifecycle.prepare(request(OWNER, "b".repeat(64), "source-grounded-v2"),
                        "gemini", "us-central1"),
                lifecycle.prepare(request(OWNER, "a".repeat(64), "source-grounded-v2"),
                        "gemini-pro", "us-central1"),
                lifecycle.prepare(request(OWNER, "a".repeat(64), "source-grounded-v2"),
                        "gemini", "europe-west4"),
                lifecycle.prepare(request(OWNER, "a".repeat(64), "source-grounded-v3"),
                        "gemini", "us-central1"));

        assertThat(mismatches).extracting(GenerationContextCacheLifecycle.Prepared::status)
                .containsOnly("miss");
        assertThat(provider.creates).hasValue(6);
        assertThat(repository.entries).hasSize(6);
    }

    @Test
    void replacesExpiredEntry_evenWhenProviderCleanupFails() {
        MutableClock clock = new MutableClock(NOW);
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider, clock,
                new SimpleMeterRegistry());
        GenerationModelPort.GenerationRequest request = request(OWNER,
                "a".repeat(64), "source-grounded-v2");
        lifecycle.prepare(request, "gemini", "us-central1");
        clock.instant = NOW.plusSeconds(3601);
        provider.cleanupFails = true;

        GenerationContextCacheLifecycle.Prepared replacement = lifecycle.prepare(
                request, "gemini", "us-central1");

        assertThat(replacement.status()).isEqualTo("miss");
        assertThat(replacement.reference()).isPresent();
        assertThat(provider.creates).hasValue(2);
        assertThat(provider.deletes).hasValue(1);
        assertThat(repository.entries).hasSize(1);
    }

    @Test
    void recreatesMetadata_whenActiveProviderReferenceIsMissing() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        GenerationModelPort.GenerationRequest request = request(OWNER,
                "a".repeat(64), "source-grounded-v2");
        lifecycle.prepare(request, "gemini", "us-central1");
        provider.references.clear();

        GenerationContextCacheLifecycle.Prepared recreated = lifecycle.prepare(
                request, "gemini", "us-central1");

        assertThat(recreated.status()).isEqualTo("miss");
        assertThat(recreated.reference()).isPresent();
        assertThat(provider.creates).hasValue(2);
        assertThat(repository.entries).hasSize(1);
    }

    @Test
    void fallsBackWithoutReferenceAndRecordsError_whenProviderCreateFails() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        provider.createFails = true;
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), metrics);

        GenerationContextCacheLifecycle.Prepared prepared = lifecycle.prepare(request(OWNER,
                "a".repeat(64), "source-grounded-v2"), "gemini", "us-central1");

        assertThat(prepared.status()).isEqualTo("error");
        assertThat(prepared.reference()).isEmpty();
        assertThat(repository.entries.values()).extracting(GenerationContextCacheRepository.Entry::status)
                .containsExactly("failed");
        assertThat(metrics.get("mulgil.generation.context.cache.errors")
                .tag("operation", "create").counter().count()).isOne();
    }

    @Test
    void preventsDuplicateCreate_whenReservationIsAlreadyCreating() throws Exception {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        provider.blockCreate = true;
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        GenerationModelPort.GenerationRequest request = request(OWNER,
                "a".repeat(64), "source-grounded-v2");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> lifecycle.prepare(request, "gemini", "us-central1"));
            assertThat(provider.createEntered.await(5, TimeUnit.SECONDS)).isTrue();

            GenerationContextCacheLifecycle.Prepared concurrent = lifecycle.prepare(
                    request, "gemini", "us-central1");
            provider.createRelease.countDown();

            assertThat(concurrent.status()).isEqualTo("miss");
            assertThat(concurrent.reference()).isEmpty();
            assertThat(first.get(5, TimeUnit.SECONDS).reference()).isPresent();
            assertThat(provider.creates).hasValue(1);
        }
    }

    @Test
    void staleCreatorCannotActivateFailOrInvalidateReclaimedGeneration() {
        FakeRepository repository = new FakeRepository();
        GenerationContextCacheRepository.Key key = new GenerationContextCacheRepository.Key(
                OWNER, "a".repeat(64), "vertex", "gemini", "us-central1", "source-grounded-v2");
        GenerationContextCacheRepository.Reservation stale = repository.reserve(
                key, NOW, NOW.plusSeconds(1));
        GenerationContextCacheRepository.Reservation current = repository.reserve(
                key, NOW.plusSeconds(2), NOW.plusSeconds(3602));

        repository.activate(current.entry().id(), current.entry().generation(), 22,
                NOW.plusSeconds(2), NOW.plusSeconds(3602));
        assertThat(repository.entries.get(key).status()).isEqualTo("active");
        assertThat(repository.entries.get(key).generation()).isEqualTo(current.entry().generation());
        assertThat(repository.entries.get(key).tokenCount()).isEqualTo(22);
        repository.activate(stale.entry().id(), stale.entry().generation(), 11,
                NOW.plusSeconds(2), NOW.plusSeconds(3602));
        assertThat(repository.entries.get(key).status()).isEqualTo("active");
        assertThat(repository.entries.get(key).generation()).isEqualTo(current.entry().generation());
        assertThat(repository.entries.get(key).tokenCount()).isEqualTo(22);
        repository.fail(stale.entry().id(), stale.entry().generation(),
                "CACHE_CREATE_FAILED", NOW.plusSeconds(2));
        assertThat(repository.entries.get(key).status()).isEqualTo("active");
        assertThat(repository.entries.get(key).generation()).isEqualTo(current.entry().generation());
        assertThat(repository.entries.get(key).tokenCount()).isEqualTo(22);
        assertThat(repository.invalidate(stale.entry().id(), stale.entry().generation(),
                "CACHE_REFERENCE_MISSING", NOW.plusSeconds(3))).isFalse();
        assertThat(repository.entries.get(key).status()).isEqualTo("active");
        assertThat(repository.entries.get(key).generation()).isEqualTo(current.entry().generation());
        assertThat(repository.entries.get(key).tokenCount()).isEqualTo(22);
    }

    @Test
    void fakeRejectsTerminalUpdatesWhenExpectedStatusDoesNotMatch() {
        FakeRepository repository = new FakeRepository();
        GenerationContextCacheRepository.Key key = new GenerationContextCacheRepository.Key(
                OWNER, "c".repeat(64), "vertex", "gemini", "us-central1", "source-grounded-v2");
        GenerationContextCacheRepository.Reservation current = repository.reserve(
                key, NOW, NOW.plusSeconds(3600));
        repository.activate(current.entry().id(), current.entry().generation(),
                22, NOW, NOW.plusSeconds(3600));

        repository.activate(current.entry().id(), current.entry().generation(),
                99, NOW.plusSeconds(1), NOW.plusSeconds(3601));
        GenerationContextCacheRepository.Entry afterSecondActivation = repository.entries.get(key);
        repository.fail(current.entry().id(), current.entry().generation(),
                "CACHE_CREATE_FAILED", NOW.plusSeconds(1));
        GenerationContextCacheRepository.Entry afterFailure = repository.entries.get(key);

        assertSoftly(softly -> {
            softly.assertThat(afterSecondActivation.tokenCount()).isEqualTo(22);
            softly.assertThat(afterFailure.status()).isEqualTo("active");
            softly.assertThat(afterFailure.tokenCount()).isEqualTo(22);
        });
    }

    @Test
    void recordsOnlyBoundedOutcomeOperationAndTokenMetricTags() {
        FakeRepository repository = new FakeRepository();
        FakeProvider provider = new FakeProvider();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GenerationContextCacheLifecycle lifecycle = lifecycle(true, repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC), metrics);
        GenerationModelPort.GenerationRequest request = request(OWNER,
                "a".repeat(64), "source-grounded-v2");
        lifecycle.prepare(request, "gemini", "us-central1");
        lifecycle.prepare(request, "gemini", "us-central1");

        assertThat(metrics.get("mulgil.generation.context.cache.requests").counters())
                .extracting(counter -> counter.getId().getTag("result")).containsExactlyInAnyOrder("miss", "hit");
        assertThat(metrics.get("mulgil.generation.context.cache.tokens").summaries())
                .allSatisfy(summary -> assertThat(summary.totalAmount()).isEqualTo(42));
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("result", "operation")));
    }

    private static GenerationContextCacheLifecycle lifecycle(boolean enabled,
                                                             GenerationContextCacheRepository repository,
                                                             GenerationContextCachePort provider, Clock clock,
                                                             SimpleMeterRegistry metrics) {
        return new GenerationContextCacheLifecycle(enabled, 3600, repository, provider, metrics, clock);
    }

    private static GenerationModelPort.GenerationRequest request(UUID owner, String snapshot, String contract) {
        return new GenerationModelPort.GenerationRequest(new GenerationInputCompiler.CompiledInput(
                "private source text", List.of()), contract, GenerationModelPort.Artifact.SUMMARY,
                () -> {}, owner, snapshot);
    }

    private static final class FakeRepository implements GenerationContextCacheRepository {
        private final Map<Key, Entry> entries = new HashMap<>();
        private int reserveCalls;

        @Override
        public synchronized Reservation reserve(Key key, Instant now, Instant expiresAt) {
            reserveCalls++;
            Entry current = entries.get(key);
            if (current != null && current.expiresAt().isAfter(now) && !current.status().equals("failed")) {
                return new Reservation(current, false, false);
            }
            int generation = current == null ? 1 : current.generation() + 1;
            Entry claimed = new Entry(current == null ? UUID.randomUUID() : current.id(), key,
                    "creating", null, expiresAt, generation);
            entries.put(key, claimed);
            return new Reservation(claimed, true, generation > 1);
        }

        @Override
        public synchronized boolean activate(UUID id, int generation, long tokenCount,
                                             Instant now, Instant expiresAt) {
            Entry entry = find(id);
            if (entry != null && entry.generation() == generation && entry.status().equals("creating")) {
                entries.put(entry.key(), new Entry(id, entry.key(), "active", tokenCount,
                        expiresAt, entry.generation()));
                return true;
            }
            return false;
        }

        @Override
        public synchronized boolean invalidate(UUID id, int generation, String errorCode, Instant now) {
            Entry entry = find(id);
            if (entry == null || entry.generation() != generation || !entry.status().equals("active")) return false;
            entries.put(entry.key(), new Entry(id, entry.key(), "failed", entry.tokenCount(),
                    entry.expiresAt(), entry.generation()));
            return true;
        }

        @Override public void touch(UUID id, Instant now) {}

        @Override
        public synchronized boolean fail(UUID id, int generation, String errorCode, Instant now) {
            Entry entry = find(id);
            if (entry != null && entry.generation() == generation && entry.status().equals("creating")) {
                entries.put(entry.key(), new Entry(id, entry.key(), "failed", null,
                        entry.expiresAt(), entry.generation()));
                return true;
            }
            return false;
        }

        private Entry find(UUID id) {
            return entries.values().stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
        }
    }

    private static final class FakeProvider implements GenerationContextCachePort {
        private final Map<GenerationContextCacheRepository.Key, Reference> references = new HashMap<>();
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger finds = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch createRelease = new CountDownLatch(1);
        private boolean blockCreate;
        private boolean cleanupFails;
        private boolean createFails;

        @Override
        public synchronized Optional<Reference> find(GenerationContextCacheRepository.Key key) {
            finds.incrementAndGet();
            return Optional.ofNullable(references.get(key));
        }

        @Override
        public Created create(GenerationContextCacheRepository.Key key,
                              GenerationInputCompiler.CompiledInput input, Duration ttl) {
            creates.incrementAndGet();
            createEntered.countDown();
            if (createFails) throw new IllegalStateException("create failed");
            if (blockCreate) {
                try {
                    if (!createRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            Reference reference = new Reference("opaque-reference-" + creates.get());
            synchronized (this) { references.put(key, reference); }
            return new Created(reference, 42);
        }

        @Override
        public synchronized void delete(GenerationContextCacheRepository.Key key) {
            deletes.incrementAndGet();
            if (cleanupFails) throw new IllegalStateException("cleanup failed");
            references.remove(key);
        }

        private int totalCalls() {
            return creates.get() + finds.get() + deletes.get();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
