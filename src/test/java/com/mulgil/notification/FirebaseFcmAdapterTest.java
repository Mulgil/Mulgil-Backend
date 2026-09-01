package com.mulgil.notification;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.mulgil.common.config.MulgilProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FirebaseFcmAdapterTest {
    private final FirebaseMessaging firebase = mock(FirebaseMessaging.class);
    private final FirebaseFcmAdapter adapter = new FirebaseFcmAdapter(firebase);

    @Test
    void selectsPropertiesConstructorForSpringInjection_whenAdapterHasTestConstructor() throws Exception {
        // Given
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        Constructor<?> propertiesConstructor = FirebaseFcmAdapter.class
                .getDeclaredConstructor(MulgilProperties.class);

        // When
        Constructor<?>[] candidates = processor.determineCandidateConstructors(
                FirebaseFcmAdapter.class, "firebaseFcmAdapter");

        // Then
        assertThat(candidates).containsExactly(propertiesConstructor);
    }

    @Test
    void sendsNotificationAndExactData_whenMessageIsValid() throws Exception {
        // Given
        ArgumentCaptor<com.google.firebase.messaging.Message> payload =
                ArgumentCaptor.forClass(com.google.firebase.messaging.Message.class);
        when(firebase.send(payload.capture())).thenReturn("projects/demo/messages/1");

        // When
        String result = adapter.send("device-token", new FcmPort.Message(
                "처리가 완료됐어요", "결과를 확인해 보세요.", "resource-1", "mulgil://sessions/resource-1"));

        // Then
        JsonNode sent = new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .valueToTree(payload.getValue());
        assertThat(result).isEqualTo("projects/demo/messages/1");
        assertThat(sent.path("token").asText()).isEqualTo("device-token");
        assertThat(sent.path("notification").path("title").asText()).isEqualTo("처리가 완료됐어요");
        assertThat(sent.path("notification").path("body").asText()).isEqualTo("결과를 확인해 보세요.");
        assertThat(sent.path("data").fieldNames()).toIterable()
                .containsExactlyInAnyOrderElementsOf(Set.of("resourceId", "deepLink"));
        assertThat(sent.path("data").path("resourceId").asText()).isEqualTo("resource-1");
        assertThat(sent.path("data").path("deepLink").asText()).isEqualTo("mulgil://sessions/resource-1");
        verify(firebase).send(payload.getValue());
    }

    @Test
    void mapsInvalidAndTransientErrors_whenFirebaseRejectsSend() throws Exception {
        // Given
        FirebaseMessagingException invalidToken = mockFailure(MessagingErrorCode.UNREGISTERED, ErrorCode.NOT_FOUND);
        FirebaseMessagingException rateLimit = mockFailure(
                MessagingErrorCode.QUOTA_EXCEEDED, ErrorCode.RESOURCE_EXHAUSTED);
        FirebaseMessagingException unavailable = mockFailure(MessagingErrorCode.UNAVAILABLE, ErrorCode.UNAVAILABLE);
        when(firebase.send(org.mockito.ArgumentMatchers.any())).thenThrow(
                invalidToken, rateLimit, unavailable);

        // When / Then
        assertFailure("DEVICE_TOKEN_UNAVAILABLE", false);
        assertFailure("PROVIDER_RATE_LIMIT", true);
        assertFailure("PROVIDER_UNAVAILABLE", true);
    }

    @Test
    void rejectsMalformedPayload_withoutCallingFirebase() {
        // Given / When / Then
        assertThatThrownBy(() -> adapter.send("", new FcmPort.Message(
                "title", "body", "resource-1", "mulgil://sessions/resource-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.send("device-token", new FcmPort.Message(
                "title", "body", "", "mulgil://sessions/resource-1")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(firebase);
    }

    private void assertFailure(String code, boolean retryable) {
        FcmPort.FcmException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                FcmPort.FcmException.class, () -> adapter.send("device-token",
                        new FcmPort.Message("title", "body", "resource-1", "mulgil://sessions/resource-1")));
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.retryable()).isEqualTo(retryable);
    }

    private static FirebaseMessagingException mockFailure(MessagingErrorCode messagingCode, ErrorCode errorCode) {
        FirebaseMessagingException failure = mock(FirebaseMessagingException.class);
        when(failure.getMessagingErrorCode()).thenReturn(messagingCode);
        when(failure.getErrorCode()).thenReturn(errorCode);
        return failure;
    }
}
