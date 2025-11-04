# BigQuery Catalog Federation with Iceberg REST Catalog in Apache Doris

## Overview

This document provides a comprehensive guide for integrating Apache Doris with **BigQuery Catalog Federation** using the BigLake Iceberg REST catalog interface. This setup enables Doris to create and manage Iceberg tables in Google Cloud Storage while maintaining interoperability with other engines including BigQuery, Apache Spark, Apache Flink, and Trino.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        BIGLAKE METASTORE SERVICE (BLMS)                        │
│                                                                                  │
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐             │
│  │   Apache        │    │  BigLake         │    │   Doris FE      │             │
│  │   Doris         │    │  Metastore       │    │   (Java)        │             │
│  │                 │    │  Service         │    │                 │             │
│  │ • Query Engine  │◄──►│ • Iceberg REST   │◄──►│ • Query Planner │             │
│  │ • Vectorized    │    │   Catalog API    │    │ • Meta Cache    │             │
│  │   Execution     │    │ • Unified        │    │ • Cost-Based    │             │
│  │ • Storage Layer │    │   Metadata       │    │   Optimizer     │             │
│  │ • S3-compatible │    │ • Serverless     │    │ • Storage Props │             │
│  │   GCS Client    │    │ • Multi-Engine   │    │ • AuthN/AuthZ   │             │
│  └─────────────────┘    └──────────────────┘    └─────────────────┘             │
│           │                       │                       │                     │
│           ▼                       ▼                       ▼                     │
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐             │
│  │ Google Cloud    │    │  Google Cloud    │    │   Doris BE      │             │
│  │ IAM &           │    │ Storage (GCS)    │    │   (C++)         │             │
│  │ BigLake API     │    │                  │    │                 │             │
│  │                 │    │ • Customer-owned │    │ • Vectorized    │             │
│  │ • OAuth2/ADC    │    │   Buckets        │    │   Execution     │             │
│  │ • Service Accts │    │ • Parquet Files  │    │ • S3-compatible │             │
│  │ • Permissions   │    │ • Iceberg        │    │   GCS Client    │             │
│  │                 │    │   Metadata       │    │ • File I/O      │             │
│  └─────────────────┘    └──────────────────┘    └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Key Components

### 1. BigQuery Catalog Federation
- **BigQuery Integration**: BigQuery provides catalog federation automatically
- **Iceberg REST Catalog**: Standard Iceberg REST catalog interface via BigLake
- **Multi-Engine Support**: Single source of truth for all engines
- **Unified Governance**: Consistent access controls across engines
- **Serverless**: No separate metastore infrastructure required

### 2. Apache Doris Integration
- **Frontend (FE)**: Java-based query planning and metadata caching
- **Backend (BE)**: C++ vectorized execution with S3-compatible GCS access
- **Storage Layer**: Direct GCS file access for Parquet/ORC/Avro data reading
- **Query Optimization**: Cost-based optimizer with Iceberg-aware optimizations

### 3. Google Cloud Storage Integration
- **Customer-Owned Buckets**: Data remains in your GCS buckets
- **Iceberg Format**: Open table format with metadata and data separation
- **Multi-Engine Access**: Same data accessible from Doris, Spark, BigQuery, etc.

## Complete Query Flow

### Step 1: Catalog Configuration and Initialization

```sql
CREATE CATALOG iceberg_gcs PROPERTIES (
    "type"="iceberg",
    "warehouse"="bq://projects/cake-data-non-production/locations/asia-southeast1",
    "iceberg.catalog.type"="rest",
    "iceberg.rest.uri"="https://biglake.googleapis.com/iceberg/v1/restcatalog",
    "iceberg.rest.security.type"="google",
    "iceberg.rest.metrics.reporting-enabled"="false",
    "iceberg.rest.io-impl"="org.apache.iceberg.gcp.gcs.GCSFileIO",
    "iceberg.rest.google.user-project"="cake-data-non-production",
    "gs.use_path_style"="true",                         -- Prevents /s3/ virtual directory
    "gs.endpoint"="https://storage.googleapis.com",
    "gs.access_key"="GOOGLE_ACCESS_KEY",                -- HMAC for direct GCS access
    "gs.secret_key"="GOOGLE_SECRET_KEY"                 -- HMAC for direct GCS access
);
```

**Configuration Details:**
- **Warehouse**: `bq://projects/{project-id}/locations/{location}` - Required for BigLake Metastore
- **REST URI**: `https://biglake.googleapis.com/iceberg/v1/restcatalog` - BigLake Iceberg REST endpoint
- **IO Implementation**: `org.apache.iceberg.gcp.gcs.GCSFileIO` - Native GCS FileIO
- **Authentication**: GoogleAuth with ADC for catalog, HMAC keys for storage
- **User Project**: Google Cloud project for billing and quota management

### Step 2: Table Creation via Iceberg REST Catalog

#### Using Doris SQL with BigLake Metastore

```sql
-- Create Iceberg table through BigLake Metastore
CREATE TABLE iceberg_gcs.my_dataset.my_table (
    id BIGINT,
    name VARCHAR(100),
    event_date DATE,
    amount DECIMAL(10,2),
    properties MAP<STRING, STRING>,
    event_timestamp DATETIME
)
PARTITIONED BY (event_date)
STORED AS ICEBERG
LOCATION 'gs://my-bucket/my-dataset/my-table'
PROPERTIES (
    "compression-codec" = "zstd",
    "write-format" = "parquet"
);

-- Insert data
INSERT INTO iceberg_gcs.my_dataset.my_table VALUES
    (1, 'Alice', '2024-01-01', 100.50, MAP{'category': 'sales', 'region': 'us-east1'}, '2024-01-01 10:30:00'),
    (2, 'Bob', '2024-01-01', 75.25, MAP{'category': 'marketing', 'region': 'us-west1'}, '2024-01-01 11:15:00');

-- Bulk insert from staging table
INSERT INTO iceberg_gcs.my_dataset.my_table
SELECT id, name, event_date, amount, properties, event_timestamp
FROM staging_events
WHERE event_date = '2024-01-01';
```

#### Advanced Doris Table Patterns

```sql
-- Multi-level partitioning with time-based transforms
CREATE TABLE iceberg_gcs.my_dataset.events (
    event_id BIGINT,
    user_id BIGINT,
    event_type VARCHAR(50),
    event_timestamp DATETIME,
    properties MAP<STRING, STRING>,
    event_date DATE GENERATED ALWAYS AS (DATE(event_timestamp)) STORED,
    event_hour INT GENERATED ALWAYS AS (HOUR(event_timestamp)) STORED
)
PARTITIONED BY (event_date, event_type)
STORED AS ICEBERG
LOCATION 'gs://my-bucket/my-dataset/events'
PROPERTIES (
    "compression-codec" = "zstd",
    "write-format" = "parquet",
    "write-target-file-size-bytes" = "268435456"  -- 256MB
);

-- List partitioning for categorical data
CREATE TABLE iceberg_gcs.my_dataset.user_profiles (
    user_id BIGINT,
    profile_data MAP<STRING, STRING>,
    region VARCHAR(50),
    tier VARCHAR(20),
    updated_at DATETIME
)
PARTITIONED BY LIST (region, tier) ()
STORED AS ICEBERG
LOCATION 'gs://my-bucket/my-dataset/user_profiles';
```

### Step 3: Query Planning and Metadata Discovery

```
User Query: SELECT * FROM iceberg_gcs.my_dataset.my_table WHERE event_date = '2024-01-01'
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                     DORIS FRONTEND (FE)                        │
│                                                                 │
│ 1. Query Parsing & Analysis                                    │
│    - Parse SQL statement                                        │
│    - Resolve catalog, database, table references                │
│    - Validate syntax and semantics                               │
│                                                                 │
│ 2. Metadata Retrieval (BigLake REST Catalog)                    │
│    ├─ GET /v1/config                                          │
│    │  Response: { "defaults": { ... } }                        │
│    ├─ GET /v1/namespaces/my_dataset/tables/my_table           │
│    │  Response: {                                               │
│    │    "metadata-location": "gs://bucket/metadata/...",      │
│    │    "current-schema-id": 1,                               │
│    │    "schemas": [...],                                     │
│    │    "current-snapshot-id": 12345                          │
│    │    "location": "gs://my-bucket/my-dataset/my-table"     │
│    │  }                                                        │
│    └─ GET /v1/namespaces/my_dataset/tables/my_table/snapshots │
│       Response: [ { "snapshot-id": 12345, ... } ]             │
│                                                                 │
│ 3. Manifest File Processing                                    │
│    - Download manifest files from GCS                           │
│    - Parse file locations and statistics                        │
│    - Build file-level execution plan                           │
│                                                                 │
│ 4. Cost-Based Optimization                                      │
│    - Estimate costs based on file statistics                   │
│    - Apply partition pruning                                   │
│    - Generate optimal scan plan                                │
│                                                                 │
│ 5. Backend Configuration                                        │
│    - Convert Iceberg file locations to GCS paths               │
│    - Generate S3-compatible GCS configuration                   │
│    - Pass execution plan to backend                             │
└─────────────────────────────────────────────────────────────────┘
```

### Step 4: Data Storage and File Organization

#### BigLake Metastore Storage Structure:
```
gs://my-bucket/my-dataset/my-table/
├── metadata/
│   ├── 00000-6c1e5b-xxxxx.metadata.json     -- Table metadata
│   ├── snap-12345-1.avro                    -- Snapshot manifest
│   ├── manifest-list-xxxxx.avro             -- File manifest list
│   └── ...                                  -- Other metadata files
└── data/
    ├── event_date=2024-01-01/
    │   ├── 00000-00001-xxxxx-c000.parquet    -- Data files
    │   └── 00001-00002-xxxxx-c000.parquet
    ├── event_date=2024-01-02/
    │   ├── 00002-00003-xxxxx-c000.parquet
    │   └── 00003-00004-xxxxx-c000.parquet
    └── ...                                  -- More partition folders
```

### Step 5: Backend Execution and Data Access

```
┌─────────────────────────────────────────────────────────────────┐
│                    DORIS BACKEND (BE)                           │
│                                                                 │
│ 1. Fragment Execution Planning                                 │
│    - Receive scan plan from FE                                 │
│    - Distribute files across BE nodes                          │
│    - Schedule parallel scans                                   │
│                                                                 │
│ 2. GCS Client Configuration                                    │
│    │ S3ClientConf s3_conf;                                      │
│    │ s3_conf.endpoint = "https://storage.googleapis.com";      │
│    │ s3_conf.ak = "GOOG1EQAYMAAA...";                        │
│    │ s3_conf.sk = "base64-encoded-secret";                   │
│    │ s3_conf.region = "us-central1";                         │
│    │ s3_conf.provider = ObjStorageType::GCP;                 │
│    └─ Create GCS client (AWS SDK S3-compatible)               │
│                                                                 │
│ 3. Parallel File Reading                                        │
│    ┌─ Node 1: Partition 2024-01-01     ┌─ Node 2: Partition 2024-01-02 │
│    │  • HTTP GET requests            │  • HTTP GET requests          │
│    │  • Parquet decoding             │  • Parquet decoding           │
│    │  • Column pruning               │  • Column pruning             │
│    │  • Predicate pushdown           │  • Predicate pushdown         │
│    └─ Vectorized output             └─ Vectorized output           │
│                                                                 │
│ 4. Query Processing                                            │
│    - Apply filters and aggregations                            │
│    - Join with other tables                                    │
│    - Group by and ordering                                     │
│    - Final result aggregation                                 │
│                                                                 │
│ 5. Result Return                                               │
│    - Streaming results back to FE                             │
│    - Final result formatting                                   │
│    - Return to client                                           │
└─────────────────────────────────────────────────────────────────┘
```

## Doris Query Patterns and Optimizations

### Basic Query Examples

```sql
-- Simple scan with partition pruning
SELECT * FROM iceberg_gcs.my_dataset.my_table
WHERE event_date >= '2024-01-01' AND event_date < '2024-01-02';

-- Aggregation with column pruning
SELECT
    event_date,
    COUNT(*) as record_count,
    SUM(amount) as total_amount,
    AVG(amount) as avg_amount,
    MAX(amount) as max_amount
FROM iceberg_gcs.my_dataset.my_table
WHERE event_date BETWEEN '2024-01-01' AND '2024-01-31'
GROUP BY event_date
ORDER BY event_date;

-- Complex query with joins and window functions
SELECT
    id,
    name,
    amount,
    ROW_NUMBER() OVER (PARTITION BY event_date ORDER BY amount DESC) as rank_in_day,
    LAG(amount) OVER (PARTITION BY id ORDER BY event_date) as prev_amount
FROM iceberg_gcs.my_dataset.my_table
WHERE event_date >= '2024-01-01'
ORDER BY event_date, amount DESC;
```

### Advanced Iceberg Features in Doris

```sql
-- Time travel queries
SELECT * FROM iceberg_gcs.my_dataset.my_table
FOR SYSTEM_TIME AS OF '2024-01-01 10:00:00';

-- Query specific snapshot
SELECT * FROM iceberg_gcs.my_dataset.my_table
FOR SYSTEM_VERSION AS OF 12345;

-- Incremental queries using snapshot history
SELECT * FROM iceberg_gcs.my_dataset.my_table
WHERE _snapshot_id > (
    SELECT max(_snapshot_id) FROM iceberg_gcs.my_dataset.my_table
    FOR SYSTEM_TIME AS OF '2024-01-01 00:00:00'
);

-- Doris metadata table functions
SELECT * FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "snapshots");

SELECT * FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "files")
WHERE file_path LIKE '%2024-01-01%';

SELECT * FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "history")
ORDER BY made_at DESC LIMIT 10;
```

### Doris-Specific Optimizations

```sql
-- Enable query profiling for performance analysis
SET enable_profile = true;

-- Set appropriate session variables for Iceberg queries
SET enable_partition_pruning = true;
SET enable_vectorized_engine = true;
SET batch_size = 4096;

-- Optimized query with hints
SELECT /*+ SET_VAR(execution_time_limit=300000) */
    event_date,
    COUNT(*) as total_events,
    SUM(amount) as total_amount
FROM iceberg_gcs.my_dataset.my_table
WHERE event_date BETWEEN '2024-01-01' AND '2024-01-31'
GROUP BY event_date
ORDER BY total_amount DESC;
```

## Data Ingestion Patterns in Doris

### 1. Batch Loading

#### Direct INSERT Operations
```sql
-- Single record insert
INSERT INTO iceberg_gcs.my_dataset.my_table VALUES
    (1001, 'John Doe', '2024-01-15', 250.75,
     MAP{'category': 'premium', 'region': 'us-west1'}, '2024-01-15 14:30:00');

-- Bulk insert from values
INSERT INTO iceberg_gcs.my_dataset.my_table VALUES
    (1002, 'Jane Smith', '2024-01-15', 180.50, MAP{'category': 'standard', 'region': 'us-east1'}, '2024-01-15 09:15:00'),
    (1003, 'Bob Johnson', '2024-01-15', 320.00, MAP{'category': 'premium', 'region': 'us-central1'}, '2024-01-15 16:45:00'),
    (1004, 'Alice Brown', '2024-01-15', 95.25, MAP{'category': 'basic', 'region': 'us-west1'}, '2024-01-15 11:20:00');

-- Bulk insert from staging table
INSERT INTO iceberg_gcs.my_dataset.my_table
SELECT id, name, event_date, amount, properties, event_timestamp
FROM staging_events
WHERE event_date = CURRENT_DATE() - INTERVAL 1 DAY
AND status = 'validated';
```

#### UPSERT Operations (MERGE INTO)
```sql
-- Upsert data using MERGE INTO
MERGE INTO iceberg_gcs.my_dataset.my_table AS target
USING (
    SELECT id, name, event_date, amount, properties, event_timestamp
    FROM daily_updates
    WHERE update_date = CURRENT_DATE()
) AS source
ON target.id = source.id AND target.event_date = source.event_date
WHEN MATCHED THEN UPDATE SET
    target.name = source.name,
    target.amount = source.amount,
    target.properties = source.properties,
    target.event_timestamp = source.event_timestamp
WHEN NOT MATCHED THEN INSERT (
    id, name, event_date, amount, properties, event_timestamp
) VALUES (
    source.id, source.name, source.event_date, source.amount,
    source.properties, source.event_timestamp
);
```

### 2. Stream Processing Integration

#### Kafka Stream Integration
```sql
-- Create routine load for continuous data ingestion
CREATE ROUTINE LOAD kafka_load_iceberg ON iceberg_gcs.my_dataset.my_table
PROPERTIES (
    "type" = "kafka",
    "kafka_broker_list" = "kafka-broker:9092",
    "kafka_topic" = "events_topic",
    "property.group.id" = "doris_iceberg_consumer",
    "property.format" = "json",
    "property.strip_outer_array" = "true",
    "property.columns" = "id, name, event_date, amount, properties, event_timestamp",
    "property.property_names" = "category,region"
)
FROM KAFKA
PROPERTIES (
    "max_filter_ratio" = "0.1",
    "max_batch_interval" = "20",
    "max_batch_rows" = "300000",
    "max_batch_size" = "104857600"
);
```

### 3. Data Transformation Patterns

#### Complex ETL Operations
```sql
-- Transform and aggregate data before insertion
INSERT INTO iceberg_gcs.my_dataset.aggregated_events
SELECT
    DATE(event_timestamp) as event_date,
    EXTRACT(HOUR FROM event_timestamp) as event_hour,
    properties['category'] as category,
    properties['region'] as region,
    COUNT(*) as event_count,
    SUM(amount) as total_amount,
    AVG(amount) as avg_amount,
    MIN(amount) as min_amount,
    MAX(amount) as max_amount
FROM iceberg_gcs.my_dataset.raw_events
WHERE event_date BETWEEN CURRENT_DATE() - INTERVAL 7 DAY AND CURRENT_DATE()
GROUP BY
    DATE(event_timestamp),
    EXTRACT(HOUR FROM event_timestamp),
    properties['category'],
    properties['region']
HAVING COUNT(*) > 10;
```

## BigQuery Catalog Federation Setup

### Required Setup Steps

#### 1. Enable Google Cloud APIs
- Enable BigQuery API
- Enable BigLake API
- Enable Cloud Storage API

#### 2. Create and Configure Service Account
- Create a dedicated service account for Doris
- Create HMAC keys for S3-compatible GCS access
- Configure GCS bucket permissions for the service account

#### 3. Configure Doris Catalog
- Set up Iceberg catalog with BigQuery federation parameters
- Configure warehouse location and REST endpoint
- Add HMAC keys for GCS access

### Required IAM Roles

Add the following IAM roles to the Doris service account:

#### **Required for BigQuery Catalog Federation**
- `roles/bigquery.metadataViewer` - Access BigQuery federated catalog metadata

#### **Required for GCS Operations**
- `roles/storage.objectUser` - Create Iceberg tables and write metadata files
- `roles/storage.objectViewer` - Read data files from GCS
- `roles/storage.admin` - Create and manage HMAC keys

#### **Bucket-Level Permissions**
Grant the service account object-level permissions on target GCS buckets:
- `objectAdmin` - Full permissions on bucket objects
- OR `objectCreator` + `objectViewer` - Create and read permissions

### Doris Catalog Configuration

See Step 1: Catalog Configuration and Initialization for the complete CREATE CATALOG example with all required properties.

**Key Requirements**:
- **No BigLake Catalog Creation**: BigQuery provides catalog federation automatically
- **ObjectUser Role**: Essential for Iceberg table creation and metadata management
- **Warehouse Format**: Must use `bq://projects/{project}/locations/{location}` for federation
- **HMAC Keys**: Required since credential vending isn't supported for federated catalogs

## Performance Optimization

### 1. Partitioning Strategy

```sql
-- Optimal partitioning for Doris query performance
CREATE TABLE iceberg_gcs.my_dataset.events (
    event_id BIGINT,
    user_id BIGINT,
    event_timestamp TIMESTAMP,
    event_type STRING,
    properties MAP<STRING, STRING>,
    event_date DATE GENERATED ALWAYS AS (DATE(event_timestamp)) STORED,
    event_hour INT GENERATED ALWAYS AS (EXTRACT(HOUR FROM event_timestamp)) STORED
)
PARTITIONED BY (event_date, event_type)  -- Multi-level partitioning
STORED AS ICEBERG
LOCATION 'gs://my-bucket/my-dataset/events'
TBLPROPERTIES (
    'write.format.default'='parquet',
    'write.parquet.compression-codec'='zstd',
    'write.target-file-size-bytes'='536870912'  -- 512MB
);
```

### 2. File Size and Layout Optimization

```sql
-- Configure optimal file sizes for parallel scanning
ALTER TABLE biglake_catalog.my_dataset.my_table
SET TBLPROPERTIES (
    'write.target-file-size-bytes'='268435456',    -- 256MB files
    'write.max-file-size-bytes'='1073741824',     -- 1GB max
    'write.parquet.row-group-size-bytes'='134217728', -- 128MB row groups
    'write.parquet.page-size-bytes'='1048576',    -- 1MB pages
    'write.parquet.dictionary.page-size-bytes'='1048576' -- 1MB dictionary pages
);
```

### 3. Storage Configuration

Add these performance tuning properties to your existing catalog configuration:

```sql
-- Performance tuning properties (add to existing catalog)
ALTER CATALOG iceberg_gcs SET PROPERTIES (
    "gs.connection.maximum"="300",                    -- High concurrency
    "gs.connection.request.timeout"="120000",        -- 2 minutes
    "gs.connection.timeout"="120000",               -- 2 minutes
    "gs.connection.max.error.retry"="10",            -- Retry failed requests
    "gs.use_path_style"="false",                     -- Virtual hosted style
    "gs.force_parsing_by_standard_uri"="true"
);
```

## Data Ingestion Patterns

### 1. Batch Loading

#### Using Doris
```sql
-- Bulk insert from staging table
INSERT INTO biglake_catalog.my_dataset.my_table
SELECT * FROM staging_table
WHERE event_date = '2024-01-01';

-- Direct file load (if supported)
LOAD DATA LABEL 'load_parquet_files'
INPATH 'gs://staging-bucket/data/'
INTO TABLE biglake_catalog.my_dataset.my_table
FORMAT AS PARQUET;
```

#### Using Spark
```python
# Efficient batch writes with Spark
df = spark.read.parquet("gs://staging-bucket/data/")
df.write.format("iceberg") \
    .mode("append") \
    .save("biglake_catalog.my_dataset.my_table")
```

### 2. Streaming Ingestion

#### Using Spark Structured Streaming
```python
# Streaming write to BigLake-managed table
streaming_df = spark.readStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", "kafka:9092") \
    .option("subscribe", "events") \
    .load()

# Process and write to BigLake Metastore
query = streaming_df \
    .selectExpr("CAST(value AS STRING) as json") \
    .select(from_json("json", schema).alias("data")) \
    .select("data.*") \
    .writeStream \
    .format("iceberg") \
    .outputMode("append") \
    .option("checkpointLocation", "gs://checkpoint-bucket/checkpoints/") \
    .toTable("biglake_catalog.my_dataset.my_table")
```

### 3. Merge Operations (UPSERT)

```sql
-- Upsert data using MERGE INTO
MERGE INTO biglake_catalog.my_dataset.my_table AS target
USING (
    SELECT id, name, event_date, amount, properties
    FROM staging_updates
) AS source
ON target.id = source.id AND target.event_date = source.event_date
WHEN MATCHED THEN UPDATE SET
    target.name = source.name,
    target.amount = source.amount,
    target.properties = source.properties
WHEN NOT MATCHED THEN INSERT (
    id, name, event_date, amount, properties
) VALUES (
    source.id, source.name, source.event_date, source.amount, source.properties
);
```

## Monitoring and Performance Analysis in Doris

### 1. Query Performance Monitoring

```sql
-- Enable query profiling for detailed analysis
SET enable_profile = true;
SET enable_profile = true;

-- Run test query
SELECT * FROM iceberg_gcs.my_dataset.my_table
WHERE event_date = '2024-01-01'
LIMIT 1000;

-- View execution profile
SHOW PROFILE FROM <query_id>;

-- Check detailed query statistics
SELECT
    query_id,
    query_time_ms,
    scan_bytes,
    scan_rows,
    scan_files,
    cpu_time_ms,
    memory_peak_bytes,
    peak_memory_bytes
FROM information_schema.query_profile
WHERE query_id = '<query_id>';

-- Monitor active queries
SELECT
    query_id,
    query_time_ms,
    current_database,
    sql_fragment,
    state
FROM information_schema.running_queries
ORDER BY query_time_ms DESC;
```

### 2. Table Statistics and Health Monitoring

```sql
-- Check table statistics using Doris metadata functions
SELECT * FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "snapshots")
ORDER BY timestamp_ms DESC LIMIT 10;

-- Analyze file distribution and sizes
SELECT
    SUBSTRING(file_path FROM LENGTH('gs://my-bucket/my-dataset/my_table/') + 1) as relative_path,
    file_size_in_bytes,
    record_count,
    file_size_in_bytes / NULLIF(record_count, 0) as avg_bytes_per_record
FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "files")
WHERE file_path LIKE '%2024-01-01%'
ORDER BY file_size_in_bytes DESC;

-- Check partition health
SELECT
    partition,
    COUNT(*) as file_count,
    SUM(record_count) as total_records,
    SUM(file_size_in_bytes) as total_size_bytes
FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "files")
GROUP BY partition
ORDER BY total_size_bytes DESC;

-- Monitor snapshot history for table health
SELECT
    snapshot_id,
    timestamp_ms,
    operation,
    summary,
    schema_id
FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "history")
ORDER BY timestamp_ms DESC;
```

### 3. Catalog and Connection Health

```sql
-- Test catalog connectivity
SHOW DATABASES FROM iceberg_gcs;

-- Check table accessibility
SHOW TABLES FROM iceberg_gcs.my_dataset;

-- Validate table schema
DESCRIBE iceberg_gcs.my_dataset.my_table;

-- Test basic query performance
EXPLAIN SELECT COUNT(*) FROM iceberg_gcs.my_dataset.my_table
WHERE event_date = '2024-01-01';
```

### 4. Resource Usage Monitoring

```sql
-- Monitor backend node resource usage
SELECT
    backend_id,
    host,
    cpu_usage_percent,
    memory_limit_bytes,
    memory_usage_bytes,
    disk_io_util_percent,
    network_receive_bytes,
    network_send_bytes
FROM information_schema.backends
ORDER BY cpu_usage_percent DESC;

-- Check task queue and resource consumption
SELECT
    task_id,
    task_type,
    database,
    sql,
    cpu_cost_ms,
    memory_cost_bytes,
    scan_rows,
    scan_bytes,
    create_time,
    finish_time
FROM information_schema.task_runs
ORDER BY create_time DESC LIMIT 20;
```

## Security and Governance

### 1. Authentication Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   BigLake       │    │   Google Cloud  │    │   Doris (C++)   │
│   Metastore     │    │   Storage (GCS) │    │   Storage Layer │
│   REST API      │    │                 │    │                 │
│                 │    │                 │    │                 │
│ OAuth2/ADC      │◄──►│ GCS Files       │◄──►│ HMAC Keys       │
│ (Token-based)   │    │ (Object Storage)│    │ (S3-compatible) │
│                 │    │                 │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### 2. IAM Permission Matrix

| Component | Required IAM Roles | Purpose |
|-----------|-------------------|---------|
| **BigQuery Catalog Federation** | `roles/bigquery.metadataViewer` | Access BigQuery federated catalog metadata |
| **GCS Table Creation** | `roles/storage.objectUser` | Create Iceberg metadata files and table structures |
| **GCS File Access** | `roles/storage.objectViewer` | Read Parquet files directly from GCS |
| **HMAC Key Management** | `roles/storage.admin` | Create/manage HMAC keys for Doris GCS access |
| **Bucket Permissions** | `objectAdmin` on bucket | Full object-level permissions on target GCS bucket |

### 3. Fine-Grained Access Control

#### Database/Schema Level Access
```sql
-- Create role with limited access
CREATE ROLE data_analyst;

-- Grant access to specific databases
GRANT USAGE ON DATABASE biglake_catalog.my_dataset TO ROLE data_analyst;
GRANT SELECT ON TABLE biglake_catalog.my_dataset.my_table TO ROLE data_analyst;

-- Deny access to sensitive tables
REVOKE SELECT ON TABLE biglake_catalog.my_dataset.sensitive_data FROM ROLE data_analyst;
```

#### Row-Level Security (if implemented)
```sql
-- Example: Row-level security using views
CREATE VIEW biglake_catalog.my_dataset.my_table_filtered AS
SELECT * FROM biglake_catalog.my_dataset.my_table
WHERE region = CURRENT_USER();
```

## Troubleshooting Guide

### Common Issues and Solutions

#### 1. **BigLake Metastore Connection Issues**

**Error**: `Failed to connect to BigLake Metastore`
```
Solution Steps:
1. Verify BigLake Metastore catalog exists and is active
2. Check service account has biglake.viewer role
3. Validate URI format and project/location names
4. Check network connectivity to biglake.googleapis.com
```

#### 2. **Authentication Failures**

**Error**: `401 Unauthorized` or `403 Forbidden` from BigLake API
```
Solution Steps:
1. Verify GoogleAuth configuration (ADC setup)
2. Check service account IAM permissions
3. Validate OAuth2 token generation
4. Ensure proper project and location access
```

#### 3. **GCS File Access Issues**

**Error**: `Anonymous caller does not have storage.objects.get access`
```
Solution Steps:
1. Verify HMAC keys are properly configured in catalog
2. Check GCS bucket permissions
3. Ensure service account has storage.objectViewer role
4. Validate bucket exists and is accessible
```

#### 4. **Table Not Found Errors**

**Error**: `Table not found: my_dataset.my_table`
```
Solution Steps:
1. Check table exists in BigLake Metastore
2. Verify correct catalog/namespace/table names
3. Check table creation completed successfully
4. Validate user has proper permissions
```

#### 5. **Performance Issues**

**Symptoms**: Slow queries, high latency
```
Optimization Steps:
1. Check partition pruning effectiveness
2. Verify file sizes are optimal (256MB-512MB)
3. Increase GCS connection pool size
4. Enable query profiling for detailed analysis
5. Consider data locality (region alignment)
```

### Debug Commands in Doris

```sql
-- Check catalog configuration
SHOW CREATE CATALOG iceberg_gcs;

-- List available databases
SHOW DATABASES FROM iceberg_gcs;

-- Check table metadata
SHOW CREATE TABLE iceberg_gcs.my_dataset.my_table;

-- View table snapshots using Doris metadata function
SELECT * FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "history")
ORDER BY timestamp_ms DESC;

-- Check file locations using Doris metadata function
SELECT file_path, file_size_in_bytes, record_count
FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "files")
ORDER BY file_size_in_bytes DESC
LIMIT 10;

-- Test basic connectivity
SELECT count(*) FROM iceberg_gcs.my_dataset.my_table LIMIT 1;

-- Check partition information
SELECT partition, record_count, file_count
FROM iceberg_meta("table" = "iceberg_gcs.my_dataset.my_table", "query_type" = "files")
GROUP BY partition
ORDER BY partition;
```

## Best Practices

### 1. **Catalog and Database Design**
- Use consistent naming conventions across engines
- Implement proper database/namespace organization
- Use descriptive table and column names
- Document table schemas and partitioning strategies

### 2. **Table Design and Partitioning**
- Choose partitioning columns based on query patterns
- Use appropriate data types and constraints
- Optimize file sizes for parallel processing
- Implement data retention policies

### 3. **Performance Optimization**
- Leverage Iceberg's partition pruning and column pruning
- Use appropriate compression codecs (ZSTD recommended)
- Monitor and tune GCS connection settings
- Implement statistics collection for query optimization

### 4. **Data Governance**
- Implement role-based access control
- Use fine-grained permissions where possible
- Enable audit logging for compliance
- Implement data classification and tagging

### 5. **Multi-Engine Compatibility**
- Use Iceberg features supported across all engines
- Test queries across different engines
- Maintain consistent schema definitions
- Plan for engine-specific optimizations

### 6. **Operations and Maintenance**
- Monitor BigLake Metastore API quotas and limits
- Implement backup strategies for critical metadata
- Regular table maintenance (compaction, orphan file cleanup)
- Plan for regional data locality and disaster recovery

### 7. **Security**
- Use dedicated service accounts with least privilege
- Regularly rotate HMAC keys and service account keys
- Implement network controls (VPC Service Controls)
- Enable comprehensive audit logging

This integration provides a powerful, open, and managed lakehouse solution that enables organizations to leverage Doris's high-performance analytics capabilities while maintaining data interoperability across multiple query engines through BigLake Metastore.