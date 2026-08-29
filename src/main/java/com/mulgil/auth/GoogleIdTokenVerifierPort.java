package com.mulgil.auth;

public interface GoogleIdTokenVerifierPort {
    GoogleIdentity verify(String idToken);
}
