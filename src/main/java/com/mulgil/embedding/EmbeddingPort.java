package com.mulgil.embedding;

import java.util.List;

public interface EmbeddingPort {
    List<Float> embed(String text);
}
