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

// Arrow stream loads of DECIMAL columns.
//
// 1. Several Arrow producers (e.g. RisingWave) emit DECIMAL as Utf8 strings — the same textual
//    form Doris's JSON loading accepts. The decimal serde must accept that form (and native Arrow
//    decimal arrays), loading values at the target precision/scale.
// 2. A wrong-typed Arrow array for a DECIMAL target must produce a graceful load error, not a BE
//    segfault. This is the regression for the crash where read_column_from_arrow dereferenced a
//    failed dynamic_cast<DecimalArray*> on a non-decimal array.
suite("test_arrow_stream_load_decimal", "p0") {

    // ---- 1. DECIMAL-as-Utf8 (RisingWave-style) loads at target precision/scale ----
    sql """DROP TABLE IF EXISTS arrow_decimal_string_load"""
    sql """
        CREATE TABLE arrow_decimal_string_load (
            id INT,
            d DECIMAL(38, 9) NULL
        ) ENGINE=OLAP
        DUPLICATE KEY(id)
        DISTRIBUTED BY HASH(id) BUCKETS 1
        PROPERTIES ("replication_allocation" = "tag.location.default: 1");
    """

    streamLoad {
        table "arrow_decimal_string_load"
        set 'format', 'arrow'
        file 'arrow_integration/arrow_decimal_string.arrow'
        time 20000
        check { result, exception, startTime, endTime ->
            if (exception != null) { throw exception }
            log.info("Stream load result: ${result}".toString())
            def json = parseJson(result)
            assertEquals("success", json.Status.toLowerCase())
            assertEquals(4, json.NumberTotalRows)
            assertEquals(0, json.NumberFilteredRows)
        }
    }

    sql "sync"
    def rows = sql """SELECT id, cast(d AS STRING) FROM arrow_decimal_string_load ORDER BY id"""
    assertEquals(4, rows.size())
    // Normalize cells to strings; nulls stay null.
    assertEquals("1", rows[0][0].toString())
    assertEquals("1234567.560000000", rows[0][1].toString())
    assertEquals("2", rows[1][0].toString())
    assertEquals("-1234567.560000000", rows[1][1].toString())
    assertEquals("3", rows[2][0].toString())
    assertEquals(null, rows[2][1])
    assertEquals("4", rows[3][0].toString())
    assertEquals("0.010000000", rows[3][1].toString())

    // ---- 2. Wrong-typed Arrow array for a DECIMAL column fails gracefully, BE stays alive ----
    // Before the fix this input crashed the BE with a null deref in
    // DataTypeDecimalSerDe::read_column_from_arrow. The load must now return an error and the
    // cluster must keep serving.
    sql """DROP TABLE IF EXISTS arrow_decimal_wrong_type_load"""
    sql """
        CREATE TABLE arrow_decimal_wrong_type_load (
            id INT,
            d DECIMAL(38, 9)
        ) ENGINE=OLAP
        DUPLICATE KEY(id)
        DISTRIBUTED BY HASH(id) BUCKETS 1
        PROPERTIES ("replication_allocation" = "tag.location.default: 1");
    """

    def failedLoad = false
    try {
        streamLoad {
            table "arrow_decimal_wrong_type_load"
            set 'format', 'arrow'
            file 'arrow_integration/arrow_decimal_wrong_type.arrow'
            time 20000
            check { result, exception, startTime, endTime ->
                log.info("Stream load result: ${result}".toString())
                if (exception == null) {
                    def json = parseJson(result)
                    // A graceful load rejection reports a non-success Status. The essential
                    // property is that the load fails without killing the BE.
                    failedLoad = json.Status.toLowerCase() != "success"
                } else {
                    failedLoad = true
                }
            }
        }
    } catch (Exception e) {
        log.info("Expected stream load failure: ${e.getMessage()}")
        failedLoad = true
    }
    assertTrue(failedLoad, "wrong-typed arrow load must be rejected, not succeed")

    // The BE must still be healthy and able to take a query after the rejected load.
    def alive = sql """SELECT 1"""
    assertEquals(1, alive.size())
    assertEquals(1, alive[0][0])
}
