package com.mulgil.generation;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SelectedTopicRequestValidationTest {
    @Test
    void rejectsBlankQueryMissingIntentAndOutOfRangeTopK_atRequestBoundary() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new SelectedTopicGenerationService.Request(UUID.randomUUID(), "  ", null, 21,
                    List.of(UUID.randomUUID()), null);

            assertThat(factory.getValidator().validate(request)).extracting(error -> error.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("query", "intent", "topK", "scopeExpansion");
        }
    }
}
