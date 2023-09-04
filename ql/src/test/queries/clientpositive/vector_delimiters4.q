set hive.fetch.task.conversion=none;
set hive.vectorized.execution.enabled=true;

create EXTERNAL table `table4` as
select
  'bob' as name,
  map(
      "Map_Key1",
        named_struct(
            'Id',
            'Id_Value1',
            'Name',
            'Name_Value1'
        ),
      "Map_Key2",
        named_struct(
            'Id',
            'Id_Value2',
            'Name',
            'Name_Value2'
        )
  ) as testmarks;

select * from table4;

set hive.vectorized.execution.enabled=false;

select * from table4;
