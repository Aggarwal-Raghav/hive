set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

CREATE EXTERNAL TABLE `table2`(
`name` string,
`total` struct<engtot:string,mathtot:string>,
`testmarks` array<array<struct<eng:string,math:string>>>,
`average` map<string,string>)
ROW FORMAT SERDE
'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
STORED AS INPUTFORMAT
'org.apache.hadoop.mapred.TextInputFormat'
OUTPUTFORMAT
'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat';

LOAD DATA LOCAL INPATH '../../data/files/data_row2.txt' INTO TABLE table2;

select * from table2;

set hive.vectorized.execution.enabled=false;

select * from table2;
