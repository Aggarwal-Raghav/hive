CREATE TABLE repro_parquet_string (
  id INT,
  col1 STRING
) STORED AS PARQUET;

INSERT INTO repro_parquet_string VALUES (1, '2026-01-01');

CREATE EXTERNAL TABLE repro_parquet_date (
  id INT,
  col1 DATE
) STORED AS PARQUET
LOCATION '${hiveconf:hive.metastore.warehouse.dir}/repro_parquet_string';

CREATE TABLE small_table (
  id INT,
  date_col DATE
);
INSERT INTO small_table VALUES (1, '2026-01-01');

SET hive.auto.convert.join=true;
SET hive.vectorized.execution.enabled=false;

SELECT /*+ MAPJOIN(small_table) */ a.col1
FROM repro_parquet_date a
JOIN small_table b ON a.id = b.id;

SET hive.vectorized.execution.enabled=true;

EXPLAIN VECTORIZATION DETAIL
SELECT /*+ MAPJOIN(small_table) */ a.col1 
FROM repro_parquet_date a 
JOIN small_table b ON a.id = b.id;

SELECT /*+ MAPJOIN(small_table) */ a.col1
FROM repro_parquet_date a
JOIN small_table b ON a.id = b.id;
