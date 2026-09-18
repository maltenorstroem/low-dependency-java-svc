package com.example.app.security;

import com.example.app.json.Json;
import com.example.app.json.JsonException;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsing of a JSON Web Key Set (RFC 7517) into verification keys.
 *
 * <p>Unusable entries are skipped rather than fatal, so one exotic or future key type cannot take
 * a whole key set — and with it the whole service — down. What is never skipped is a weak key: an
 * RSA modulus under 2048 bits is dropped, as is an EC coordinate of the wrong length for its curve.
 *
 * <p>No {@code oct} (symmetric) key can ever enter the result. That is the second, structural half
 * of the algorithm-confusion defence: even if an HMAC algorithm somehow reached the verifier,
 * there would be no key for it to use.
 */
public final class Jwks {

    /** A key set larger than this is a configuration mistake or an attack; the tail is ignored. */
    private static final int MAX_KEYS = 32;

    private static final int MIN_RSA_MODULUS_BITS = 2048;

    private record Curve(String jwsAlgorithm, String jcaName, int coordinateBytes) {}

    private static final Map<String, Curve> CURVES = Map.of(
            "P-256", new Curve("ES256", "secp256r1", 32),
            "P-384", new Curve("ES384", "secp384r1", 48),
            "P-521", new Curve("ES512", "secp521r1", 66));

    private static final Base64.Decoder URL = Base64.getUrlDecoder();

    private Jwks() {}

    /** @return usable keys by {@code kid}; a key without a {@code kid} is stored under "". */
    public static Map<String, VerificationKey> parse(String document) {
        Object parsed;
        try {
            parsed = Json.parse(document);
        } catch (JsonException e) {
            throw new IllegalArgumentException("JWKS is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("keys") instanceof List<?> keys)) {
            throw new IllegalArgumentException("JWKS has no 'keys' array");
        }
        Map<String, VerificationKey> result = new LinkedHashMap<>();
        for (Object element : keys) {
            if (result.size() >= MAX_KEYS) {
                break;
            }
            if (element instanceof Map<?, ?> jwk) {
                VerificationKey key = toKey(jwk);
                if (key != null) {
                    result.putIfAbsent(key.kid() == null ? "" : key.kid(), key);
                }
            }
        }
        return Map.copyOf(result);
    }

    /** @return null when the entry is not a signing key we can use. */
    private static VerificationKey toKey(Map<?, ?> jwk) {
        if (!isForSignatureVerification(jwk)) {
            return null;
        }
        String kid = string(jwk, "kid");
        String declared = string(jwk, "alg");
        try {
            return switch (string(jwk, "kty")) {
                case "RSA" -> rsa(jwk, kid, declared);
                case "EC" -> ec(jwk, kid, declared);
                case null, default -> null; // oct, OKP and anything newer: not ours to verify
            };
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null; // a malformed key is skipped, exactly like an unknown one
        }
    }

    private static VerificationKey rsa(Map<?, ?> jwk, String kid, String declared)
            throws GeneralSecurityException {
        BigInteger modulus = unsignedInt(jwk, "n");
        BigInteger exponent = unsignedInt(jwk, "e");
        if (modulus.bitLength() < MIN_RSA_MODULUS_BITS) {
            return null;
        }
        String algorithm = declared == null ? "RS256" : declared;
        if (!algorithm.startsWith("RS") && !algorithm.startsWith("PS")) {
            return null; // an RSA key claiming an EC or HMAC algorithm is not one we will use
        }
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        return new VerificationKey(kid, algorithm, key);
    }

    private static VerificationKey ec(Map<?, ?> jwk, String kid, String declared)
            throws GeneralSecurityException {
        Curve curve = CURVES.get(string(jwk, "crv"));
        if (curve == null || (declared != null && !declared.equals(curve.jwsAlgorithm()))) {
            return null; // the algorithm is pinned one-to-one to the curve
        }
        // Coordinates are fixed-width for a curve (RFC 7518 section 6.2.1.2); a short or long
        // encoding means the key is not what it claims to be.
        byte[] x = decode(jwk, "x");
        byte[] y = decode(jwk, "y");
        if (x.length != curve.coordinateBytes() || y.length != curve.coordinateBytes()) {
            return null;
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curve.jcaName()));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        // generatePublic rejects a point that is not on the curve.
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
        return new VerificationKey(kid, curve.jwsAlgorithm(), key);
    }

    private static boolean isForSignatureVerification(Map<?, ?> jwk) {
        String use = string(jwk, "use");
        if (use != null && !use.equals("sig")) {
            return false;
        }
        return !(jwk.get("key_ops") instanceof List<?> ops) || ops.contains("verify");
    }

    private static String string(Map<?, ?> jwk, String name) {
        return jwk.get(name) instanceof String value ? value : null;
    }

    private static BigInteger unsignedInt(Map<?, ?> jwk, String name) {
        return new BigInteger(1, decode(jwk, name));
    }

    private static byte[] decode(Map<?, ?> jwk, String name) {
        String value = string(jwk, name);
        if (value == null) {
            throw new IllegalArgumentException("JWK member '" + name + "' is missing");
        }
        return URL.decode(value);
    }
}
