package com.mulgil.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Component
@Profile("!test & !smoke")
final class GoogleIdTokenAdapter implements GoogleIdTokenVerifierPort {
    private final GoogleIdTokenVerifier verifier;

    GoogleIdTokenAdapter(MulgilProperties properties) {
        verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(List.of(properties.google().oauthClientId()))
                .build();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        try {
            GoogleIdToken verified = verifier.verify(idToken);
            if (verified == null) {
                throw unauthenticated();
            }
            GoogleIdToken.Payload payload = verified.getPayload();
            String subject = payload.getSubject();
            String email = payload.getEmail();
            String displayName = (String) payload.get("name");
            if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
                throw unauthenticated();
            }
            return new GoogleIdentity(subject, email,
                    displayName == null || displayName.isBlank() ? email : displayName);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            throw unauthenticated();
        }
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication failed.");
    }
}
