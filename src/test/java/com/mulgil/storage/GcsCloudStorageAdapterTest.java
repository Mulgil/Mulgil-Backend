package com.mulgil.storage;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.mulgil.common.config.MulgilProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GcsCloudStorageAdapterTest {
    @Test
    void rejectsSignedUrlsForPrivateTargetPayload_beforeSignerInvocation() throws Exception {
        Storage storage = mock(Storage.class);
        GcsCloudStorageAdapter adapter = adapter(storage);
        String internalKey = "temporary/target-generations/owner/job.json";

        assertThatThrownBy(() -> adapter.createUploadUrl(
                internalKey, "application/json", 12, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.createDownloadUrl(internalKey, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void signsExpectedMaterialKeys() throws Exception {
        Storage storage = mock(Storage.class);
        GcsCloudStorageAdapter adapter = adapter(storage);
        URL signed = URI.create("https://storage.example/signed").toURL();
        when(storage.signUrl(any(BlobInfo.class), anyLong(), eq(TimeUnit.SECONDS),
                any(Storage.SignUrlOption[].class))).thenReturn(signed);

        assertThat(adapter.createUploadUrl("materials/owner/file.pdf", "application/pdf", 12,
                Instant.now().plusSeconds(60))).isEqualTo(signed.toURI());
        assertThat(adapter.createDownloadUrl("materials/owner/file.pdf", Instant.now().plusSeconds(60)))
                .isEqualTo(signed.toURI());
        verify(storage, times(2)).signUrl(any(BlobInfo.class), anyLong(), eq(TimeUnit.SECONDS),
                any(Storage.SignUrlOption[].class));
    }

    private static GcsCloudStorageAdapter adapter(Storage storage) throws Exception {
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.gcs()).thenReturn(new MulgilProperties.Gcs("test-bucket", 60));
        GcsCloudStorageAdapter adapter = new GcsCloudStorageAdapter(properties);
        Field field = GcsCloudStorageAdapter.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(adapter, storage);
        return adapter;
    }
}
