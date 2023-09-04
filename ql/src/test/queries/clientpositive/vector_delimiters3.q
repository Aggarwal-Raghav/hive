set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

CREATE EXTERNAL TABLE `table3`(
`name` string,
`testmarks` array<array<struct<eng:string,math:string>>>)
ROW FORMAT SERDE
'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
STORED AS INPUTFORMAT
'org.apache.hadoop.mapred.TextInputFormat'
OUTPUTFORMAT
'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat';

LOAD DATA LOCAL INPATH '../../data/files/data_row3.txt' INTO TABLE table3;

select * from table3;

set hive.vectorized.execution.enabled=false;

select * from table3;
