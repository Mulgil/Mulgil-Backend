package com.mulgil.auth;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@Profile({"test", "smoke"})
final class FakeGoogleIdTokenVerifier implements GoogleIdTokenVerifierPort {
    static final String ISSUER = "https://accounts.google.com";

    private final MulgilProperties properties;
    private final Clock clock;

    FakeGoogleIdTokenVerifier(MulgilProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        String[] fields = idToken.split("\\|", -1);
        if (fields.length != 7
                || !"fake".equals(fields[0])
                || !ISSUER.equals(fields[1])
                || !properties.google().oauthClientId().equals(fields[2])) {
            throw unauthenticated();
        }
        try {
            if (fields[3].isBlank() || Long.parseLong(fields[6]) <= clock.instant().getEpochSecond()) {
                throw unauthenticated();
            }
            return new GoogleIdentity(fields[3], fields[4], fields[5]);
        } catch (NumberFormatException exception) {
            throw unauthenticated();
        }
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication failed.");
    }
}
