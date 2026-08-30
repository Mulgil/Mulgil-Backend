package com.mulgil.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.mulgil.common.config.MulgilProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@Profile("!test & !smoke")
@ConditionalOnProperty(prefix = "mulgil.fcm", name = "enabled", havingValue = "true")
final class FirebaseFcmAdapter implements FcmPort {
    private final FirebaseMessaging firebase;
    private final FirebaseApp app;

    FirebaseFcmAdapter(MulgilProperties properties) {
        app = initialize(properties.google().cloudProject());
        firebase = FirebaseMessaging.getInstance(app);
    }

    FirebaseFcmAdapter(FirebaseMessaging firebase) {
        this.firebase = firebase;
        this.app = null;
    }

    @Override
    public String send(String deviceToken, Message message) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new IllegalArgumentException("FCM device token must not be blank.");
        }
        com.google.firebase.messaging.Message payload = com.google.firebase.messaging.Message.builder()
                .setToken(deviceToken)
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(message.title()).setBody(message.body()).build())
                .putAllData(Map.of("resourceId", message.resourceId(), "deepLink", message.deepLink()))
                .build();
        try {
            return firebase.send(payload);
        } catch (FirebaseMessagingException exception) {
            throw failure(exception);
        }
    }

    @PreDestroy
    void close() {
        if (app != null) app.delete();
    }

    private static FirebaseApp initialize(String projectId) {
        try {
            return FirebaseApp.initializeApp(FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(projectId)
                    .build());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize Firebase with application default credentials.",
                    exception);
        }
    }

    private static FcmException failure(FirebaseMessagingException exception) {
        MessagingErrorCode messagingCode = exception.getMessagingErrorCode();
        if (messagingCode != null) return switch (messagingCode) {
            case UNREGISTERED -> new FcmException("DEVICE_TOKEN_UNAVAILABLE", false);
            case INVALID_ARGUMENT, SENDER_ID_MISMATCH -> new FcmException("INVALID_DEVICE_TOKEN", false);
            case QUOTA_EXCEEDED -> new FcmException("PROVIDER_RATE_LIMIT", true);
            case UNAVAILABLE, INTERNAL -> new FcmException("PROVIDER_UNAVAILABLE", true);
            case THIRD_PARTY_AUTH_ERROR -> new FcmException("PROVIDER_AUTHENTICATION_FAILED", false);
        };
        ErrorCode errorCode = exception.getErrorCode();
        return switch (errorCode) {
            case RESOURCE_EXHAUSTED -> new FcmException("PROVIDER_RATE_LIMIT", true);
            case DEADLINE_EXCEEDED -> new FcmException("PROVIDER_TIMEOUT", true);
            case ABORTED, CANCELLED, DATA_LOSS, INTERNAL, UNAVAILABLE, UNKNOWN ->
                    new FcmException("PROVIDER_UNAVAILABLE", true);
            case INVALID_ARGUMENT, NOT_FOUND -> new FcmException("INVALID_DEVICE_TOKEN", false);
            case UNAUTHENTICATED, PERMISSION_DENIED ->
                    new FcmException("PROVIDER_AUTHENTICATION_FAILED", false);
            case ALREADY_EXISTS, CONFLICT, FAILED_PRECONDITION, OUT_OF_RANGE ->
                    new FcmException("FCM_REQUEST_REJECTED", false);
        };
    }
}
