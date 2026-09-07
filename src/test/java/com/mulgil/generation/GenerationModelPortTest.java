package com.mulgil.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationModelPortTest {
    @Test
    void rejectsNegativeThoughtTokenCount() {
        assertThatThrownBy(() -> new GenerationModelPort.GenerationUsage(1L, 2L, 3L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedFinishReason() {
        assertThatThrownBy(() -> new GenerationModelPort.GenerationResult("{}", null, null,
                "provider raw detail"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
