package com.mulgil.document;

import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.ocr.VisionOcrPort;
import com.mulgil.storage.CloudStoragePort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "JOB_POLL_INTERVAL_MILLIS=600000")
@Import(PdfIndexingIT.Fakes.class)
class PdfIndexingIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcClient jdbc;
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired FakeStorage storage;
    @Autowired FakeVision vision;
    @Autowired ContentIndexingService indexing;

    UUID owner;
    UUID course;
    UUID session;

    @BeforeEach
    void seedScope() {
        jdbc.sql("DELETE FROM users").update();
        storage.clear();
        vision.reset();
        owner = UUID.randomUUID();
        course = UUID.randomUUID();
        session = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO users VALUES (:id, 'google', :subject, :email, 'Owner', :now)")
                .param("id", owner).param("subject", owner.toString()).param("email", owner + "@example.com")
                .param("now", now).update();
        jdbc.sql("INSERT INTO courses VALUES (:id, :owner, 'Course', NULL, NULL, :now, :now)")
                .param("id", course).param("owner", owner).param("now", now).update();
        insertSession(session, 1);
    }

    @Test
    void indexesTextWithoutOcr_whenNonWhitespaceLengthIs80() throws Exception {
        byte[] pdf = pdf("x".repeat(80), false);
        UUID material = insertMaterial(pdf);
        String checksum = checksum(pdf);

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("chunk_embed");

        assertThat(vision.calls()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE owner_id=:owner AND course_id=:course "
                        + "AND session_id=:session AND embedding IS NOT NULL")
                .param("owner", owner).param("course", course).param("session", session)
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT checksum FROM materials WHERE id=:id").param("id", material)
                .query(String.class).single()).isEqualTo(checksum);
    }

    @Test
    void invokesOcrOnce_whenNonWhitespaceLengthIs79() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(79), false));

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("pdf_ocr");

        assertThat(vision.calls()).isOne();
        assertThat(jdbc.sql("SELECT extraction_method FROM document_pages WHERE material_id=:id")
                .param("id", material).query(String.class).single()).isEqualTo("ocr");
        assertThat(jdbc.sql("SELECT confidence FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Double.class).single()).isEqualTo(0.91);
    }

    @Test
    void invokesOcrOnce_whenImageCoverageIsExactlyHalf() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(80), true));

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("pdf_ocr");

        assertThat(vision.calls()).isOne();
    }

    @Test
    void materializesPastExamForSelectedSessions_andForeignOwnerReadsNothing() throws Exception {
        UUID secondSession = UUID.randomUUID();
        insertSession(secondSession, 2);
        byte[] pdf = pdf("exam".repeat(20), false);
        UUID exam = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO exams VALUES (:id,:owner,:course,'Exam',:now,:now,:now)")
                .param("id", exam).param("owner", owner).param("course", course).param("now", now).update();
        for (UUID selected : List.of(session, secondSession)) {
            jdbc.sql("INSERT INTO exam_session_members VALUES (:exam,:session,:owner,:course,:now)")
                    .param("exam", exam).param("session", selected).param("owner", owner)
                    .param("course", course).param("now", now).update();
        }
        String key = "exam/" + resource;
        storage.put(key, pdf);
        jdbc.sql("""
                INSERT INTO exam_resources
                    (id,owner_id,course_id,exam_id,resource_type,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:exam,'past_exam',:key,'exam.pdf','application/pdf',
                        :size,1,:hash,'uploaded',:now,:now)
                """).param("id", resource).param("owner", owner).param("course", course).param("exam", exam)
                .param("key", key).param("size", pdf.length).param("hash", checksum(pdf)).param("now", now).update();

        jobs.enqueuePdfExamResource(owner, resource);
        runAll("pdf_extract");

        List<String> refs = jdbc.sql("SELECT source_ref::text FROM chunks WHERE owner_id=:owner ORDER BY session_id")
                .param("owner", owner).query(String.class).list();
        assertThat(refs).hasSize(2).allMatch(reference -> reference.contains("\"sourceType\": \"past_exam\"")
                && reference.contains(resource.toString()));
        assertThat(jdbc.sql("SELECT count(DISTINCT session_id) FROM chunks "
                        + "WHERE source_ref->>'examResourceId'=:resource")
                .param("resource", resource.toString()).query(Integer.class).single()).isEqualTo(2);
        assertThat(indexing.chunks(UUID.randomUUID(), course, session)).isEmpty();
    }

    private UUID insertMaterial(byte[] pdf) throws Exception {
        UUID id = UUID.randomUUID();
        String key = "material/" + id;
        storage.put(key, pdf);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                INSERT INTO materials
                    (id,owner_id,course_id,session_id,source_phase,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,version,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,'preview_pdf',:key,'source.pdf','application/pdf',
                        :size,1,:hash,1,'uploaded',:now,:now)
                """).param("id", id).param("owner", owner).param("course", course).param("session", session)
                .param("key", key).param("size", pdf.length).param("hash", checksum(pdf)).param("now", now).update();
        return id;
    }

    private void insertSession(UUID id, int number) {
        jdbc.sql("""
                INSERT INTO class_sessions
                    (id,owner_id,course_id,session_number,title,session_date,created_at,updated_at)
                VALUES (:id,:owner,:course,:number,'Session',DATE '2026-09-01',:now,:now)
                """).param("id", id).param("owner", owner).param("course", course).param("number", number)
                .param("now", Timestamp.from(Instant.now())).update();
    }

    private void runAll(String type) throws Exception {
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals(type)).findFirst().orElseThrow();
        JobQueue.ClaimedJob job;
        while ((job = jobs.claim("pdf-it", Set.of(type))) != null) {
            jobs.complete(job, handler.handle(job));
        }
    }

    private static byte[] pdf(String text, boolean halfPageImage) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(20, 760);
                content.showText(text);
                content.endText();
                if (halfPageImage) {
                    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D graphics = image.createGraphics();
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, 2, 2);
                    graphics.dispose();
                    content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 612, 396);
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static String checksum(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeStorage storage() { return new FakeStorage(); }
        @Bean FakeVision vision() { return new FakeVision(); }
        @Bean ChunkEmbeddingPort embeddings() {
            return text -> new ChunkEmbeddingPort.Embedding(java.util.Collections.nCopies(768, 0.25f), "fake-768");
        }
    }

    static final class FakeStorage implements CloudStoragePort {
        private final Map<String, byte[]> objects = new HashMap<>();
        void put(String key, byte[] bytes) { objects.put(key, bytes.clone()); }
        void clear() { objects.clear(); }
        @Override public URI createUploadUrl(String key, String type, long length, Instant expiresAt) {
            return URI.create("https://storage.invalid/upload");
        }
        @Override public URI createDownloadUrl(String key, Instant expiresAt) {
            return URI.create("https://storage.invalid/download");
        }
        @Override public StoredObjectMetadata metadata(String key) { return null; }
        @Override public byte[] read(String key) { return objects.get(key).clone(); }
    }

    static final class FakeVision implements VisionOcrPort {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public OcrResult extract(byte[] image) {
            calls.incrementAndGet();
            return new OcrResult(List.of(new OcrBlock("recognized text", 0.91,
                    new NormalizedBox(0.1, 0.2, 0.7, 0.2))), "fake-vision", "fake-ocr");
        }
        int calls() { return calls.get(); }
        void reset() { calls.set(0); }
    }
}
