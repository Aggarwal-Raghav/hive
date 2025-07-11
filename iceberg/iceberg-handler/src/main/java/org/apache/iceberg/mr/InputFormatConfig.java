/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr;

import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.SerDeUtils;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.util.SerializationUtil;

public class InputFormatConfig {

  private InputFormatConfig() {
  }

  // configuration values for Iceberg input formats
  public static final String REUSE_CONTAINERS = "iceberg.mr.reuse.containers";
  public static final String SKIP_RESIDUAL_FILTERING = "skip.residual.filtering";
  public static final String AS_OF_TIMESTAMP = "iceberg.mr.as.of.time";
  public static final String FILTER_EXPRESSION = "iceberg.mr.filter.expression";
  public static final String GROUPING_PARTITION_COLUMNS = "iceberg.mr.grouping.partition.columns";
  public static final String IN_MEMORY_DATA_MODEL = "iceberg.mr.in.memory.data.model";
  public static final String READ_SCHEMA = "iceberg.mr.read.schema";
  public static final String SNAPSHOT_ID = "iceberg.mr.snapshot.id";
  public static final String SNAPSHOT_ID_INTERVAL_FROM = "iceberg.mr.snapshot.id.interval.from";
  public static final String SPLIT_SIZE = "iceberg.mr.split.size";
  public static final String SCHEMA_AUTO_CONVERSION = "iceberg.mr.schema.auto.conversion";
  public static final String TABLE_IDENTIFIER = "iceberg.mr.table.identifier";
  public static final String TABLE_LOCATION = "iceberg.mr.table.location";
  public static final String TABLE_SCHEMA = "iceberg.mr.table.schema";
  public static final String PARTITION_SPEC = "iceberg.mr.table.partition.spec";
  public static final String SERIALIZED_TABLE_PREFIX = "iceberg.mr.serialized.table.";
  public static final String TABLE_CATALOG_PREFIX = "iceberg.mr.table.catalog.";
  public static final String LOCALITY = "iceberg.mr.locality";
  public static final String WRITE_FANOUT_ENABLED = "write.fanout.enabled";

  public static final String CTAS_TABLE_NAME = "iceberg.mr.ctas.table.name";
  public static final String SELECTED_COLUMNS = "iceberg.mr.selected.columns";
  public static final String FETCH_VIRTUAL_COLUMNS = "iceberg.mr.fetch.virtual.columns";
  public static final String EXTERNAL_TABLE_PURGE = "external.table.purge";

  public static final String CONFIG_SERIALIZATION_DISABLED = "iceberg.mr.config.serialization.disabled";
  public static final boolean CONFIG_SERIALIZATION_DISABLED_DEFAULT = true;
  public static final String OPERATION_TYPE_PREFIX = "iceberg.mr.operation.type.";
  public static final String OUTPUT_TABLES = "iceberg.mr.output.tables";
  public static final String OUTPUT_TABLE_SNAPSHOT_REF = "iceberg.mr.output.table.snapshot.ref";
  public static final String COMMIT_TABLE_THREAD_POOL_SIZE = "iceberg.mr.commit.table.thread.pool.size";
  public static final int COMMIT_TABLE_THREAD_POOL_SIZE_DEFAULT = 10;
  public static final String COMMIT_FILE_THREAD_POOL_SIZE = "iceberg.mr.commit.file.thread.pool.size";
  public static final int COMMIT_FILE_THREAD_POOL_SIZE_DEFAULT = 10;
  public static final String WRITE_TARGET_FILE_SIZE = "iceberg.mr.write.target.file.size";

  public static final String CASE_SENSITIVE = "iceberg.mr.case.sensitive";
  public static final boolean CASE_SENSITIVE_DEFAULT = true;

  public static final String CATALOG_NAME = "iceberg.catalog";
  public static final String HADOOP_CATALOG = "hadoop.catalog";
  public static final String HADOOP_TABLES = "hadoop.tables";
  public static final String HIVE_CATALOG = "hive.catalog";
  public static final String ICEBERG_SNAPSHOTS_TABLE_SUFFIX = ".snapshots";
  public static final String SNAPSHOT_TABLE = "iceberg.snapshots.table";
  public static final String SNAPSHOT_TABLE_SUFFIX = "__snapshots";

  public static final String CATALOG_CONFIG_PREFIX = "iceberg.catalog.";
  public static final String CATALOG_TYPE_TEMPLATE = "iceberg.catalog.%s.type";
  public static final String CATALOG_WAREHOUSE_TEMPLATE = "iceberg.catalog.%s.warehouse";
  public static final String CATALOG_CLASS_TEMPLATE = "iceberg.catalog.%s.catalog-impl";
  public static final String CATALOG_DEFAULT_CONFIG_PREFIX = "iceberg.catalog-default.";

  public enum InMemoryDataModel {
    HIVE,
    GENERIC // Default data model is of Iceberg Generics
  }

  public static class ConfigBuilder {
    private final Configuration conf;

    public ConfigBuilder(Configuration conf) {
      this.conf = conf;
      // defaults
      conf.setBoolean(SKIP_RESIDUAL_FILTERING, false);
      conf.setBoolean(CASE_SENSITIVE, CASE_SENSITIVE_DEFAULT);
      conf.setBoolean(REUSE_CONTAINERS, false);
      conf.setBoolean(LOCALITY, false);
    }

    public Configuration conf() {
      return conf;
    }

    public ConfigBuilder filter(Expression expression) {
      conf.set(FILTER_EXPRESSION, SerializationUtil.serializeToBase64(expression));
      return this;
    }

    public ConfigBuilder project(Schema schema) {
      conf.set(READ_SCHEMA, SchemaParser.toJson(schema));
      return this;
    }

    public ConfigBuilder schema(Schema schema) {
      conf.set(TABLE_SCHEMA, SchemaParser.toJson(schema));
      return this;
    }

    public ConfigBuilder select(List<String> columns) {
      conf.setStrings(SELECTED_COLUMNS, columns.toArray(new String[0]));
      return this;
    }

    public ConfigBuilder select(String... columns) {
      conf.setStrings(SELECTED_COLUMNS, columns);
      return this;
    }

    public ConfigBuilder readFrom(TableIdentifier identifier) {
      conf.set(TABLE_IDENTIFIER, identifier.toString());
      return this;
    }

    public ConfigBuilder readFrom(String location) {
      conf.set(TABLE_LOCATION, location);
      return this;
    }

    public ConfigBuilder reuseContainers(boolean reuse) {
      conf.setBoolean(InputFormatConfig.REUSE_CONTAINERS, reuse);
      return this;
    }

    public ConfigBuilder caseSensitive(boolean caseSensitive) {
      conf.setBoolean(InputFormatConfig.CASE_SENSITIVE, caseSensitive);
      return this;
    }

    public ConfigBuilder snapshotId(long snapshotId) {
      conf.setLong(SNAPSHOT_ID, snapshotId);
      return this;
    }

    public ConfigBuilder asOfTime(long asOfTime) {
      conf.setLong(AS_OF_TIMESTAMP, asOfTime);
      return this;
    }

    public ConfigBuilder splitSize(long splitSize) {
      conf.setLong(SPLIT_SIZE, splitSize);
      return this;
    }

    /**
     * If this API is called. The input splits constructed will have host location information
     */
    public ConfigBuilder preferLocality() {
      conf.setBoolean(LOCALITY, true);
      return this;
    }

    public ConfigBuilder useHiveRows() {
      conf.set(IN_MEMORY_DATA_MODEL, InMemoryDataModel.HIVE.name());
      return this;
    }

    /**
     * Compute platforms pass down filters to data sources. If the data source cannot apply some filters, or only
     * partially applies the filter, it will return the residual filter back. If the platform can correctly apply the
     * residual filters, then it should call this api. Otherwise the current api will throw an exception if the passed
     * in filter is not completely satisfied.
     */
    public ConfigBuilder skipResidualFiltering() {
      conf.setBoolean(InputFormatConfig.SKIP_RESIDUAL_FILTERING, true);
      return this;
    }
  }

  public static Schema tableSchema(Configuration conf) {
    return schema(conf, InputFormatConfig.TABLE_SCHEMA);
  }

  public static Schema readSchema(Configuration conf) {
    return schema(conf, InputFormatConfig.READ_SCHEMA);
  }

  public static String[] selectedColumns(Configuration conf) {
    String readColumns = conf.get(InputFormatConfig.SELECTED_COLUMNS);
    if (readColumns == null || readColumns.isEmpty()) {
      return null;
    }

    return readColumns.split(conf.get(serdeConstants.COLUMN_NAME_DELIMITER, String.valueOf(SerDeUtils.COMMA)));
  }

  public static boolean fetchVirtualColumns(Configuration conf) {
    return conf.getBoolean(InputFormatConfig.FETCH_VIRTUAL_COLUMNS, false);
  }

  /**
   * Get Hadoop config key of a catalog property based on catalog name
   * @param catalogName catalog name
   * @param catalogProperty catalog property, can be any custom property,
   *                        a commonly used list of properties can be found
   *                        at {@link org.apache.iceberg.CatalogProperties}
   * @return Hadoop config key of a catalog property for the catalog name
   */
  public static String catalogPropertyConfigKey(String catalogName, String catalogProperty) {
    return String.format("%s%s.%s", CATALOG_CONFIG_PREFIX, catalogName, catalogProperty);
  }

  private static Schema schema(Configuration conf, String key) {
    String json = conf.get(key);
    return json == null ? null : SchemaParser.fromJson(json);
  }

}
