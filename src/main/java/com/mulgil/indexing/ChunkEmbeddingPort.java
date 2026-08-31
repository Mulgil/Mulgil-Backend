package com.mulgil.indexing;

import java.util.List;
import java.util.function.Supplier;

public interface ChunkEmbeddingPort {
    Embedding embed(String text);

    default List<Embedding> embedAll(List<String> texts) {
        return embedAll(texts, ProviderCallObserver.direct());
    }

    default List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
        java.util.ArrayList<Embedding> results = new java.util.ArrayList<>(texts.size());
        List<Embedding> pending = List.of();
        int pendingIndex = 0;
        for (int index = 0; index < texts.size(); index++) {
            if (!pending.isEmpty()) observer.checkpoint(pendingIndex, pending);
            int currentIndex = index;
            pending = observer.observe(index, List.of(texts.get(index)),
                    () -> List.of(embed(texts.get(currentIndex))));
            pendingIndex = index;
            results.addAll(pending);
        }
        return List.copyOf(results);
    }

    record Embedding(List<Float> values, String model) {
        public Embedding {
            values = List.copyOf(values);
        }
    }

    interface ProviderCallObserver {
        List<Embedding> observe(int startIndex, List<String> texts, Supplier<List<Embedding>> providerCall);

        default void checkpoint(int startIndex, List<Embedding> embeddings) {}

        static ProviderCallObserver direct() {
            return (startIndex, texts, providerCall) -> providerCall.get();
        }
    }
}
