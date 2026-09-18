package com.example.app.api;

import com.example.app.web.ApiException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Cursors are opaque to clients (base64url), so the paging scheme can change without breaking them. */
final class Cursors {

    private Cursors() {}

    static String encode(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static UUID decode(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length == 16) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                return new UUID(buffer.getLong(), buffer.getLong());
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid cursor");
    }
}
