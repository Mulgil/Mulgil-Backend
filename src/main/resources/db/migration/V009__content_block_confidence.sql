ALTER TABLE content_blocks
    ADD COLUMN confidence numeric CHECK (confidence BETWEEN 0 AND 1);
