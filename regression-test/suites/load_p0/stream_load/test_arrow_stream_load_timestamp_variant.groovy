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

suite("test_arrow_stream_load_timestamp_variant", "p0") {

    // ---- 1. Arrow stream load of timezone-naive timestamps -----------------
    // The Arrow file holds wall-clock values in naive Timestamp(us). The fixed
    // reader decodes naive in UTC, so the loaded datetime must equal the source
    // wall-clock (no +8h shift).
    sql """DROP TABLE IF EXISTS arrow_ts_load"""
    sql """
        CREATE TABLE arrow_ts_load (
            id INT,
            ts DATETIMEV2
        ) ENGINE=OLAP
        DUPLICATE KEY(id)
        DISTRIBUTED BY HASH(id) BUCKETS 1
        PROPERTIES ("replication_allocation" = "tag.location.default: 1");
    """

    streamLoad {
        table "arrow_ts_load"
        set 'format', 'arrow'
        file 'arrow_integration/arrow_timestamp.arrow'
        time 20000
        check { result, exception, startTime, endTime ->
            if (exception != null) { throw exception }
            log.info("Stream load result: ${result}".toString())
            def json = parseJson(result)
            assertEquals("success", json.Status.toLowerCase())
            assertEquals(2, json.NumberTotalRows)
            assertEquals(0, json.NumberFilteredRows)
        }
    }

    sql "sync"
    order_qt_arrow_ts """
        SELECT concat(cast(id AS STRING), ':', cast(ts AS STRING))
        FROM arrow_ts_load
        ORDER BY id
    """

    // ---- 2. Arrow stream load of VARIANT columns ---------------------------
    // The Arrow file holds JSON documents in STRING columns. The VARIANT reader
    // parses each into a Variant value; the third row's null must land as a null.
    sql """DROP TABLE IF EXISTS arrow_variant_load"""
    sql """
        CREATE TABLE arrow_variant_load (
            id INT,
            v VARIANT
        ) ENGINE=OLAP
        DUPLICATE KEY(id)
        DISTRIBUTED BY HASH(id) BUCKETS 1
        PROPERTIES ("replication_allocation" = "tag.location.default: 1");
    """

    streamLoad {
        table "arrow_variant_load"
        set 'format', 'arrow'
        file 'arrow_integration/arrow_variant.arrow'
        time 20000
        check { result, exception, startTime, endTime ->
            if (exception != null) { throw exception }
            log.info("Stream load result: ${result}".toString())
            def json = parseJson(result)
            assertEquals("success", json.Status.toLowerCase())
            assertEquals(3, json.NumberTotalRows)
            assertEquals(0, json.NumberFilteredRows)
        }
    }

    sql "sync"
    order_qt_arrow_variant """
        SELECT concat(cast(id AS STRING), ':', cast(v AS STRING))
        FROM arrow_variant_load
        ORDER BY id
    """
}
