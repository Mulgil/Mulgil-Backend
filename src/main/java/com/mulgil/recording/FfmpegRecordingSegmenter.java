package com.mulgil.recording;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
final class FfmpegRecordingSegmenter implements RecordingSegmenter {
    private final CloudStoragePort storage;
    private final String ffmpeg;
    private final long timeoutSeconds;

    FfmpegRecordingSegmenter(CloudStoragePort storage, MulgilProperties properties) {
        this.storage = storage;
        this.ffmpeg = properties.uploads().ffmpegPath();
        this.timeoutSeconds = properties.jobs().providerTimeoutSeconds();
    }

    @Override
    public List<AudioSegment> split(String objectKey, Duration duration, Duration maxSegmentDuration) {
        Path directory = null;
        try {
            byte[] audio = storage.read(objectKey);
            if (audio == null) throw new IllegalStateException("Recording object is unavailable.");
            directory = Files.createTempDirectory("mulgil-stt-");
            Path source = directory.resolve("source.m4a");
            Files.write(source, audio);
            List<AudioSegment> result = new ArrayList<>();
            for (Duration offset = Duration.ZERO; offset.compareTo(duration) < 0; offset = offset.plus(maxSegmentDuration)) {
                Duration length = duration.minus(offset).compareTo(maxSegmentDuration) < 0
                        ? duration.minus(offset) : maxSegmentDuration;
                Path output = directory.resolve("segment-%04d.m4a".formatted(result.size()));
                run(source, output, offset, length);
                result.add(new AudioSegment(output, offset));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            if (directory != null) deleteDirectory(directory);
            throw new IllegalStateException("Could not prepare recording segments.", exception);
        } catch (RuntimeException exception) {
            if (directory != null) deleteDirectory(directory);
            throw exception;
        }
    }

    @Override
    public void cleanup(List<AudioSegment> segments) {
        if (!segments.isEmpty()) deleteDirectory(segments.getFirst().path().getParent());
    }

    private void run(Path source, Path output, Duration offset, Duration length) {
        Process process = null;
        try {
            process = new ProcessBuilder(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-ss", decimalSeconds(offset), "-i", source.toString(), "-t", decimalSeconds(length),
                    "-map", "0:a:0", "-c:a", "copy", "-vn", "-y", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg timed out.");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg failed.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not run FFmpeg.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg was interrupted.", exception);
        }
    }

    private static String decimalSeconds(Duration value) {
        return "%d.%03d".formatted(value.toSeconds(), value.toMillisPart());
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not clean temporary audio.", exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Could not clean temporary audio.", exception);
        }
    }
}
