package com.mulgil.auth;

public record GoogleIdentity(String subject, String email, String displayName) {}
