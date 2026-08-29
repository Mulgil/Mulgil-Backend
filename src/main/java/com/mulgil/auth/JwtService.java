package com.mulgil.auth;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public final class JwtService {
    private final MulgilProperties.Jwt properties;
    private final Clock clock;
    private final byte[] secret;

    public JwtService(MulgilProperties properties, Clock clock) {
        this.properties = properties.jwt();
        this.clock = clock;
        try {
            secret = Base64.getDecoder().decode(this.properties.secretBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_HS256_SECRET_BASE64 must be valid Base64", exception);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_HS256_SECRET_BASE64 must decode to at least 32 bytes");
        }
    }

    IssuedAccessToken issue(UUID userId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(properties.accessTtlSeconds());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(properties.issuer())
                .audience(properties.audience())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(properties.keyId()).build(), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign access token", exception);
        }
        return new IssuedAccessToken(jwt.serialize(), expiresAt);
    }

    public UUID verify(String serialized) {
        try {
            SignedJWT jwt = SignedJWT.parse(serialized);
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String subject = claims.getSubject();
            boolean valid = JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    && properties.keyId().equals(jwt.getHeader().getKeyID())
                    && jwt.verify(new MACVerifier(secret))
                    && properties.issuer().equals(claims.getIssuer())
                    && claims.getAudience().contains(properties.audience())
                    && claims.getExpirationTime() != null
                    && claims.getExpirationTime().toInstant().isAfter(clock.instant())
                    && subject != null;
            if (!valid) {
                throw unauthenticated();
            }
            return UUID.fromString(subject);
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            throw unauthenticated();
        }
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication failed.");
    }

    record IssuedAccessToken(String value, Instant expiresAt) {}
}
