package com.mulgil.generation;

public interface GenerationModelPort {
    String generateJson(String prompt, String responseSchema);
}
