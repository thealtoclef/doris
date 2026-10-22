# User property provider protocol

A frontend with `user_property_provider_url` set in `fe.conf` takes every user's properties
(`max_user_connections`, `query_timeout`, `default_workload_group`, …) from a document it polls
from that URL. This file specifies the document and the poll. The JSON files beside it are
golden examples; a provider's tests should produce them byte for byte. `UserPropertyDocumentTest`
pins their SHA-256, so changing one is a deliberate change to the contract, never an accident.
A provider that keeps a copy pins the same digests and compares its copy with these files when
both checkouts are present (doris-manager: `DORIS_FORK_DIR`).

## Behaviour of the frontend

- The document is the only source of user properties, for internal users and for users who log
  in through an authentication integration alike. A user is matched by exact, case-sensitive name:
  an internal account keeps the case it was created with, and an entry for a single-sign-on account
  must use the name its login plugin derives. A user the document does not list gets the built-in
  defaults; stored properties are ignored while the URL is set, and apply again once it is removed.
- `SET PROPERTY` is refused by the frontend it is issued on, which does not forward it to the
  master. `SHOW PROPERTY`, `SHOW ALL PROPERTIES` and `SHOW PROC '/auth/<user>'` show the served
  values; a user whose entry is refused shows the defaults.
- Property values are checked by the same rules as `SET PROPERTY`, except that a default compute
  group is not checked against the user's `USAGE` privilege (it is checked when a query uses it);
  that the compute group exists is checked. An entry is also refused when its name carries the
  `default_cluster:` prefix (no login does) or when it sets one property twice, property names
  being case-insensitive. A user whose properties are refused is left out, so that user gets the
  defaults, while every other entry still applies.
- The held document is checked again on every 304, and served again when that changes which
  users are refused: a workload group may be created after the document names it, or dropped
  while it does.
- While the URL is set, a workload group can be dropped unless the document names it; stored
  properties do not keep it. A stored workload group dropped this way makes that user's queries
  fail once the URL is removed, until `SET PROPERTY` names another.
- A failed poll, a status other than 200 or 304, or a malformed document keeps the document held.
- Every document received is written durably to
  `<meta_dir>/user_property_provider/document.json` and read once at startup, before the frontend
  accepts connections. A frontend that has never received a document serves no users: everyone
  gets the defaults.
- The provider is trusted as the Ranger policy service is: plain HTTP inside one network guarded
  by network policy. Whoever can answer the poll sets every user's limits.

Settings (all read at startup; a value that is not positive uses the default):

| Key | Default | |
|---|---|---|
| `user_property_provider_url` | empty (off) | full URL of the document |
| `user_property_provider_poll_interval_ms` | 30000 | |
| `user_property_provider_connect_timeout_ms` | 10000 | |
| `user_property_provider_read_timeout_ms` | 30000 | |

## Poll

```
GET <user_property_provider_url>?lastKnownVersion=<version held, -1 when none>&pluginId=<hostname>-doris
```

`pluginId` is the value the Ranger plugin reports for the same frontend.

| Status | Meaning |
|---|---|
| 200 | body is the current document |
| 304 | `lastKnownVersion` is current; empty body |
| anything else | no document; the frontend keeps the one it holds |

## Document

```json
{"version":1234567890123,"users":{"blue@cake.vn":{"max_user_connections":"20"}}}
```

- The document is UTF-8 and strict JSON (RFC 8259: no comments, unquoted names or single quotes).
- `version`: positive 64-bit integer literal (`41`, not `41.0` or `4.1e1`) identifying the content;
  equal versions mean equal documents, whichever URL served them.
- `users`: object keyed by the name a user logs in as, without a host (`etl`, `blue@cake.vn`,
  `system:serviceaccount:analytics:airflow`); each value is an object of property name to string
  value, written exactly as `SET PROPERTY` takes it. No users is `"users":{}`.
- Any other top-level field is ignored. A document whose `version` or `users` is missing or of the
  wrong type, with a non-object user or a non-string value, or that gives one name twice in an
  object, is refused whole.
