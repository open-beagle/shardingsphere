/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.apache.shardingsphere.infra.metadata.database.schema.loader.dialect;

import java.sql.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.shardingsphere.infra.metadata.database.schema.loader.common.DataTypeLoader;
import org.apache.shardingsphere.infra.metadata.database.schema.loader.model.ColumnMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.loader.model.IndexMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.loader.model.SchemaMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.loader.model.TableMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.loader.spi.DialectSchemaMetaDataLoader;

import com.google.common.collect.Lists;

import lombok.extern.slf4j.Slf4j;

/**
 * Schema metadata loader for GBase. by wuwanli
 */
@Slf4j
public final class GBaseSchemaMetaDataLoader implements DialectSchemaMetaDataLoader {
    private static final String TABLE_META_DATA_SQL_NO_ORDER =
        "SELECT T1.OWNER AS TABLE_SCHEMA,T1.TABNAME AS TABLE_NAME,T2.COLNAME AS COLUMN_NAME,T2.COLTYPENAME AS DATA_TYPE,T2.COLNO AS COLUMN_ID FROM SYSTABLES T1 INNER JOIN SYSCOLUMNSEXT T2 ON T1.TABID = T2.TABID WHERE T1.TABID > 100 AND T1.OWNER =  ?";

    private static final String ORDER_BY_COLUMN_ID = " ORDER BY COLUMN_ID";

    private static final String TABLE_META_DATA_SQL = TABLE_META_DATA_SQL_NO_ORDER + ORDER_BY_COLUMN_ID;

    private static final String TABLE_META_DATA_SQL_IN_TABLES =
        TABLE_META_DATA_SQL_NO_ORDER + " AND TABLE_NAME IN (%s)" + ORDER_BY_COLUMN_ID;

    private static final String INDEX_META_DATA_SQL =
        "SELECT T1.OWNER AS TABLE_SCHEMA,T2.TABNAME AS TABLE_NAME,T1.IDXNAME AS INDEX_NAME FROM SYSINDICES T1 INNER JOIN SYSTABLES T2 ON T1.TABID = T2.TABID WHERE T1.TABID > 100 AND T2.OWNER = ? AND T2.TABNAME IN (%S)";
    private static final String PRIMARY_KEY_META_DATA_SQL =
        "select T1.OWNER as TABLE_SCHEMA,T2.TABNAME as TABLE_NAME,T3.COLNAME as COLUMN_NAME from SYSCONSTRAINTS T1 inner join SYSTABLES T2 on T1.TABID = T2.TABID and T1.OWNER = T2.OWNER inner join SYSCOLUMNS T3 on T2.TABID = T3.TABID inner join SYSINDEXES T4 on T1.IDXNAME = T4.IDXNAME and T1.TABID = T4.TABID and T1.OWNER = T4.OWNER and(T3.COLNO = T4.PART1 or T3.COLNO = T4.PART2 or T3.COLNO = T4.PART3 or T3.COLNO = T4.PART4 or T3.COLNO = T4.PART5 or T3.COLNO = T4.PART6 or T3.COLNO = T4.PART7 or T3.COLNO = T4.PART8 or T3.COLNO = T4.PART9 or T3.COLNO = T4.PART10 or T3.COLNO = T4.PART11 or T3.COLNO = T4.PART12 or T3.COLNO = T4.PART13 or T3.COLNO = T4.PART14  or T3.COLNO = T4.PART15 or T3.COLNO = T4.PART16 ) where T1.TABID > 100 and T1.CONSTRTYPE = 'P' and T1.OWNER = ?";
    private static final String PRIMARY_KEY_META_DATA_SQL_IN_TABLES =
        PRIMARY_KEY_META_DATA_SQL + " AND A.TABLE_NAME IN (%s)";

    private static final int COLLATION_START_MAJOR_VERSION = 12;

    private static final int COLLATION_START_MINOR_VERSION = 2;

    private static final int IDENTITY_COLUMN_START_MINOR_VERSION = 1;

    private static final int MAX_EXPRESSION_SIZE = 1000;

    @Override
    public Collection<SchemaMetaData> load(final DataSource dataSource, final Collection<String> tables,
        final String defaultSchemaName) throws SQLException {
        Map<String, Collection<ColumnMetaData>> columnMetaDataMap = new HashMap<>(tables.size(), 1);
        Map<String, Collection<IndexMetaData>> indexMetaDataMap = new HashMap<>(tables.size(), 1);
        try (Connection connection = dataSource.getConnection()) {
            for (List<String> each : Lists.partition(new ArrayList<>(tables), MAX_EXPRESSION_SIZE)) {
                columnMetaDataMap.putAll(loadColumnMetaDataMap(connection, each));
                indexMetaDataMap.putAll(loadIndexMetaData(connection, each));
            }
        }
        Collection<TableMetaData> tableMetaDataList = new LinkedList<>();
        for (Entry<String, Collection<ColumnMetaData>> entry : columnMetaDataMap.entrySet()) {
            tableMetaDataList.add(new TableMetaData(entry.getKey(), entry.getValue(),
                indexMetaDataMap.getOrDefault(entry.getKey(), Collections.emptyList()), Collections.emptyList()));
        }
        return Collections.singletonList(new SchemaMetaData(defaultSchemaName, tableMetaDataList));
    }

    private Map<String, Collection<ColumnMetaData>> loadColumnMetaDataMap(final Connection connection,
        final Collection<String> tables) throws SQLException {
        Map<String, Collection<ColumnMetaData>> result = new HashMap<>(tables.size(), 1);
        try (PreparedStatement preparedStatement =
            connection.prepareStatement(getTableMetaDataSQL(tables, connection.getMetaData()))) {
            Map<String, Integer> dataTypes = DataTypeLoader.load(connection.getMetaData());
            appendDataType(dataTypes);
            Map<String, Collection<String>> tablePrimaryKeys = loadTablePrimaryKeys(connection, tables);
            preparedStatement.setString(1, connection.getSchema());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    ColumnMetaData columnMetaData = loadColumnMetaData(dataTypes, resultSet,
                        tablePrimaryKeys.getOrDefault(tableName, Collections.emptyList()), connection.getMetaData());
                    if (!result.containsKey(tableName)) {
                        result.put(tableName, new LinkedList<>());
                    }
                    result.get(tableName).add(columnMetaData);
                }
            }
        }
        return result;
    }

    // add by wuwanli debug
    private void appendDataType(final Map<String, Integer> dataTypes) {
        dataTypes.put("TEXT", Types.LONGVARCHAR);
        dataTypes.put("DOUBLE PRECISION", Types.DOUBLE);
        dataTypes.put("IMAGE", Types.LONGVARBINARY);
        dataTypes.put("DEC", Types.DECIMAL);
    }

    private ColumnMetaData loadColumnMetaData(final Map<String, Integer> dataTypeMap, final ResultSet resultSet,
        final Collection<String> primaryKeys, final DatabaseMetaData databaseMetaData) throws SQLException {
        String columnName = resultSet.getString("COLUMN_NAME");
        String dataType = getOriginalDataType(resultSet.getString("DATA_TYPE"));
        if (dataTypeMap.get(dataType) == null) {
            // add by wuwanli debug
            log.error("dataType not exist! dataType=" + dataType);
        }
        boolean primaryKey = primaryKeys.contains(columnName);
        boolean generated =
            versionContainsIdentityColumn(databaseMetaData) && "YES".equals(resultSet.getString("IDENTITY_COLUMN"));
        // TODO need to support caseSensitive when version < 12.2.
        boolean caseSensitive =
            versionContainsCollation(databaseMetaData) && resultSet.getString("COLLATION").endsWith("_CS");
        return new ColumnMetaData(columnName, dataTypeMap.get(dataType), primaryKey, generated, caseSensitive);
    }

    private String getOriginalDataType(final String dataType) {
        int index = dataType.indexOf("(");
        if (index > 0) {
            return dataType.substring(0, index);
        }
        return dataType;
    }

    private String getTableMetaDataSQL(final Collection<String> tables, final DatabaseMetaData databaseMetaData)
        throws SQLException {
        StringBuilder stringBuilder = new StringBuilder(28);
        if (versionContainsIdentityColumn(databaseMetaData)) {
            stringBuilder.append(", IDENTITY_COLUMN");
        }
        if (versionContainsCollation(databaseMetaData)) {
            stringBuilder.append(", COLLATION");
        }
        String collation = stringBuilder.toString();
        return tables.isEmpty() ? String.format(TABLE_META_DATA_SQL, collation)
            : String.format(TABLE_META_DATA_SQL_IN_TABLES, collation,
                tables.stream().map(each -> String.format("'%s'", each)).collect(Collectors.joining(",")));
    }

    private boolean versionContainsCollation(final DatabaseMetaData databaseMetaData) throws SQLException {
        return databaseMetaData.getDatabaseMajorVersion() >= COLLATION_START_MAJOR_VERSION
            && databaseMetaData.getDatabaseMinorVersion() >= COLLATION_START_MINOR_VERSION;
    }

    private boolean versionContainsIdentityColumn(final DatabaseMetaData databaseMetaData) throws SQLException {
        return databaseMetaData.getDatabaseMajorVersion() >= COLLATION_START_MAJOR_VERSION
            && databaseMetaData.getDatabaseMinorVersion() >= IDENTITY_COLUMN_START_MINOR_VERSION;
    }

    private Map<String, Collection<IndexMetaData>> loadIndexMetaData(final Connection connection,
        final Collection<String> tableNames) throws SQLException {
        Map<String, Collection<IndexMetaData>> result = new HashMap<>(tableNames.size(), 1);
        try (PreparedStatement preparedStatement = connection.prepareStatement(getIndexMetaDataSQL(tableNames))) {
            preparedStatement.setString(1, connection.getSchema());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String indexName = resultSet.getString("INDEX_NAME");
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (!result.containsKey(tableName)) {
                        result.put(tableName, new LinkedList<>());
                    }
                    result.get(tableName).add(new IndexMetaData(indexName));
                }
            }
        }
        return result;
    }

    private String getIndexMetaDataSQL(final Collection<String> tableNames) {
        return String.format(INDEX_META_DATA_SQL,
            tableNames.stream().map(each -> String.format("'%s'", each)).collect(Collectors.joining(",")));
    }

    private Map<String, Collection<String>> loadTablePrimaryKeys(final Connection connection,
        final Collection<String> tableNames) throws SQLException {
        Map<String, Collection<String>> result = new HashMap<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(getPrimaryKeyMetaDataSQL(tableNames))) {
            preparedStatement.setString(1, connection.getSchema());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    String tableName = resultSet.getString("TABLE_NAME");
                    result.computeIfAbsent(tableName, k -> new LinkedList<>()).add(columnName);
                }
            }
        }
        return result;
    }

    private String getPrimaryKeyMetaDataSQL(final Collection<String> tables) {
        return tables.isEmpty() ? PRIMARY_KEY_META_DATA_SQL : String.format(PRIMARY_KEY_META_DATA_SQL_IN_TABLES,
            tables.stream().map(each -> String.format("'%s'", each)).collect(Collectors.joining(",")));
    }

    @Override
    public String getType() {
        return "gbasedbt-sqli";
    }
}
