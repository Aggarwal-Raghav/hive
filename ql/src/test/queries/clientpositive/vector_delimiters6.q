set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

create EXTERNAL table `table6` as
select
  'bob'                                           as name,
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
  )                                               as testmarks;

select * from table6;

set hive.vectorized.execution.enabled=false;

select * from table6;
