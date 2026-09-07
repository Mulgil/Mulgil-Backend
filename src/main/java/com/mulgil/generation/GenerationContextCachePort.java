package com.mulgil.generation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public interface GenerationContextCachePort {
    Optional<Reference> find(GenerationContextCacheRepository.Key key);
    Created create(GenerationContextCacheRepository.Key key, GenerationInputCompiler.CompiledInput input,
                   Duration ttl);
    void delete(GenerationContextCacheRepository.Key key);

    record Reference(String value) {
        public Reference { Objects.requireNonNull(value); }
    }
    record Created(Reference reference, long tokenCount) {
        public Created {
            Objects.requireNonNull(reference);
            if (tokenCount < 0) throw new IllegalArgumentException("Cache token count must not be negative.");
        }
    }
}
