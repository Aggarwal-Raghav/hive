#!/usr/bin/env bash
# ==============================================================================
# Hive Environment Configuration
# ==============================================================================

# --- Directory Paths ----------------------------------------------------------
export HADOOP_HOME="$HOME/Desktop/setup/hadoop"
export HIVE_HOME="$HOME/Desktop/setup/hive"
export HIVE_CONF_DIR="$HOME/Desktop/setup/hive/conf"
export HIVE_LOG_DIR="/tmp/hadoop/hive"

# --- Common JVM Options -------------------------------------------------------
COMMON_JVM_OPTS="-Djava.io.tmpdir=/tmp \
  -Dhive.log.dir=$HIVE_LOG_DIR \
  -Dhive.log.file=${SERVICE}.log \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=$HIVE_LOG_DIR/heapdump-${SERVICE}-%p-%t.hprof \
  -XX:+ExitOnOutOfMemoryError \
  -XX:NativeMemoryTracking=summary \
  -XX:StartFlightRecording=disk=true,dumponexit=true,filename=$HIVE_LOG_DIR/recording-${SERVICE}-%p-%t.jfr,maxsize=250M,maxage=1d,settings=profile \
  -Xlog:gc*,gc+age=trace,safepoint:file=$HIVE_LOG_DIR/gc-${SERVICE}-%p-%t.log:time,uptimemillis,pid,level,tags:filecount=5,filesize=64M \
  -XX:+UseG1GC \
  -agentpath:$HOME/Desktop/software/async-profiler-4.4-macos/lib/libasyncProfiler.dylib=start,event=cpu,alloc=2m,file=$HIVE_LOG_DIR/${SERVICE}_profile.html"

# --- Service-Specific Configurations ------------------------------------------
case "$SERVICE" in
  beeline)
    export HADOOP_HEAPSIZE=128
    export HADOOP_CLIENT_OPTS="$HADOOP_CLIENT_OPTS $COMMON_JVM_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
    ;;

  metastore)
    export HADOOP_HEAPSIZE=2048
    export HADOOP_CLIENT_OPTS="$HADOOP_CLIENT_OPTS $COMMON_JVM_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5006"
    ;;

  hiveserver2)
    export HADOOP_HEAPSIZE=2048
    export HADOOP_CLIENT_OPTS="$HADOOP_CLIENT_OPTS $COMMON_JVM_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5007"
    ;;
esac
