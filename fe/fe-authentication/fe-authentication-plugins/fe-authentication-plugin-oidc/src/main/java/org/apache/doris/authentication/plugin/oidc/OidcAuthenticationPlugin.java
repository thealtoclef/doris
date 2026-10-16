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
import org.apache.doris.authentication.BasicPrincipal;
import org.apache.doris.authentication.CredentialType;
import org.apache.doris.authentication.Principal;
import org.apache.doris.authentication.spi.AuthenticationPlugin;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Verifies JWT passwords against one issuer's JWKS and uses {@code subject_claim} verbatim as the username.
 */
public class OidcAuthenticationPlugin implements AuthenticationPlugin {

    private static final Logger LOG = LogManager.getLogger(OidcAuthenticationPlugin.class);

    public static final String PLUGIN_NAME = "oidc";

    // These message prefixes are exposed to clients by fe-core.
    static final String SIGNATURE_FAILED = "OIDC access token signature validation failed";
    static final String USERNAME_MISMATCH = "Authentication request username does not match OIDC token username";

    private static final int HTTP_CONNECT_TIMEOUT_MS = 2000;
    private static final int HTTP_READ_TIMEOUT_MS = 2000;
    private static final int HTTP_SIZE_LIMIT_BYTES = 1024 * 1024;
    private static final long JWKS_REFRESH_TIMEOUT_MS = 15_000;
    private static final Set<String> REQUIRED_CLAIMS = Collections.singleton("exp");

    private final Function<String, String> env;
    private final boolean requireHttpsJwksUri;

    // One plugin instance serves one AuthenticationIntegration.
    private volatile Verifier verifier;

    public OidcAuthenticationPlugin() {
        this(System::getenv, true);
    }

    OidcAuthenticationPlugin(Function<String, String> env, boolean requireHttpsJwksUri) {
        this.env = env;
        this.requireHttpsJwksUri = requireHttpsJwksUri;
    }

    @Override
    public String name() {
        return PLUGIN_NAME;
    }

    @Override
    public String description() {
        return "OIDC authentication plugin - verifies a JWT password against the issuer's JWKS";
    }

    @Override
    public boolean requiresClearPassword() {
        return true;
    }

    /**
     * Routes by unverified issuer and audience; a credential failure stops the integration chain.
     */
    @Override
    public boolean supports(AuthenticationRequest request) {
        if (!isTokenCredentialType(request.getCredentialType())) {
            return false;
        }
        Verifier current = verifier;
        if (current == null) {
            return false;
        }
        String token = credentialAsString(request);
        if (!looksLikeJwt(token)) {
            return false;
        }
        try {
            JWTClaimsSet claims = JWTParser.parse(token).getJWTClaimsSet();
            List<String> audience = claims.getAudience();
            return current.config.issuer.equals(claims.getIssuer())
                    && audience != null && audience.contains(current.config.audience);
        } catch (ParseException e) {
            return false;
        }
    }

    @Override
    public AuthenticationResult authenticate(AuthenticationRequest request, AuthenticationIntegration integration)
            throws AuthenticationException {
        Verifier current = verifier;
        if (current == null) {
            throw new AuthenticationException("OIDC integration '" + integration.getName() + "' is not initialized",
                    AuthenticationFailureType.INTERNAL_ERROR);
        }
        String token = credentialAsString(request);
        if (!looksLikeJwt(token)) {
            return rejected(integration, null, null, "parse", "OIDC token is not a valid JWT");
        }
        JWT jwt;
        try {
            jwt = JWTParser.parse(token);
        } catch (ParseException e) {
            return rejected(integration, null, null, "parse", "OIDC token is not a valid JWT");
        }
        String kid = jwt instanceof SignedJWT ? ((SignedJWT) jwt).getHeader().getKeyID() : null;

        JWTClaimsSet claims;
        try {
            claims = current.processor.process(jwt, null);
        } catch (BadJWSException e) {
            return rejected(integration, kid, null, "signature: " + e.getMessage(), SIGNATURE_FAILED);
        } catch (BadJWTException e) {
            return rejected(integration, kid, null, "claims: " + e.getMessage(), claimsFailureMessage(e));
        } catch (BadJOSEException e) {
            // No key for the kid, an algorithm outside 'algorithms', an unsigned JWT, or a wrong typ.
            return rejected(integration, kid, null, "key or algorithm: " + e.getMessage(),
                    "OIDC token rejected: no matching key, or algorithm not allowed");
        } catch (RateLimitReachedException e) {
            if (current.fetcher == null || !current.fetcher.reachable) {
                throw jwksUnavailable(integration, kid, e);
            }
            // The JWKS was fetched moments ago and lacks this kid.
            return rejected(integration, kid, null, "unknown kid, JWKS refetch rate-limited",
                    "OIDC token rejected: no matching key, or algorithm not allowed");
        } catch (KeySourceException e) {
            throw jwksUnavailable(integration, kid, e);
        } catch (JOSEException e) {
            // The JWKS was read, but its key could not verify the token (e.g. an unusable key).
            LOG.warn("OIDC integration '{}': token verification failed (kid={}): {}", integration.getName(), kid,
                    e.getMessage());
            throw new AuthenticationException("OIDC token could not be verified", e,
                    AuthenticationFailureType.INTERNAL_ERROR);
        }

        Object value = ClaimPath.resolve(claims.getClaims(), current.config.subjectClaim);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            return rejected(integration, kid, null, "claim '" + current.config.subjectClaim + "' missing or empty",
                    "OIDC token is missing claim '" + current.config.subjectClaim + "'");
        }
        String username = (String) value;

        if (!username.equals(request.getUsername())) {
            return rejected(integration, kid, username, "login name '" + request.getUsername() + "' differs",
                    USERNAME_MISMATCH);
        }

        LOG.info("OIDC integration '{}' authenticated '{}' (iss={}, kid={})", integration.getName(), username,
                claims.getIssuer(), kid);
        Principal principal = BasicPrincipal.builder()
                .name(username)
                .authenticator(PLUGIN_NAME)
                .externalPrincipal(claims.getSubject() == null ? username : claims.getSubject())
                .build();
        return AuthenticationResult.success(principal);
    }

    @Override
    public void validate(AuthenticationIntegration integration) throws AuthenticationException {
        OidcConfig config = OidcConfig.parse(integration.getProperties(), requireHttpsJwksUri);
        if (config.jwksEnv != null) {
            parseJwksEnv(config);
        }
    }

    @Override
    public void initialize(AuthenticationIntegration integration) throws AuthenticationException {
        OidcConfig config = OidcConfig.parse(integration.getProperties(), requireHttpsJwksUri);
        Verifier next;
        if (config.jwksEnv != null) {
            next = new Verifier(config, new ImmutableJWKSet<>(parseJwksEnv(config)), null);
        } else {
            TrackedJwkSetSource fetcher = new TrackedJwkSetSource(new URLBasedJWKSetSource<>(config.jwksUri,
                    new DefaultResourceRetriever(HTTP_CONNECT_TIMEOUT_MS, HTTP_READ_TIMEOUT_MS,
                            HTTP_SIZE_LIMIT_BYTES)));
            JWKSource<SecurityContext> keys = JWKSourceBuilder.create(fetcher)
                    .cache(config.jwksCacheTtlSeconds * 1000, JWKS_REFRESH_TIMEOUT_MS)
                    .refreshAheadCache(false)
                    .rateLimited(true)
                    .retrying(false)
                    .build();
            next = new Verifier(config, keys, fetcher);
        }
        Verifier previous = verifier;
        verifier = next;
        closeQuietly(previous);
        LOG.info("OIDC integration '{}' initialized (iss={}, aud={}, algorithms={}, jwks={})",
                integration.getName(), config.issuer, config.audience, config.algorithms,
                config.jwksUri != null ? config.jwksUri : "env " + config.jwksEnv);
    }

    @Override
    public void reload(AuthenticationIntegration integration) throws AuthenticationException {
        initialize(integration);
    }

    @Override
    public void close() {
        Verifier previous = verifier;
        verifier = null;
        closeQuietly(previous);
    }

    private JWKSet parseJwksEnv(OidcConfig config) throws AuthenticationException {
        String json = env.apply(config.jwksEnv);
        if (json == null || json.trim().isEmpty()) {
            throw OidcConfig.misconfigured("environment variable '" + config.jwksEnv + "' is not set");
        }
        try {
            return JWKSet.parse(json);
        } catch (ParseException e) {
            throw OidcConfig.misconfigured("environment variable '" + config.jwksEnv + "' is not a JWKS: "
                    + e.getMessage());
        }
    }

    private static AuthenticationException jwksUnavailable(AuthenticationIntegration integration, String kid,
            KeySourceException e) {
        LOG.warn("OIDC integration '{}': JWKS unavailable (kid={}): {}", integration.getName(), kid, e.getMessage());
        return new AuthenticationException("OIDC token could not be verified: JWKS unavailable", e,
                AuthenticationFailureType.SOURCE_UNAVAILABLE);
    }

    private static AuthenticationResult rejected(AuthenticationIntegration integration, String kid, String username,
            String check, String clientMessage) {
        LOG.info("OIDC integration '{}' rejected token (kid={}, username={}): {}", integration.getName(), kid,
                username, check);
        return AuthenticationResult.failure(AuthenticationFailureType.BAD_CREDENTIAL, clientMessage);
    }

    // Keep token claim values out of client-visible errors.
    private static String claimsFailureMessage(BadJWTException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("Expired JWT")) {
            return "OIDC token expired";
        }
        if (message.startsWith("JWT before use time") || message.startsWith("JWT issued in the future")) {
            return "OIDC token is not yet valid";
        }
        if (message.startsWith("JWT audience rejected") || message.startsWith("JWT missing required audience")) {
            return "OIDC token audience rejected";
        }
        if (message.startsWith("JWT iss claim")) {
            return "OIDC token issuer rejected";
        }
        if (message.startsWith("JWT missing required claims")) {
            return "OIDC token is missing required claims";
        }
        return "OIDC token claims rejected";
    }

    private static boolean isTokenCredentialType(String credentialType) {
        return CredentialType.CLEAR_TEXT_PASSWORD.equalsIgnoreCase(credentialType)
                || CredentialType.JWT_TOKEN.equalsIgnoreCase(credentialType)
                || CredentialType.OAUTH_TOKEN.equalsIgnoreCase(credentialType)
                || CredentialType.OIDC_ID_TOKEN.equalsIgnoreCase(credentialType);
    }

    // Preserve the exact password inspected by fe-core's TLS guard.
    private static String credentialAsString(AuthenticationRequest request) {
        byte[] credential = request.getCredential();
        return credential == null ? "" : new String(credential, StandardCharsets.UTF_8);
    }

    // Must match AuthenticatorManager.looksLikeJwt to preserve TLS enforcement.
    private static boolean looksLikeJwt(String token) {
        if (!token.startsWith("eyJ")) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '.') {
                dots++;
            }
        }
        return dots == 2;
    }

    private static void closeQuietly(Verifier verifier) {
        if (verifier != null && verifier.keys instanceof Closeable) {
            try {
                ((Closeable) verifier.keys).close();
            } catch (IOException e) {
                LOG.debug("Failed to close JWKS source", e);
            }
        }
    }

    /**
     * Tracks issuer fetch outcomes, excluding cache hits, to distinguish outages from unknown keys.
     */
    private static final class TrackedJwkSetSource implements JWKSetSource<SecurityContext> {
        private final JWKSetSource<SecurityContext> delegate;
        volatile boolean reachable;

        TrackedJwkSetSource(JWKSetSource<SecurityContext> delegate) {
            this.delegate = delegate;
        }

        @Override
        public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime,
                SecurityContext context) throws KeySourceException {
            try {
                JWKSet keys = delegate.getJWKSet(refreshEvaluator, currentTime, context);
                reachable = true;
                return keys;
            } catch (KeySourceException e) {
                reachable = false;
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class Verifier {
        final OidcConfig config;
        final JWKSource<SecurityContext> keys;
        // Null when the keys come from the environment.
        final TrackedJwkSetSource fetcher;
        final DefaultJWTProcessor<SecurityContext> processor;

        Verifier(OidcConfig config, JWKSource<SecurityContext> keys, TrackedJwkSetSource fetcher) {
            this.config = config;
            this.keys = keys;
            this.fetcher = fetcher;
            this.processor = new DefaultJWTProcessor<>();
            // RFC 9068 access tokens carry typ "at+jwt".
            processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                    JOSEObjectType.JWT, new JOSEObjectType("application/jwt"), new JOSEObjectType("at+jwt"),
                    new JOSEObjectType("application/at+jwt"), null));
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(config.algorithms, keys));
            ClaimsVerifier claimsVerifier = new ClaimsVerifier(config.audience,
                    new JWTClaimsSet.Builder().issuer(config.issuer).build());
            claimsVerifier.setMaxClockSkew(config.leewaySeconds);
            processor.setJWTClaimsSetVerifier(claimsVerifier);
        }
    }

    /** Nimbus's standard checks, plus a non-null {@code exp} and {@code iat} not in the future when present. */
    private static final class ClaimsVerifier extends DefaultJWTClaimsVerifier<SecurityContext> {
        ClaimsVerifier(String audience, JWTClaimsSet exactMatchClaims) {
            super(audience, exactMatchClaims, REQUIRED_CLAIMS);
        }

        @Override
        public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
            super.verify(claimsSet, context);
            // Nimbus skips expiry validation for null values.
            if (claimsSet.getExpirationTime() == null) {
                throw new BadJWTException("JWT missing required claims: [exp]");
            }
            Date issuedAt = claimsSet.getIssueTime();
            if (issuedAt != null
                    && issuedAt.getTime() > System.currentTimeMillis() + getMaxClockSkew() * 1000L) {
                throw new BadJWTException("JWT issued in the future");
            }
        }
    }

    /**
     * Reads a claim by a dotted path. At each level the longest existing key wins, so keys containing dots
     * resolve: {@code kubernetes.io.serviceaccount.name}.
     */
    static final class ClaimPath {
        private ClaimPath() {
        }

        static Object resolve(Map<String, Object> claims, String path) {
            return resolve(claims, Arrays.asList(path.split("\\.", -1)));
        }

        @SuppressWarnings("unchecked")
        private static Object resolve(Map<String, Object> claims, List<String> parts) {
            for (int end = parts.size(); end > 0; end--) {
                String key = String.join(".", parts.subList(0, end));
                if (!claims.containsKey(key)) {
                    continue;
                }
                Object value = claims.get(key);
                if (end == parts.size()) {
                    return value;
                }
                if (value instanceof Map) {
                    Object nested = resolve((Map<String, Object>) value, parts.subList(end, parts.size()));
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }
    }
}
