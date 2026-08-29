package com.mulgil.resource;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.storage.CloudStoragePort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!test & !smoke")
final class ServerResourceContentProbe implements ResourceContentProbe {
    private final CloudStoragePort storage;
    private final Clock clock;
    private final MulgilProperties properties;

    ServerResourceContentProbe(CloudStoragePort storage, Clock clock, MulgilProperties properties) {
        this.storage = storage;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public PdfInspection inspectPdf(String objectKey) {
        Downloaded downloaded = download(objectKey, ".pdf");
        try (PDDocument document = Loader.loadPDF(downloaded.path().toFile())) {
            return new PdfInspection(document.getNumberOfPages(), downloaded.checksum());
        } catch (IOException exception) {
            throw invalidContent();
        } finally {
            delete(downloaded.path());
        }
    }

    @Override
    public AudioInspection inspectAudio(String objectKey) {
        Downloaded downloaded = download(objectKey, ".m4a");
        try {
            Process process = new ProcessBuilder(ffprobe(), "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", downloaded.path().toString())
                    .redirectErrorStream(true).start();
            boolean completed = process.waitFor(properties.jobs().providerTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw invalidContent();
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.exitValue() != 0) throw invalidContent();
            long duration = (long) Math.ceil(Double.parseDouble(output));
            if (duration < 1) throw invalidContent();
            return new AudioInspection(duration, downloaded.checksum());
        } catch (IOException | InterruptedException | NumberFormatException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw invalidContent();
        } finally {
            delete(downloaded.path());
        }
    }

    private Downloaded download(String objectKey, String suffix) {
        try {
            Instant expiry = clock.instant().plusSeconds(properties.gcs().signedUrlTtlSeconds());
            URLConnection connection = storage.createDownloadUrl(objectKey, expiry).toURL().openConnection();
            int timeoutMillis = Math.toIntExact(properties.jobs().providerTimeoutSeconds() * 1000);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            Path path = Files.createTempFile("mulgil-probe-", suffix);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(connection.getInputStream(), digest)) {
                Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                delete(path);
                throw exception;
            }
            return new Downloaded(path, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw invalidContent();
        }
    }

    private String ffprobe() {
        Path ffmpeg = Path.of(properties.uploads().ffmpegPath());
        Path filename = ffmpeg.getFileName();
        if (filename != null && filename.toString().equals("ffmpeg") && ffmpeg.getParent() != null) {
            return ffmpeg.getParent().resolve("ffprobe").toString();
        }
        return filename != null && filename.toString().equals("ffmpeg") ? "ffprobe" : ffmpeg.toString();
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Temporary upload probe cleanup failed.", exception);
        }
    }

    private static ApiException invalidContent() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "Uploaded content validation failed.");
    }

    private record Downloaded(Path path, String checksum) {}
}
