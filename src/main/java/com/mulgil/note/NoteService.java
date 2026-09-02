package com.mulgil.note;

import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class NoteService {
    private final JdbcClient jdbc;
    private final ContentIndexingService indexing;
    private final Clock clock;

    NoteService(JdbcClient jdbc, ContentIndexingService indexing, Clock clock) {
        this.jdbc = jdbc;
        this.indexing = indexing;
        this.clock = clock;
    }

    @Transactional
    Note create(UUID ownerId, UUID sessionId, String body) {
        Scope scope = jdbc.sql("SELECT course_id FROM class_sessions WHERE owner_id=:owner AND id=:session")
                .param("owner", ownerId).param("session", sessionId)
                .query((row, ignored) -> new Scope(row.getObject("course_id", UUID.class), sessionId))
                .optional().orElseThrow(NoteService::notFound);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                INSERT INTO notes
                    (id,owner_id,course_id,session_id,body_markdown,version,last_left_version,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,:body,1,0,:now,:now)
                """).param("id", id).param("owner", ownerId).param("course", scope.courseId())
                .param("session", sessionId).param("body", body).param("now", now).update();
        return new Note(id, sessionId, body, 1);
    }

    List<Note> list(UUID ownerId, UUID sessionId) {
        if (!sessionExists(ownerId, sessionId)) throw notFound();
        return jdbc.sql("""
                SELECT id,session_id,body_markdown,version
                FROM notes
                WHERE owner_id=:owner AND session_id=:session
                ORDER BY updated_at DESC, id DESC
                """).param("owner", ownerId).param("session", sessionId)
                .query((row, ignored) -> new Note(row.getObject("id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getString("body_markdown"),
                        row.getInt("version")))
                .list();
    }

    Note get(UUID ownerId, UUID noteId) {
        StoredNote note = read(ownerId, noteId);
        return new Note(noteId, note.sessionId(), note.body(), note.version());
    }

    @Transactional
    Note patch(UUID ownerId, UUID noteId, String body, int expectedVersion) {
        StoredNote note = lock(ownerId, noteId);
        if (note.version() != expectedVersion) throw conflict();
        int version = expectedVersion + 1;
        jdbc.sql("UPDATE notes SET body_markdown=:body,version=:version,updated_at=:now WHERE id=:id")
                .param("body", body).param("version", version).param("now", Timestamp.from(clock.instant()))
                .param("id", noteId).update();
        return new Note(noteId, note.sessionId(), body, version);
    }

    @Transactional
    JobQueue.JobAccepted leave(UUID ownerId, UUID noteId, int changedVersion) {
        StoredNote note = lock(ownerId, noteId);
        if (note.version() != changedVersion) throw conflict();
        if (note.lastLeftVersion() >= changedVersion) return null;
        jdbc.sql("UPDATE notes SET last_left_version=:version,updated_at=:now WHERE id=:id")
                .param("version", changedVersion).param("now", Timestamp.from(clock.instant()))
                .param("id", noteId).update();
        jdbc.sql("DELETE FROM content_blocks WHERE note_id=:id").param("id", noteId).update();
        String[] paragraphs = note.body().strip().split("(?:\\R\\s*){2,}");
        JobQueue.JobAccepted accepted = null;
        int offset = 0;
        for (String paragraph : paragraphs) {
            String text = paragraph.strip();
            if (text.isEmpty()) continue;
            UUID blockId = UUID.nameUUIDFromBytes((noteId + ":" + changedVersion + ":" + offset)
                    .getBytes(StandardCharsets.UTF_8));
            String blockHash = ContentIndexingService.sha256(changedVersion + ":" + text);
            jdbc.sql("""
                    INSERT INTO content_blocks
                        (id,owner_id,course_id,session_id,note_id,block_type,text_content,
                         paragraph_offset,source_hash,created_at)
                    VALUES (:id,:owner,:course,:session,:note,'text',:text,:offset,:hash,:now)
                    """).param("id", blockId).param("owner", ownerId).param("course", note.courseId())
                    .param("session", note.sessionId()).param("note", noteId).param("text", text)
                    .param("offset", offset).param("hash", blockHash).param("now", Timestamp.from(clock.instant())).update();
            Map<String, Object> reference = Map.of("sourceType", "note", "noteId", noteId,
                    "contentBlockId", blockId, "paragraphOffset", offset, "inputVersion", changedVersion);
            ContentIndexingService.IndexResult indexed = indexing.index(new ContentIndexingService.IndexRequest(
                    "note", reference, ownerId, note.courseId(), note.sessionId(), changedVersion, text));
            if (accepted == null) {
                accepted = indexed.job();
            }
            offset += text.length();
        }
        return accepted;
    }

    private boolean sessionExists(UUID ownerId, UUID sessionId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM class_sessions WHERE owner_id=:owner AND id=:session)")
                .param("owner", ownerId).param("session", sessionId)
                .query(Boolean.class).single();
    }

    private StoredNote read(UUID ownerId, UUID noteId) {
        return jdbc.sql("""
                SELECT course_id,session_id,body_markdown,version,last_left_version
                FROM notes WHERE owner_id=:owner AND id=:id
                """).param("owner", ownerId).param("id", noteId)
                .query((row, ignored) -> new StoredNote(
                        row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getString("body_markdown"),
                        row.getInt("version"),
                        row.getInt("last_left_version")))
                .optional().orElseThrow(NoteService::notFound);
    }

    private StoredNote lock(UUID ownerId, UUID noteId) {
        return jdbc.sql("""
                SELECT course_id,session_id,body_markdown,version,last_left_version
                FROM notes WHERE owner_id=:owner AND id=:id FOR UPDATE
                """).param("owner", ownerId).param("id", noteId)
                .query((row, ignored) -> new StoredNote(row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getString("body_markdown"),
                        row.getInt("version"), row.getInt("last_left_version")))
                .optional().orElseThrow(NoteService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND", "Note not found.");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Version conflict.");
    }

    record Note(UUID id, UUID sessionId, String bodyMarkdown, int version) {}
    private record Scope(UUID courseId, UUID sessionId) {}
    private record StoredNote(UUID courseId, UUID sessionId, String body, int version, int lastLeftVersion) {}
}
