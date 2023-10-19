set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

create EXTERNAL table `map_map_table` as
select
  'bob' as name,
  MAP(
    "Key1",
    MAP(
      1,
      2
    ),
    "Key2",
    MAP(
    3,
    4
    )
  ) as testmarks;

select * from map_map_table;

set hive.vectorized.execution.enabled=false;

select * from map_map_table;
