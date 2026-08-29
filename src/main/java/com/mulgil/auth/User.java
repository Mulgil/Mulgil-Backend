package com.mulgil.auth;

import java.util.UUID;

public record User(UUID id, String email, String displayName) {}
