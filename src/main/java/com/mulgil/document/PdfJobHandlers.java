package com.mulgil.document;

import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.springframework.stereotype.Component;

@Component
final class PdfExtractJobHandler implements JobHandler {
    private final PdfIndexingService service;

    PdfExtractJobHandler(PdfIndexingService service) {
        this.service = service;
    }

    @Override
    public String jobType() {
        return "pdf_extract";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        PdfIndexingService.PreparedPdf prepared = service.extract(job);
        return () -> service.publishExtract(job, prepared);
    }
}

@Component
final class PdfOcrJobHandler implements JobHandler {
    private final PdfIndexingService service;

    PdfOcrJobHandler(PdfIndexingService service) {
        this.service = service;
    }

    @Override
    public String jobType() {
        return "pdf_ocr";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        PdfIndexingService.PreparedOcr prepared = service.ocr(job);
        return () -> service.publishOcr(job, prepared);
    }
}
