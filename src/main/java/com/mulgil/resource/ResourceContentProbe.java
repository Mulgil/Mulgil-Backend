package com.mulgil.resource;

public interface ResourceContentProbe {
    PdfInspection inspectPdf(String objectKey);

    AudioInspection inspectAudio(String objectKey);

    record PdfInspection(int pageCount, String checksumSha256) {}

    record AudioInspection(long durationSeconds, String checksumSha256) {}
}
