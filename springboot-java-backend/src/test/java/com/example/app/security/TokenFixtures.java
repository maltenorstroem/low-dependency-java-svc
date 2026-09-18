package com.example.app.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints real tokens with real keys, rather than mocking the decoder. A mock would assert that the
 * code calls the library; these assert what the service does with a token an attacker could
 * actually construct.
 *
 * <p>Not named {@code *Test}, so it is collected as support rather than as a suite.
 */
public final class TokenFixtures {

    public static final String ISSUER = "https://idp.example.com/realms/demo";
    public static final String AUDIENCE = "task-service";

    private final RSAKey rsa;
    private final ECKey ec;

    public TokenFixtures() throws Exception {
        // A distinct key id per instance, so that two instances model a real rotation: a rotated
        // key that reused the old id would be indistinguishable from a bad signature.
        String generation = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.rsa = new RSAKeyGenerator(2048).keyID("rsa-" + generation).generate();
        this.ec = new ECKeyGenerator(Curve.P_256).keyID("ec-" + generation).generate();
    }

    /** The public half, as the identity provider would publish it. */
    public String jwks() {
        return new JWKSet(List.of(rsa.toPublicJWK(), ec.toPublicJWK())).toString();
    }

    public String jwksWithRsaOnly() {
        return new JWKSet(List.of(rsa.toPublicJWK())).toString();
    }

    public JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("user-1")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    public String signedWithRsa(JWTClaimsSet claims) throws Exception {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsa.getKeyID()).type(JOSEObjectType.JWT).build(), claims, new RSASSASigner(rsa));
    }

    public String signedWithEc(JWTClaimsSet claims) throws Exception {
        return sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(ec.getKeyID()).type(JOSEObjectType.JWT).build(), claims, new ECDSASigner(ec));
    }

    public String withScopes(String scopes) throws Exception {
        return signedWithRsa(claims().claim("scope", scopes).build());
    }

    /** Scopes in the array-valued claim some providers use instead. */
    public String withScpArray(List<String> scopes) throws Exception {
        return signedWithRsa(claims().claim("scp", scopes).build());
    }

    /** An unsecured token: the signature is simply absent. */
    public String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /**
     * The key-confusion attack: the RSA public key, which anyone can fetch, used as an HMAC secret.
     * A verifier that takes the algorithm from the header rather than from the key accepts it.
     */
    public String signedWithPublicKeyAsHmacSecret(JWTClaimsSet claims) throws Exception {
        byte[] secret = rsa.toPublicJWK().toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] padded = new byte[Math.max(64, secret.length)];
        System.arraycopy(secret, 0, padded, 0, secret.length);
        return sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(rsa.getKeyID()).build(),
                claims, new MACSigner(padded));
    }

    /** Signed with a key the identity provider never published. */
    public String signedWithAnUnknownKey(JWTClaimsSet claims) throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("rsa-unknown").generate();
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(other.getKeyID()).type(JOSEObjectType.JWT).build(), claims, new RSASSASigner(other));
    }

    /** A valid token whose payload has been edited after signing. */
    public String tampered(String token) {
        String[] parts = token.split("\\.");
        var decoder = java.util.Base64.getUrlDecoder();
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String payload = new String(decoder.decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        String edited = payload.replace("\"user-1\"", "\"admin\"");
        return parts[0] + "." + encoder.encodeToString(
                edited.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
    }

    private static String sign(JWSHeader header, JWTClaimsSet claims, com.nimbusds.jose.JWSSigner signer)
            throws Exception {
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(signer);
        return jwt.serialize();
    }
}
