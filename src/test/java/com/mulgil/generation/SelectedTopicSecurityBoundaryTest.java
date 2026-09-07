package com.mulgil.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.indexing.ContentIndexingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectedTopicSecurityBoundaryTest {
    @Test
    void rejectsCrossOwnerScope_beforeEmbeddingProviderInvocation() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec authorization = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec ledger = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked") JdbcClient.MappedQuerySpec<Boolean> ownership =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenAnswer(invocation -> invocation.<String>getArgument(0)
                .contains("SELECT EXISTS") ? authorization : ledger);
        when(authorization.param(anyString(), any())).thenReturn(authorization);
        when(authorization.query(Boolean.class)).thenReturn(ownership);
        when(ownership.single()).thenReturn(false);
        when(ledger.param(anyString(), any())).thenReturn(ledger);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChunkEmbeddingPort> embeddings = mock(ObjectProvider.class);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.retrieval()).thenReturn(new MulgilProperties.Retrieval(true, 20, 8, 30));
        var service = new SelectedTopicRetrievalService(
                jdbc, embeddings, properties, new ObjectMapper(), Clock.systemUTC());
        String query = "private query";

        assertThatThrownBy(() -> service.retrieve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                query, SelectedTopicGenerationService.Intent.SUMMARY, 3, null,
                SelectedTopicGenerationService.ScopeExpansion.NONE))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("SESSION_NOT_FOUND");
        verify(embeddings, never()).getIfAvailable();
        verify(ledger).param("hash", ContentIndexingService.sha256(query));
        verify(ledger, never()).param(anyString(), eq(query));
    }
}
