package com.mulgil.indexing;

import java.util.List;

public interface ChunkEmbeddingPort {
    Embedding embed(String text);

    record Embedding(List<Float> values, String model) {
        public Embedding {
            values = List.copyOf(values);
        }
    }
}
