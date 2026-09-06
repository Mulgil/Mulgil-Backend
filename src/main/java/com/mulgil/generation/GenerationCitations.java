package com.mulgil.generation;

final class GenerationCitations {
    private GenerationCitations() {}

    static String sourceId(int index) {
        return "s" + (index + 1);
    }
}
