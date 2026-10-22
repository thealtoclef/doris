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

package org.apache.doris.mysql.privilege;

import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.Pair;
import org.apache.doris.common.UserException;

import com.google.common.collect.ImmutableMap;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One version of the document a user property provider serves: every listed user's properties, parsed by
 * the same rules as SET PROPERTY. The wire format is specified in
 * fe-core/src/test/resources/user_property_provider/README.md.
 *
 * <p>A document that is not shaped as specified is refused whole: strict JSON in UTF-8, no name given twice
 * in one object. A user whose properties Doris refuses (an unknown name, a bad value, a workload group that
 * does not exist) is left out, so that user gets the defaults while every other entry still applies.
 */
public final class UserPropertyDocument {
    private static final Logger LOG = LogManager.getLogger(UserPropertyDocument.class);

    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");

    public static final UserPropertyDocument EMPTY =
            new UserPropertyDocument(0, ImmutableMap.of(), ImmutableMap.of(), new byte[0]);

    private final long version;
    private final Map<String, UserProperty> users;
    private final Map<String, String> rejectedUsers;
    private final byte[] bytes;

    private UserPropertyDocument(long version, Map<String, UserProperty> users,
            Map<String, String> rejectedUsers, byte[] bytes) {
        this.version = version;
        this.users = users;
        this.rejectedUsers = rejectedUsers;
        this.bytes = bytes;
    }

    public static UserPropertyDocument parse(byte[] bytes) throws MalformedDocumentException {
        Long version = null;
        Map<String, List<Pair<String, String>>> listed = null;
        try (JsonReader reader = new JsonReader(new StringReader(decode(bytes)))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new MalformedDocumentException("the document is not a JSON object");
            }
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) {
                    throw new MalformedDocumentException("field '" + field + "' is given more than once");
                }
                if (field.equals("version")) {
                    version = readVersion(reader);
                } else if (field.equals("users")) {
                    listed = readUsers(reader);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new MalformedDocumentException("the document continues after its object");
            }
        } catch (IOException | IllegalStateException e) {
            throw new MalformedDocumentException("not JSON: " + e.getMessage());
        }
        if (version == null) {
            throw new MalformedDocumentException("version is not a number");
        }
        if (listed == null) {
            throw new MalformedDocumentException("users is not an object");
        }

        ImmutableMap.Builder<String, UserProperty> users = ImmutableMap.builder();
        ImmutableMap.Builder<String, String> rejectedUsers = ImmutableMap.builder();
        for (Map.Entry<String, List<Pair<String, String>>> user : listed.entrySet()) {
            String refusal = refusalOf(user.getKey(), user.getValue());
            UserProperty property = new UserProperty(user.getKey());
            if (refusal == null) {
                try {
                    property.updateServed(user.getValue());
                } catch (UserException e) {
                    refusal = e.getDetailMessage();
                } catch (RuntimeException e) {
                    // a check that fails unexpectedly costs this entry, never the document
                    LOG.warn("user property provider: checking the properties of user '{}' failed", user.getKey(),
                            e);
                    refusal = e.toString();
                }
            }
            if (refusal == null) {
                users.put(user.getKey(), property);
            } else {
                LOG.warn("user property provider: properties of user '{}' are refused, the user gets the"
                        + " defaults: {}", user.getKey(), refusal);
                rejectedUsers.put(user.getKey(), refusal);
            }
        }
        return new UserPropertyDocument(version, users.build(), rejectedUsers.build(), bytes.clone());
    }

    private static String decode(byte[] bytes) throws MalformedDocumentException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new MalformedDocumentException("the document is not UTF-8");
        }
    }

    private static long readVersion(JsonReader reader) throws IOException, MalformedDocumentException {
        if (reader.peek() != JsonToken.NUMBER) {
            throw new MalformedDocumentException("version is not a number");
        }
        String literal = reader.nextString();
        if (POSITIVE_INTEGER.matcher(literal).matches()) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException e) {
                // reported below
            }
        }
        throw new MalformedDocumentException("version is not a positive 64-bit integer: " + literal);
    }

    private static Map<String, List<Pair<String, String>>> readUsers(JsonReader reader)
            throws IOException, MalformedDocumentException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new MalformedDocumentException("users is not an object");
        }
        Map<String, List<Pair<String, String>>> users = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String user = reader.nextName();
            if (users.containsKey(user)) {
                throw new MalformedDocumentException("user '" + user + "' is listed more than once");
            }
            users.put(user, readProperties(reader, user));
        }
        reader.endObject();
        return users;
    }

    private static List<Pair<String, String>> readProperties(JsonReader reader, String user)
            throws IOException, MalformedDocumentException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new MalformedDocumentException("properties of user '" + user + "' are not an object");
        }
        List<Pair<String, String>> properties = new ArrayList<>();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) {
                throw new MalformedDocumentException("property '" + name + "' of user '" + user
                        + "' is given more than once");
            }
            if (reader.peek() != JsonToken.STRING) {
                throw new MalformedDocumentException("property '" + name + "' of user '" + user
                        + "' is not a string");
            }
            properties.add(Pair.of(name, reader.nextString()));
        }
        reader.endObject();
        return properties;
    }

    /** Why an entry cannot apply whatever its values are, or null. */
    private static String refusalOf(String user, List<Pair<String, String>> properties) {
        if (!ClusterNamespace.getNameFromFullName(user).equals(user)) {
            return "a login never carries the default_cluster: prefix, so this name matches no user";
        }
        // Doris reads property names case-insensitively, so two spellings would set one property twice
        Set<String> names = new HashSet<>();
        for (Pair<String, String> property : properties) {
            if (!names.add(property.first.toLowerCase(Locale.ROOT))) {
                return "property '" + property.first + "' is set more than once";
            }
        }
        return null;
    }

    public long getVersion() {
        return version;
    }

    /** The served properties of a user, or null when the document does not list the user. */
    public UserProperty get(String qualifiedUser) {
        return users.get(qualifiedUser);
    }

    /**
     * Every listed user's served properties. They are shared by every reader of this document and must not
     * be updated; a changed document is a new instance.
     */
    public Map<String, UserProperty> getUsers() {
        return users;
    }

    /** Users the document lists whose properties Doris refused, with the reason. */
    public Map<String, String> getRejectedUsers() {
        return rejectedUsers;
    }

    /** The document exactly as it was served. */
    public byte[] getBytes() {
        return bytes.clone();
    }

    public static class MalformedDocumentException extends Exception {
        public MalformedDocumentException(String message) {
            super(message);
        }
    }
}
