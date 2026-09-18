package com.example.app.security;

import com.example.app.json.Json;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds real keys, real JWKS documents and real signed tokens, so the verifier is tested against
 * the thing it will actually meet rather than a mock. Not named {@code *Test}, so the runner
 * leaves it alone.
 */
final class TokenFixtures {

    static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private TokenFixtures() {}

    static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static KeyPair ec(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    /** A one-key JWKS document, hand-built with the project's own JSON writer. */
    static String jwks(String kid, KeyPair pair) {
        return Json.write(Map.of("keys", java.util.List.of(jwk(kid, pair))));
    }

    static Map<String, Object> jwk(String kid, KeyPair pair) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", kid);
        jwk.put("use", "sig");
        switch (pair.getPublic()) {
            case RSAPublicKey rsa -> {
                jwk.put("kty", "RSA");
                jwk.put("alg", "RS256");
                jwk.put("n", URL.encodeToString(unsigned(rsa.getModulus(), 256)));
                jwk.put("e", URL.encodeToString(unsigned(rsa.getPublicExponent(), 3)));
            }
            case ECPublicKey ecKey -> {
                int size = (ecKey.getParams().getCurve().getField().getFieldSize() + 7) / 8;
                jwk.put("kty", "EC");
                jwk.put("crv", size == 32 ? "P-256" : size == 48 ? "P-384" : "P-521");
                jwk.put("x", URL.encodeToString(unsigned(ecKey.getW().getAffineX(), size)));
                jwk.put("y", URL.encodeToString(unsigned(ecKey.getW().getAffineY(), size)));
            }
            default -> throw new IllegalArgumentException("unsupported key");
        }
        return jwk;
    }

    /** Big-endian, sign byte stripped, left-padded to the fixed width a JWK requires. */
    static byte[] unsigned(BigInteger value, int width) {
        byte[] bytes = value.toByteArray();
        int from = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        int length = bytes.length - from;
        if (length >= width) {
            byte[] exact = new byte[length];
            System.arraycopy(bytes, from, exact, 0, length);
            return exact;
        }
        byte[] padded = new byte[width];
        System.arraycopy(bytes, from, padded, width - length, length);
        return padded;
    }

    static String sign(String kid, String alg, PrivateKey key, Map<String, Object> claims) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("typ", "JWT");
        header.put("kid", kid);
        return sign(header, claims, key, jca(alg));
    }

    static String sign(Map<String, Object> header, Map<String, Object> claims, PrivateKey key, String jca)
            throws Exception {
        String signingInput = segment(header) + "." + segment(claims);
        Signature signature = Signature.getInstance(jca);
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + URL.encodeToString(signature.sign());
    }

    /** An unsigned or forged token: the third segment is whatever the caller says it is. */
    static String forge(Map<String, Object> header, Map<String, Object> claims, String signature) {
        return segment(header) + "." + segment(claims) + "." + signature;
    }

    static String segment(Map<String, Object> value) {
        return URL.encodeToString(Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    static String jca(String alg) {
        return switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            case "ES256" -> "SHA256withECDSAinP1363Format";
            case "ES384" -> "SHA384withECDSAinP1363Format";
            case "ES512" -> "SHA512withECDSAinP1363Format";
            default -> throw new IllegalArgumentException(alg);
        };
    }
}
