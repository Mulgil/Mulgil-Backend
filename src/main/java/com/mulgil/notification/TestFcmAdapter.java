package com.mulgil.notification;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile({"test", "smoke"})
final class TestFcmAdapter implements FcmPort {
    private final List<SentMessage> sent = new ArrayList<>();
    private FcmException nextFailure;

    @Override
    public String send(String deviceToken, Message message) {
        if (nextFailure != null) {
            FcmException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        sent.add(new SentMessage(message));
        return "fake-message-" + sent.size();
    }

    List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    void failNext(String code, boolean retryable) {
        nextFailure = new FcmException(code, retryable);
    }

    void reset() {
        sent.clear();
        nextFailure = null;
    }

    record SentMessage(Message message) {}
}
