ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_job_type_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_job_type_check CHECK (job_type IN
    ('pdf_extract', 'pdf_ocr', 'handwriting_ocr', 'stt', 'chunk_embed', 'preview_generate',
     'review_generate', 'preview_mindmap_generate', 'review_mindmap_generate',
     'preview_quiz_generate', 'review_quiz_generate', 'exam_summary_generate',
     'exam_quiz_generate', 'notification_send'));

ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_progress_stage_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_progress_stage_check CHECK (
    (progress_stage IS NULL AND progress_updated_at IS NULL)
    OR (
        job_type IN ('preview_generate', 'review_generate',
                     'preview_mindmap_generate', 'review_mindmap_generate',
                     'preview_quiz_generate', 'review_quiz_generate',
                     'exam_summary_generate', 'exam_quiz_generate')
        AND progress_stage IN ('preparing', 'generating', 'validating', 'publishing')
        AND progress_updated_at IS NOT NULL
    )
);
