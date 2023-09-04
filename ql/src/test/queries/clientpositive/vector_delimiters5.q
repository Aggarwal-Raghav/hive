set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

create EXTERNAL table `table4` as
select
  'bob'                                           as name,
  array(
    MAP(
      "Key1",
      "Value1"
      ),
    MAP(
      "Key2",
      "Value2"
    )
  )                                               as testmarks;

select * from table4;

set hive.vectorized.execution.enabled=false;

select * from table4;
