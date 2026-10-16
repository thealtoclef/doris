# OIDC Authentication Plugin

Authenticates JWT passwords against one issuer's JWKS. The username comes from `subject_claim` (default `sub`),
without rewriting. Doris maps it to an existing account or creates a session-only user when `enable_jit_user`
is enabled. The plugin returns no groups or roles.

## Configuration

One integration trusts one issuer. To trust several issuers, create one integration per issuer and chain them.

```
# fe.conf
# Enable TLS for token authentication.
enable_ssl = true
# Tried after native login fails; mutable at runtime.
authentication_chain = idp, k8s, app
```

```sql
-- people, from an OIDC provider
CREATE AUTHENTICATION INTEGRATION idp PROPERTIES (
  'type' = 'oidc',
  'issuer' = 'https://idp.example.com/',
  'audience' = 'doris',
  'algorithms' = 'RS256',
  'jwks_uri' = 'https://idp.example.com/keys',
  'subject_claim' = 'email',
  'enable_jit_user' = 'true');

-- Kubernetes service accounts (projected token with audience "doris");
-- username = sub = system:serviceaccount:<namespace>:<name>
CREATE AUTHENTICATION INTEGRATION k8s PROPERTIES (
  'type' = 'oidc',
  'issuer' = 'https://kubernetes.default.svc.cluster.local',
  'audience' = 'doris',
  'algorithms' = 'RS256',
  'jwks_uri' = 'https://<api-server>/openid/v1/jwks',
  'enable_jit_user' = 'true');

-- an application signing its own tokens; its public JWKS comes from an env var on every FE
CREATE AUTHENTICATION INTEGRATION app PROPERTIES (
  'type' = 'oidc',
  'issuer' = 'my-app',
  'audience' = 'doris',
  'algorithms' = 'ES256',
  'jwks_env' = 'MY_APP_JWKS',
  'enable_jit_user' = 'true');
```

The client sends the token as the password over TLS, with the login name set to the token's username. The FE asks
the client to switch to `mysql_clear_password`; MySQL clients need to allow cleartext authentication for that.

### Properties

| Property | Required | Default | Meaning |
|---|---|---|---|
| `type` | yes | | `oidc` |
| `issuer` | yes | | Exact `iss`. |
| `audience` | yes | | Must be one of the values in `aud`. |
| `algorithms` | yes | | Comma-separated JWS algorithms: `RS256/384/512`, `PS256/384/512`, `ES256/384/512`, `HS256/384/512`. EdDSA and ES256K are not supported. |
| `jwks_uri` | one of the two | | HTTPS JWKS URL, accessible without client credentials. Fetched on first use, cached, and refreshed for unknown keys, subject to a rate limit. |
| `jwks_env` | one of the two | | Name of an env var that holds the JWKS JSON. It must be set on every FE, and is read when the integration is initialized and again on `ALTER`. |
| `jwks_cache_ttl` | no | `3600` | Seconds a fetched JWKS is cached. Must be more than 30, the refetch rate-limit interval. |
| `subject_claim` | no | `sub` | The claim whose value is the username, used verbatim. A dotted path selects a nested claim; at each level the longest existing key wins, so `kubernetes.io.serviceaccount.name` resolves. |
| `leeway` | no | `60` | Allowed clock skew in seconds for `exp`, `nbf` and `iat`. |
| `enable_jit_user` | fe-core's own | `false` | `true` to create the account for the session. |

HTTPS certificates must be trusted by the JVM truststore. OIDC discovery is not used; supply the JWKS URL directly.
For Kubernetes, the API-server JWKS endpoint must allow unauthenticated reads. Otherwise, provide its keys through
`jwks_env`.

## Behaviour

### Picking the integration
`supports()` reads unverified `iss` and `aud` claims to select an integration. Tokens with a different issuer or
audience are passed to the next integration; invalid credentials for a matching integration stop the chain.

Credentials must start with `eyJ` and contain exactly two dots, matching fe-core's TLS guard. Whitespace is not
stripped, and differently serialized JWT headers are not supported.

### Validation

- The signature must verify with a JWKS key and an allowed algorithm.
- `typ` must be `JWT`, `at+jwt`, either `application/` form, or absent.
- `iss` and `aud` must match the integration.
- `exp` must be non-null and unexpired; `nbf` and `iat`, when present, must not be in the future, within `leeway`.
- `subject_claim` must be a non-empty string equal to the login name.

Validation failures return `BAD_CREDENTIAL` and stop the chain. Client messages omit token values.
JWKS fetch failures return `SOURCE_UNAVAILABLE`, allowing the chain to continue. A rate-limited retry uses the
last fetch outcome; cache hits do not change it. Other verification errors return `INTERNAL_ERROR`.

Logs record the integration, key ID, available username, and failure reason; successful logins also record the issuer.
The token itself is never logged.

### Usernames
The claim's value is used as is: no lowercasing, no rewriting. Names with `@` or `:` work, for example
`user@example.com` or `system:serviceaccount:ns:sa`. Two things in fe-core affect them:
- **Existing accounts:** if a native account with the same name exists, fe-core logs in to that account.
- **`#`:** fe-core cuts the login name at `#`, so a username containing `#` fails check 6.

## Packaging

This plugin loads from `authentication_plugins_dir` (default `$DORIS_HOME/plugins/authentication`).
`build.sh --fe` produces:

```
output/fe/plugins/authentication/oidc/
├── fe-authentication-plugin-oidc-<version>.jar
└── lib/nimbus-jose-jwt-<version>.jar
```

Nimbus JOSE+JWT is bundled with the plugin. Logging and the authentication API/SPI are provided by the FE.
