set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

create EXTERNAL table `map_array_table` as
select
  MAP(
    "Key1",
    ARRAY(
      1,
      2,
      3
    ),
    "Key2",
    ARRAY(
    4,
    5,
    6
    )
  ) as testmarks;

select * from map_array_table;

set hive.vectorized.execution.enabled=false;

select * from map_array_table;
