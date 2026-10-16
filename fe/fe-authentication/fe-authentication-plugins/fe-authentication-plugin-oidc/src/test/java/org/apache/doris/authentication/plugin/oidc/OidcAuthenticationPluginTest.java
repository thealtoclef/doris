// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.authentication.plugin.oidc;

import org.apache.doris.authentication.AuthenticationException;
import org.apache.doris.authentication.AuthenticationFailureType;
import org.apache.doris.authentication.AuthenticationIntegration;
import org.apache.doris.authentication.AuthenticationRequest;
import org.apache.doris.authentication.AuthenticationResult;
import org.apache.doris.authentication.CredentialType;
import org.apache.doris.authentication.spi.AuthenticationPluginFactory;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class OidcAuthenticationPluginTest {

    private static final String ISSUER = "https://issuer.example.com";
    private static final String AUDIENCE = "doris";
    private static final String K8S_SUB = "system:serviceaccount:analytics:dbt-runner";

    private HttpServer server;
    private final AtomicInteger jwksFetches = new AtomicInteger();
    private final AtomicBoolean jwksDown = new AtomicBoolean();
    private final AtomicReference<String> jwksJson = new AtomicReference<>();
    private String jwksUri;
    private RSAKey rsaKey;
    private final Map<String, String> env = new HashMap<>();
    private OidcAuthenticationPlugin plugin;

    @BeforeEach
    public void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
        publish(rsaKey);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/keys", exchange -> {
            jwksFetches.incrementAndGet();
            if (jwksDown.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = jwksJson.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        jwksUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/keys";
        plugin = new OidcAuthenticationPlugin(env::get, false);
    }

    @AfterEach
    public void tearDown() {
        plugin.close();
        server.stop(0);
    }

    @Test
    public void testValidTokenLogsInWithSubVerbatim() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB).build());

        AuthenticationResult result = plugin.authenticate(request(K8S_SUB, token), integration);

        Assertions.assertTrue(result.isSuccess(), String.valueOf(result.getException()));
        Assertions.assertEquals(K8S_SUB, result.getPrincipal().getName());
        Assertions.assertTrue(result.getPrincipal().getExternalGroups().isEmpty());
    }

    @Test
    public void testSubjectClaimWithAtSignIsNotLowercased() throws Exception {
        Map<String, String> props = props();
        props.put(OidcConfig.SUBJECT_CLAIM, "email");
        AuthenticationIntegration integration = init(props);
        String token = sign(rsaKey, claims().subject("opaque-id").claim("email", "User@example.com").build());

        AuthenticationResult result = plugin.authenticate(request("User@example.com", token), integration);

        Assertions.assertTrue(result.isSuccess(), String.valueOf(result.getException()));
        Assertions.assertEquals("User@example.com", result.getPrincipal().getName());
        assertRejected(plugin.authenticate(request("user@example.com", token), integration),
                OidcAuthenticationPlugin.USERNAME_MISMATCH);
    }

    @Test
    public void testNestedSubjectClaimWithDottedKey() throws Exception {
        Map<String, String> props = props();
        props.put(OidcConfig.SUBJECT_CLAIM, "kubernetes.io.serviceaccount.name");
        AuthenticationIntegration integration = init(props);
        Map<String, Object> serviceAccount = new LinkedHashMap<>();
        serviceAccount.put("name", "dbt-runner");
        Map<String, Object> kubernetes = new LinkedHashMap<>();
        kubernetes.put("namespace", "analytics");
        kubernetes.put("serviceaccount", serviceAccount);
        String token = sign(rsaKey, claims().subject(K8S_SUB).claim("kubernetes.io", kubernetes).build());

        AuthenticationResult result = plugin.authenticate(request("dbt-runner", token), integration);

        Assertions.assertTrue(result.isSuccess(), String.valueOf(result.getException()));
        Assertions.assertEquals("dbt-runner", result.getPrincipal().getName());
    }

    @Test
    public void testTypAtJwtAndEcKeysFromEnv() throws Exception {
        ECKey ecKey = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
        env.put("APP_JWKS", new JWKSet(ecKey.toPublicJWK()).toString());
        Map<String, String> props = props();
        props.remove(OidcConfig.JWKS_URI);
        props.put(OidcConfig.JWKS_ENV, "APP_JWKS");
        props.put(OidcConfig.ALGORITHMS, "RS256, ES256");
        AuthenticationIntegration integration = init(props);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec-1")
                .type(new JOSEObjectType("at+jwt")).build(), claims().subject("user@example.com").build());
        jwt.sign(new ECDSASigner(ecKey));

        AuthenticationResult result = plugin.authenticate(request("user@example.com", jwt.serialize()), integration);

        Assertions.assertTrue(result.isSuccess(), String.valueOf(result.getException()));
        Assertions.assertEquals(0, jwksFetches.get());
    }

    @Test
    public void testWithinLeewayPasses() throws Exception {
        AuthenticationIntegration integration = init(props());
        long now = System.currentTimeMillis();
        String token = sign(rsaKey, claims().subject(K8S_SUB)
                .expirationTime(new Date(now - 30_000))
                .notBeforeTime(new Date(now + 30_000))
                .issueTime(new Date(now + 30_000)).build());

        Assertions.assertTrue(plugin.authenticate(request(K8S_SUB, token), integration).isSuccess());
    }

    @Test
    public void testExpiredTokenRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB)
                .expirationTime(new Date(System.currentTimeMillis() - 120_000)).build());
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration), "OIDC token expired");
    }

    @Test
    public void testTokenWithoutExpRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE)
                .subject(K8S_SUB).build());
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration),
                "OIDC token is missing required claims");
    }

    @Test
    public void testNullExpRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        long now = System.currentTimeMillis() / 1000;
        JWSObject jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                new Payload("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + K8S_SUB
                        + "\",\"iat\":" + now + ",\"exp\":null}"));
        jws.sign(new RSASSASigner(rsaKey));
        assertRejected(plugin.authenticate(request(K8S_SUB, jws.serialize()), integration),
                "OIDC token is missing required claims");
    }

    @Test
    public void testNotYetValidTokenRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB)
                .notBeforeTime(new Date(System.currentTimeMillis() + 120_000)).build());
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration), "OIDC token is not yet valid");
    }

    @Test
    public void testIssuedInTheFutureRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB)
                .issueTime(new Date(System.currentTimeMillis() + 120_000)).build());
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration), "OIDC token is not yet valid");
    }

    @Test
    public void testWrongIssuerRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().issuer("https://other.example.com").subject(K8S_SUB).build());
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, token)));
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration), "OIDC token issuer rejected");
    }

    @Test
    public void testWrongAudienceRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().audience("kubernetes").subject(K8S_SUB).build());
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, token)));
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration), "OIDC token audience rejected");
    }

    @Test
    public void testIntegrationsSharingAnIssuerRouteByAudience() throws Exception {
        AuthenticationIntegration app1 = init(props());
        Map<String, String> app2Props = props();
        app2Props.put(OidcConfig.AUDIENCE, "app2");
        OidcAuthenticationPlugin app2Plugin = new OidcAuthenticationPlugin(env::get, false);
        AuthenticationIntegration app2 = integration(app2Props);
        app2Plugin.initialize(app2);
        try {
            String forApp2 = sign(rsaKey, claims().audience(Arrays.asList("app2", "other")).subject(K8S_SUB).build());
            Assertions.assertFalse(plugin.supports(request(K8S_SUB, forApp2)));
            Assertions.assertTrue(app2Plugin.supports(request(K8S_SUB, forApp2)));
            Assertions.assertTrue(app2Plugin.authenticate(request(K8S_SUB, forApp2), app2).isSuccess());

            String forApp1 = sign(rsaKey, claims().subject(K8S_SUB).build());
            Assertions.assertTrue(plugin.supports(request(K8S_SUB, forApp1)));
            Assertions.assertFalse(app2Plugin.supports(request(K8S_SUB, forApp1)));
            Assertions.assertTrue(plugin.authenticate(request(K8S_SUB, forApp1), app1).isSuccess());
        } finally {
            app2Plugin.close();
        }
    }

    @Test
    public void testUnknownKidRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        RSAKey other = new RSAKeyGenerator(2048).keyID("unknown").generate();
        String token = sign(other, claims().subject(K8S_SUB).build());
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration),
                "OIDC token rejected: no matching key, or algorithm not allowed");
    }

    @Test
    public void testBadSignatureRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        // Same kid as the published key, signed with a different private key.
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
        String token = sign(impostor, claims().subject(K8S_SUB).build());
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration),
                OidcAuthenticationPlugin.SIGNATURE_FAILED);
    }

    @Test
    public void testAlgorithmOutsideListRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        ECKey ecKey = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
        publish(rsaKey, ecKey);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec-1").build(),
                claims().subject(K8S_SUB).build());
        jwt.sign(new ECDSASigner(ecKey));
        assertRejected(plugin.authenticate(request(K8S_SUB, jwt.serialize()), integration),
                "OIDC token rejected: no matching key, or algorithm not allowed");
    }

    @Test
    public void testAlgNoneRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = new PlainJWT(claims().subject(K8S_SUB).build()).serialize();
        assertRejected(plugin.authenticate(request(K8S_SUB, token), integration),
                "OIDC token rejected: no matching key, or algorithm not allowed");
    }

    @Test
    public void testHs256SignedWithPublicKeyRejected() throws Exception {
        Map<String, String> props = props();
        props.put(OidcConfig.ALGORITHMS, "RS256,HS256");
        AuthenticationIntegration integration = init(props);
        byte[] publicKeyAsSecret = rsaKey.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("rsa-1").build(),
                claims().subject(K8S_SUB).build());
        jwt.sign(new MACSigner(publicKeyAsSecret));
        assertRejected(plugin.authenticate(request(K8S_SUB, jwt.serialize()), integration),
                "OIDC token rejected: no matching key, or algorithm not allowed");
    }

    @Test
    public void testMissingOrEmptySubjectClaimRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String missing = sign(rsaKey, claims().build());
        assertRejected(plugin.authenticate(request(K8S_SUB, missing), integration),
                "OIDC token is missing claim 'sub'");
        String empty = sign(rsaKey, claims().subject("").build());
        assertRejected(plugin.authenticate(request(K8S_SUB, empty), integration),
                "OIDC token is missing claim 'sub'");
    }

    @Test
    public void testLoginNameMismatchRejected() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB).build());
        assertRejected(plugin.authenticate(request("etl", token), integration),
                OidcAuthenticationPlugin.USERNAME_MISMATCH);
    }

    @Test
    public void testTokenFeCoreTlsGuardWouldMissIsNotSupported() throws Exception {
        init(props());
        String header = "{\n\"alg\":\"RS256\",\"kid\":\"" + rsaKey.getKeyID() + "\"}";
        JWSObject jws = new JWSObject(JWSHeader.parse(Base64URL.encode(header)),
                new Payload(claims().subject(K8S_SUB).build().toJSONObject()));
        jws.sign(new RSASSASigner(rsaKey));
        String token = jws.serialize();
        Assertions.assertFalse(token.startsWith("eyJ"));
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, token)));
    }

    @Test
    public void testPaddedTokenFeCoreTlsGuardWouldMissIsRefused() throws Exception {
        AuthenticationIntegration integration = init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB).build());
        for (String padded : Arrays.asList(" " + token, "\t" + token, "\n" + token)) {
            Assertions.assertFalse(plugin.supports(request(K8S_SUB, padded)));
            assertRejected(plugin.authenticate(request(K8S_SUB, padded), integration),
                    "OIDC token is not a valid JWT");
        }
    }

    @Test
    public void testSupportsOnlyJwtsFromItsIssuer() throws Exception {
        init(props());
        String token = sign(rsaKey, claims().subject(K8S_SUB).build());
        Assertions.assertTrue(plugin.supports(request(K8S_SUB, token)));
        Assertions.assertTrue(plugin.supports(request(K8S_SUB, token, CredentialType.OAUTH_TOKEN)));
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, token, CredentialType.MYSQL_NATIVE_PASSWORD)));
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, "a plain password")));
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, "eyJnot.a.jwt")));
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, "")));
    }

    @Test
    public void testSupportsIsFalseBeforeInitialize() throws Exception {
        String token = sign(rsaKey, claims().subject(K8S_SUB).build());
        Assertions.assertFalse(plugin.supports(request(K8S_SUB, token)));
    }

    @Test
    public void testUnknownKidRefetchesOnceThenRateLimited() throws Exception {
        AuthenticationIntegration integration = init(props());
        Assertions.assertTrue(plugin.authenticate(request(K8S_SUB,
                sign(rsaKey, claims().subject(K8S_SUB).build())), integration).isSuccess());
        Assertions.assertEquals(1, jwksFetches.get());

        // The issuer rotates to a new key: the unknown kid triggers one refetch, which finds it.
        RSAKey rotated = new RSAKeyGenerator(2048).keyID("rsa-2").generate();
        publish(rsaKey, rotated);
        AuthenticationResult result = plugin.authenticate(request(K8S_SUB,
                sign(rotated, claims().subject(K8S_SUB).build())), integration);
        Assertions.assertTrue(result.isSuccess(), String.valueOf(result.getException()));
        Assertions.assertEquals(2, jwksFetches.get());

        // Known kids are served from the cache.
        Assertions.assertTrue(plugin.authenticate(request(K8S_SUB,
                sign(rotated, claims().subject(K8S_SUB).build())), integration).isSuccess());
        Assertions.assertEquals(2, jwksFetches.get());

        // A burst of unknown kids does not turn into a burst of fetches.
        for (int i = 0; i < 5; i++) {
            RSAKey unknown = new RSAKeyGenerator(2048).keyID("unknown-" + i).generate();
            assertRejected(plugin.authenticate(request(K8S_SUB,
                    sign(unknown, claims().subject(K8S_SUB).build())), integration),
                    "OIDC token rejected: no matching key, or algorithm not allowed");
        }
        Assertions.assertTrue(jwksFetches.get() <= 3, "fetches: " + jwksFetches.get());
    }

    @Test
    public void testUnreachableJwksIsSourceUnavailable() throws Exception {
        Map<String, String> props = props();
        props.put(OidcConfig.JWKS_URI, "http://127.0.0.1:" + server.getAddress().getPort() + "/missing");
        AuthenticationIntegration integration = init(props);
        String token = sign(rsaKey, claims().subject(K8S_SUB).build());

        // Also once refetches are rate-limited: an outage is not an unknown kid.
        for (int i = 0; i < 4; i++) {
            AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                    () -> plugin.authenticate(request(K8S_SUB, token), integration));
            Assertions.assertEquals(AuthenticationFailureType.SOURCE_UNAVAILABLE, e.getFailureType());
            Assertions.assertTrue(e.getFailureType().shouldContinueInChain());
        }
    }

    @Test
    public void testCachedKeyHitDoesNotMaskOutage() throws Exception {
        AuthenticationIntegration integration = init(props());
        String oldKeyToken = sign(rsaKey, claims().subject(K8S_SUB).build());
        Assertions.assertTrue(plugin.authenticate(request(K8S_SUB, oldKeyToken), integration).isSuccess());

        jwksDown.set(true);
        String newKeyToken = sign(new RSAKeyGenerator(2048).keyID("rsa-2").generate(),
                claims().subject(K8S_SUB).build());
        for (int i = 0; i < 2; i++) {
            AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                    () -> plugin.authenticate(request(K8S_SUB, newKeyToken), integration));
            Assertions.assertEquals(AuthenticationFailureType.SOURCE_UNAVAILABLE, e.getFailureType());
            // A cache hit says nothing about whether the issuer is reachable.
            Assertions.assertTrue(plugin.authenticate(request(K8S_SUB, oldKeyToken), integration).isSuccess());
        }
    }

    @Test
    public void testReloadPicksUpRotatedEnvKey() throws Exception {
        env.put("JWKS", new JWKSet(rsaKey.toPublicJWK()).toString());
        Map<String, String> props = props();
        props.remove(OidcConfig.JWKS_URI);
        props.put(OidcConfig.JWKS_ENV, "JWKS");
        AuthenticationIntegration integration = init(props);
        RSAKey rotated = new RSAKeyGenerator(2048).keyID("rsa-2").generate();
        String token = sign(rotated, claims().subject(K8S_SUB).build());
        Assertions.assertFalse(plugin.authenticate(request(K8S_SUB, token), integration).isSuccess());

        env.put("JWKS", new JWKSet(rotated.toPublicJWK()).toString());
        plugin.reload(integration);

        Assertions.assertTrue(plugin.authenticate(request(K8S_SUB, token), integration).isSuccess());
    }

    @Test
    public void testValidateRejectsBadConfiguration() {
        assertMisconfigured(p -> p.remove(OidcConfig.ISSUER));
        assertMisconfigured(p -> p.remove(OidcConfig.AUDIENCE));
        assertMisconfigured(p -> p.remove(OidcConfig.ALGORITHMS));
        assertMisconfigured(p -> p.put(OidcConfig.ALGORITHMS, " , "));
        assertMisconfigured(p -> p.put(OidcConfig.ALGORITHMS, "none"));
        assertMisconfigured(p -> p.put(OidcConfig.ALGORITHMS, "RS256,XYZ"));
        assertMisconfigured(p -> p.put(OidcConfig.JWKS_ENV, "JWKS"));
        assertMisconfigured(p -> p.remove(OidcConfig.JWKS_URI));
        assertMisconfigured(p -> p.put(OidcConfig.JWKS_URI, "not a url"));
        assertMisconfigured(p -> p.put(OidcConfig.JWKS_CACHE_TTL, "soon"));
        assertMisconfigured(p -> p.put(OidcConfig.JWKS_CACHE_TTL, "0"));
        assertMisconfigured(p -> p.put(OidcConfig.JWKS_CACHE_TTL, "30"));
        assertMisconfigured(p -> p.put(OidcConfig.JWKS_CACHE_TTL, String.valueOf(Long.MAX_VALUE)));
        assertMisconfigured(p -> p.put(OidcConfig.ALGORITHMS, "ES256K"));
        assertMisconfigured(p -> p.put(OidcConfig.LEEWAY, "-1"));
        assertMisconfigured(p -> {
            p.remove(OidcConfig.JWKS_URI);
            p.put(OidcConfig.JWKS_ENV, "UNSET");
        });
        env.put("BROKEN", "{not json");
        assertMisconfigured(p -> {
            p.remove(OidcConfig.JWKS_URI);
            p.put(OidcConfig.JWKS_ENV, "BROKEN");
        });
    }

    @Test
    public void testHttpJwksUriRejectedByDefault() {
        OidcAuthenticationPlugin strict = new OidcAuthenticationPlugin();
        AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                () -> strict.validate(integration(props())));
        Assertions.assertEquals(AuthenticationFailureType.MISCONFIGURED, e.getFailureType());
    }

    @Test
    public void testFactoryIsDiscoveredAsOidc() {
        boolean found = false;
        for (AuthenticationPluginFactory factory : ServiceLoader.load(AuthenticationPluginFactory.class)) {
            if (factory instanceof OidcAuthenticationPluginFactory) {
                found = true;
                Assertions.assertEquals("oidc", factory.name());
                Assertions.assertEquals("oidc", factory.create().name());
            }
        }
        Assertions.assertTrue(found);
    }

    private void publish(JWK... keys) {
        JWK[] publicKeys = new JWK[keys.length];
        for (int i = 0; i < keys.length; i++) {
            publicKeys[i] = keys[i].toPublicJWK();
        }
        jwksJson.set(new JWKSet(Arrays.asList(publicKeys)).toString());
    }

    private Map<String, String> props() {
        Map<String, String> props = new HashMap<>();
        props.put(OidcConfig.ISSUER, ISSUER);
        props.put(OidcConfig.AUDIENCE, AUDIENCE);
        props.put(OidcConfig.ALGORITHMS, "RS256");
        props.put(OidcConfig.JWKS_URI, jwksUri);
        return props;
    }

    private static AuthenticationIntegration integration(Map<String, String> props) {
        return AuthenticationIntegration.builder().name("test_oidc").type("oidc").properties(props).build();
    }

    private AuthenticationIntegration init(Map<String, String> props) throws AuthenticationException {
        AuthenticationIntegration integration = integration(props);
        plugin.validate(integration);
        plugin.initialize(integration);
        return integration;
    }

    private void assertMisconfigured(Consumer<Map<String, String>> change) {
        Map<String, String> props = props();
        change.accept(props);
        AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                () -> plugin.validate(integration(props)), props.toString());
        Assertions.assertEquals(AuthenticationFailureType.MISCONFIGURED, e.getFailureType());
    }

    private static JWTClaimsSet.Builder claims() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(Collections.singletonList(AUDIENCE))
                .issueTime(new Date(now))
                .expirationTime(new Date(now + 300_000));
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        JWSSigner signer = new RSASSASigner(key);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static AuthenticationRequest request(String username, String token) {
        return request(username, token, CredentialType.CLEAR_TEXT_PASSWORD);
    }

    private static AuthenticationRequest request(String username, String token, String credentialType) {
        return AuthenticationRequest.builder()
                .username(username)
                .credentialType(credentialType)
                .credential(token.getBytes(StandardCharsets.UTF_8))
                .remoteHost("127.0.0.1")
                .build();
    }

    private static void assertRejected(AuthenticationResult result, String message) {
        Assertions.assertTrue(result.isFailure(), "expected a failure");
        Assertions.assertEquals(AuthenticationFailureType.BAD_CREDENTIAL, result.getException().getFailureType());
        Assertions.assertEquals(message, result.getException().getMessage());
    }
}
