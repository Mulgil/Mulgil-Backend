package com.mulgil.notification;

public interface FcmPort {
    String send(String deviceToken, Message message);

    record Message(String title, String body, String resourceId, String deepLink) {}
}
