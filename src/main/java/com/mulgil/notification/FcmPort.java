package com.mulgil.notification;

public interface FcmPort {
    String send(String deviceToken, Message message);

    record Message(String title, String body, String resourceId, String deepLink) {
        public Message {
            if (title == null || title.isBlank() || body == null || body.isBlank()
                    || resourceId == null || resourceId.isBlank() || deepLink == null || deepLink.isBlank()) {
                throw new IllegalArgumentException("FCM message fields must not be blank.");
            }
        }
    }

    final class FcmException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public FcmException(String code, boolean retryable) {
            super("FCM delivery failed.");
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
