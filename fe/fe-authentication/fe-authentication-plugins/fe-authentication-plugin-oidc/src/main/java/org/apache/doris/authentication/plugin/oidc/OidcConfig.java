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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Validated properties for one OIDC authentication integration. */
final class OidcConfig {

    static final String ISSUER = "issuer";
    static final String AUDIENCE = "audience";
    static final String ALGORITHMS = "algorithms";
    static final String JWKS_URI = "jwks_uri";
    static final String JWKS_ENV = "jwks_env";
    static final String JWKS_CACHE_TTL = "jwks_cache_ttl";
    static final String SUBJECT_CLAIM = "subject_claim";
    static final String LEEWAY = "leeway";

    static final long DEFAULT_JWKS_CACHE_TTL_SECONDS = 3600;
    static final long MIN_JWKS_CACHE_TTL_SECONDS = JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL / 1000;
    static final String DEFAULT_SUBJECT_CLAIM = "sub";
    static final int DEFAULT_LEEWAY_SECONDS = 60;

    private static final Set<JWSAlgorithm> KNOWN_ALGORITHMS;

    static {
        Set<JWSAlgorithm> known = new LinkedHashSet<>();
        known.addAll(JWSAlgorithm.Family.RSA);
        known.addAll(JWSAlgorithm.Family.EC);
        // The default JDK 17 provider does not support secp256k1.
        known.remove(JWSAlgorithm.ES256K);
        known.addAll(JWSAlgorithm.Family.HMAC_SHA);
        // EdDSA requires Google Tink, which is not bundled.
        KNOWN_ALGORITHMS = Collections.unmodifiableSet(known);
    }

    final String issuer;
    final String audience;
    final Set<JWSAlgorithm> algorithms;
    // Exactly one of jwksUri and jwksEnv is set.
    final URL jwksUri;
    final String jwksEnv;
    final long jwksCacheTtlSeconds;
    final String subjectClaim;
    final int leewaySeconds;

    private OidcConfig(String issuer, String audience, Set<JWSAlgorithm> algorithms, URL jwksUri, String jwksEnv,
            long jwksCacheTtlSeconds, String subjectClaim, int leewaySeconds) {
        this.issuer = issuer;
        this.audience = audience;
        this.algorithms = algorithms;
        this.jwksUri = jwksUri;
        this.jwksEnv = jwksEnv;
        this.jwksCacheTtlSeconds = jwksCacheTtlSeconds;
        this.subjectClaim = subjectClaim;
        this.leewaySeconds = leewaySeconds;
    }

    static OidcConfig parse(Map<String, String> properties, boolean requireHttpsJwksUri)
            throws AuthenticationException {
        String issuer = required(properties, ISSUER);
        String audience = required(properties, AUDIENCE);
        Set<JWSAlgorithm> algorithms = parseAlgorithms(required(properties, ALGORITHMS));

        String jwksUriValue = optional(properties, JWKS_URI);
        String jwksEnv = optional(properties, JWKS_ENV);
        if ((jwksUriValue == null) == (jwksEnv == null)) {
            throw misconfigured("exactly one of '" + JWKS_URI + "' and '" + JWKS_ENV + "' is required");
        }
        URL jwksUri = null;
        if (jwksUriValue != null) {
            try {
                jwksUri = new URL(jwksUriValue);
            } catch (MalformedURLException e) {
                throw misconfigured("'" + JWKS_URI + "' is not a URL: " + jwksUriValue);
            }
            if (requireHttpsJwksUri && !"https".equalsIgnoreCase(jwksUri.getProtocol())) {
                throw misconfigured("'" + JWKS_URI + "' must use https: " + jwksUriValue);
            }
        }

        long jwksCacheTtlSeconds = nonNegative(properties, JWKS_CACHE_TTL, DEFAULT_JWKS_CACHE_TTL_SECONDS);
        // Nimbus requires a cache TTL longer than the refresh rate-limit interval.
        if (jwksCacheTtlSeconds <= MIN_JWKS_CACHE_TTL_SECONDS || jwksCacheTtlSeconds > Long.MAX_VALUE / 1000) {
            throw misconfigured("'" + JWKS_CACHE_TTL + "' must be greater than " + MIN_JWKS_CACHE_TTL_SECONDS
                    + " seconds: " + jwksCacheTtlSeconds);
        }
        String subjectClaim = optional(properties, SUBJECT_CLAIM);
        long leewaySeconds = nonNegative(properties, LEEWAY, DEFAULT_LEEWAY_SECONDS);
        if (leewaySeconds > Integer.MAX_VALUE) {
            throw misconfigured("'" + LEEWAY + "' is too large: " + leewaySeconds);
        }
        return new OidcConfig(issuer, audience, algorithms, jwksUri, jwksEnv, jwksCacheTtlSeconds,
                subjectClaim == null ? DEFAULT_SUBJECT_CLAIM : subjectClaim, (int) leewaySeconds);
    }

    private static Set<JWSAlgorithm> parseAlgorithms(String value) throws AuthenticationException {
        Set<JWSAlgorithm> algorithms = new LinkedHashSet<>();
        for (String name : value.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JWSAlgorithm algorithm = JWSAlgorithm.parse(trimmed);
            // JWSAlgorithm.parse accepts unknown names.
            if (!KNOWN_ALGORITHMS.contains(algorithm)) {
                throw misconfigured("unsupported algorithm in '" + ALGORITHMS + "': " + trimmed);
            }
            algorithms.add(algorithm);
        }
        if (algorithms.isEmpty()) {
            throw misconfigured("'" + ALGORITHMS + "' is empty");
        }
        return Collections.unmodifiableSet(algorithms);
    }

    private static String required(Map<String, String> properties, String key) throws AuthenticationException {
        String value = optional(properties, key);
        if (value == null) {
            throw misconfigured("'" + key + "' is required");
        }
        return value;
    }

    private static String optional(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static long nonNegative(Map<String, String> properties, String key, long defaultValue)
            throws AuthenticationException {
        String value = optional(properties, key);
        if (value == null) {
            return defaultValue;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw misconfigured("'" + key + "' is not an integer: " + value);
        }
        if (parsed < 0) {
            throw misconfigured("'" + key + "' must not be negative: " + value);
        }
        return parsed;
    }

    static AuthenticationException misconfigured(String message) {
        return new AuthenticationException("OIDC integration misconfigured: " + message,
                AuthenticationFailureType.MISCONFIGURED);
    }
}
