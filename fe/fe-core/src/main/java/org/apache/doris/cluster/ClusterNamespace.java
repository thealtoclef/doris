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

package org.apache.doris.cluster;

import org.apache.doris.mysql.privilege.Auth;

import com.google.common.base.Strings;

/**
 * used to isolate the use for the database name and user name in the catalog,
 * all using the database name and user name place need to call the appropriate
 * method to makeup full name or get real name, full name is made up generally
 * in stmt's analyze.
 *
 */
public class ClusterNamespace {

    private static final String CLUSTER_DELIMITER = ":";
    // Names persisted before multi-cluster was removed carry this prefix.
    private static final String LEGACY_CLUSTER_PREFIX = "default_cluster" + CLUSTER_DELIMITER;

    /**
     * Strips the legacy "default_cluster:" prefix. Any other ':' belongs to the name,
     * e.g. "system:serviceaccount:ns:sa".
     */
    public static String getNameFromFullName(String fullName) {
        if (Strings.isNullOrEmpty(fullName) || !fullName.startsWith(LEGACY_CLUSTER_PREFIX)) {
            return fullName;
        }
        return fullName.substring(LEGACY_CLUSTER_PREFIX.length());
    }

    private static String linkString(String cluster, String name) {
        if (Strings.isNullOrEmpty(cluster) || Strings.isNullOrEmpty(name)) {
            return null;
        }
        if (name.contains(CLUSTER_DELIMITER) || name.equalsIgnoreCase(Auth.ROOT_USER)
                || name.equalsIgnoreCase(Auth.ADMIN_USER)) {
            return name;
        }
        final StringBuilder sb = new StringBuilder(cluster);
        sb.append(CLUSTER_DELIMITER).append(name);
        return sb.toString();
    }
}
