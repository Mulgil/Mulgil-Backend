package com.mulgil.auth;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
class AuthService {
    private final GoogleIdTokenVerifierPort googleVerifier;
    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final JwtService jwt;
    private final MulgilProperties.Jwt jwtProperties;
    private final Clock clock;
    private final SecureRandom random;

    AuthService(
            GoogleIdTokenVerifierPort googleVerifier,
            UserRepository users,
            RefreshTokenStore refreshTokens,
            JwtService jwt,
            MulgilProperties properties,
            Clock clock,
            SecureRandom random
    ) {
        this.googleVerifier = googleVerifier;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.jwtProperties = properties.jwt();
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    AuthTokens login(String idToken) {
        User user = users.upsertGoogle(googleVerifier.verify(idToken));
        String refreshToken = newRefreshToken();
        refreshTokens.create(user.id(), UUID.randomUUID(), TokenHash.sha256(refreshToken), refreshExpiresAt());
        return issuePair(user, refreshToken);
    }

    AuthTokens refresh(String presentedToken) {
        String successor = newRefreshToken();
        RefreshTokenStore.Rotation rotation = refreshTokens.rotate(
                TokenHash.sha256(presentedToken), TokenHash.sha256(successor), refreshExpiresAt());
        if (rotation.status() != RefreshTokenStore.Status.SUCCESS) {
            throw unauthenticated();
        }
        return issuePair(rotation.user(), successor);
    }

    void logout(String presentedToken) {
        if (!refreshTokens.revokePresentedFamily(TokenHash.sha256(presentedToken))) {
            throw unauthenticated();
        }
    }

    private AuthTokens issuePair(User user, String refreshToken) {
        JwtService.IssuedAccessToken access = jwt.issue(user.id());
        return new AuthTokens(access.value(), refreshToken, "Bearer", access.expiresAt(), user);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Instant refreshExpiresAt() {
        return clock.instant().plus(jwtProperties.refreshTtlDays(), ChronoUnit.DAYS);
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication failed.");
    }

    record AuthTokens(
            String accessToken,
            String refreshToken,
            String tokenType,
            Instant accessExpiresAt,
            User user
    ) {}
}
