set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

CREATE EXTERNAL TABLE `table1`(
`col1` string,
`info` map<string,struct<id:string,name:string,city:string,state:string,country:string>>)
PARTITIONED BY (
`store_date` bigint)
ROW FORMAT SERDE
'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
WITH SERDEPROPERTIES (
'collection.delim'='\u0003',
'field.delim'='\u0001',
'mapkey.delim'='\u0002',
'serialization.format'='\u0001')
STORED AS INPUTFORMAT
'org.apache.hadoop.mapred.TextInputFormat'
OUTPUTFORMAT
'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat';

LOAD DATA LOCAL INPATH '../../data/files/data_row.txt' INTO TABLE table1 partition(store_date='20230901');

select * from table1;

set hive.vectorized.execution.enabled=false;

select * from table1;
