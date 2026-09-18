package com.example.app.security;

/** No usable {@code Authorization: Bearer} header was present. */
public final class MissingCredentialsException extends AuthException {

    private static final long serialVersionUID = 1L;

    public MissingCredentialsException() {
        super("missing_token", "Authentication is required");
    }
}
