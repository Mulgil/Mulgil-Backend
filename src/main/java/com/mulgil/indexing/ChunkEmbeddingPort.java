package com.mulgil.indexing;

import java.util.List;

public interface ChunkEmbeddingPort {
    Embedding embed(String text);

    default List<Embedding> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    record Embedding(List<Float> values, String model) {
        public Embedding {
            values = List.copyOf(values);
        }
    }
}
