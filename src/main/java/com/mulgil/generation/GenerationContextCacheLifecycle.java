package com.mulgil.generation;

import com.mulgil.common.config.MulgilProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
final class GenerationContextCacheLifecycle {
    private final boolean enabled;
    private final Duration ttl;
    private final GenerationContextCacheRepository repository;
    private final GenerationContextCachePort provider;
    private final MeterRegistry metrics;
    private final Clock clock;

    @Autowired
    GenerationContextCacheLifecycle(MulgilProperties properties, GenerationContextCacheRepository repository,
                                    ObjectProvider<GenerationContextCachePort> provider,
                                    MeterRegistry metrics, Clock clock) {
        this(properties.generation().contextCacheEnabled(),
                properties.generation().contextCacheTtlSeconds(), repository,
                provider.getIfAvailable(), metrics, clock);
    }

    GenerationContextCacheLifecycle(boolean enabled, long ttlSeconds,
                                    GenerationContextCacheRepository repository,
                                    GenerationContextCachePort provider, MeterRegistry metrics, Clock clock) {
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.repository = repository;
        this.provider = provider;
        this.metrics = metrics;
        this.clock = clock;
    }

    Prepared prepare(GenerationModelPort.GenerationRequest request, String model, String location) {
        if (!enabled) return new Prepared("disabled", null, null);
        if (provider == null) {
            error("provider_unavailable");
            return observed("error", null, null);
        }
        GenerationContextCacheRepository.Key key = new GenerationContextCacheRepository.Key(
                request.ownerId(), request.snapshotHash(), "vertex", model, location, request.responseSchema());
        return prepare(key, request.input(), true);
    }

    private Prepared prepare(GenerationContextCacheRepository.Key key,
                             GenerationInputCompiler.CompiledInput input, boolean mayRetryMissing) {
        Instant now = clock.instant();
        GenerationContextCacheRepository.Reservation reservation;
        try {
            reservation = repository.reserve(key, now, now.plus(ttl));
        } catch (RuntimeException exception) {
            error("repository");
            return observed("error", null, null);
        }
        if (reservation.creator()) return create(reservation, input, now);
        if (!reservation.entry().status().equals("active")) return observed("miss", null, null);
        try {
            Optional<GenerationContextCachePort.Reference> reference = provider.find(key);
            if (reference.isPresent()) {
                repository.touch(reservation.entry().id(), now);
                return observed("hit", reference.orElseThrow(), reservation.entry().tokenCount());
            }
            if (mayRetryMissing && repository.invalidate(reservation.entry().id(),
                    reservation.entry().generation(), "CACHE_REFERENCE_MISSING", now)) {
                return prepare(key, input, false);
            }
            return observed("miss", null, null);
        } catch (RuntimeException exception) {
            error("lookup");
            return observed("error", null, null);
        }
    }

    private Prepared create(GenerationContextCacheRepository.Reservation reservation,
                            GenerationInputCompiler.CompiledInput input, Instant now) {
        if (reservation.replacesExpired()) {
            try {
                provider.delete(reservation.entry().key());
            } catch (RuntimeException exception) {
                error("cleanup");
            }
        }
        try {
            GenerationContextCachePort.Created created = provider.create(
                    reservation.entry().key(), input, ttl);
            repository.activate(reservation.entry().id(), reservation.entry().generation(),
                    created.tokenCount(), now, now.plus(ttl));
            return observed("miss", created.reference(), created.tokenCount());
        } catch (RuntimeException exception) {
            try {
                repository.fail(reservation.entry().id(), reservation.entry().generation(),
                        "CACHE_CREATE_FAILED", now);
            } catch (RuntimeException persistenceFailure) {
                error("repository");
            }
            error("create");
            return observed("error", null, null);
        }
    }

    private Prepared observed(String status, GenerationContextCachePort.Reference reference, Long tokens) {
        metrics.counter("mulgil.generation.context.cache.requests", "result", status).increment();
        if (tokens != null) {
            metrics.summary("mulgil.generation.context.cache.tokens", "result", status).record(tokens);
        }
        return new Prepared(status, reference, tokens);
    }

    private void error(String operation) {
        metrics.counter("mulgil.generation.context.cache.errors", "operation", operation).increment();
    }

    record Prepared(String status, GenerationContextCachePort.Reference cacheReference, Long tokenCount) {
        Optional<GenerationContextCachePort.Reference> reference() {
            return Optional.ofNullable(cacheReference);
        }
    }
}
