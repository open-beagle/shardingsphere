/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.infra.executor.sql.execute.engine.driver.jdbc;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.infra.database.DatabaseHolder;
import org.apache.shardingsphere.infra.database.ThreadLocalManager;
import org.apache.shardingsphere.infra.database.metadata.DataSourceMetaData;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.executor.kernel.model.ExecutorCallback;
import org.apache.shardingsphere.infra.executor.sql.context.SQLUnit;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.SQLExecutionUnit;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.SQLExecutorExceptionHandler;
import org.apache.shardingsphere.infra.executor.sql.hook.SPISQLExecutionHook;
import org.apache.shardingsphere.infra.executor.sql.hook.SQLExecutionHook;
import org.apache.shardingsphere.infra.executor.sql.process.ExecuteProcessEngine;
import org.apache.shardingsphere.infra.executor.sql.process.model.ExecuteProcessConstants;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereColumn;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereTable;
import org.apache.shardingsphere.infra.replace.SqlReplaceEngine;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC executor callback.
 *
 * @param <T> class type of return value
 */
@RequiredArgsConstructor
public abstract class JDBCExecutorCallback<T> implements ExecutorCallback<JDBCExecutionUnit, T> {

    private static final Map<String, DataSourceMetaData> CACHED_DATASOURCE_METADATA = new ConcurrentHashMap<>();

    @Getter
    private final DatabaseType databaseType;

    private final SQLStatement sqlStatement;

    private final boolean isExceptionThrown;

    @Override
    public final Collection<T> execute(final Collection<JDBCExecutionUnit> executionUnits, final boolean isTrunkThread, final Map<String, Object> dataMap) throws SQLException {
        // TODO It is better to judge whether need sane result before execute, can avoid exception thrown
        Collection<T> result = new LinkedList<>();
        for (JDBCExecutionUnit each : executionUnits) {
            T executeResult = execute(each, isTrunkThread, dataMap);
            if (null != executeResult) {
                result.add(executeResult);
            }
        }
        return result;
    }

    /*
     * To make sure SkyWalking will be available at the next release of ShardingSphere, a new plugin should be provided to SkyWalking project if this API changed.
     *
     * @see <a href="https://github.com/apache/skywalking/blob/master/docs/en/guides/Java-Plugin-Development-Guide.md#user-content-plugin-development-guide">Plugin Development Guide</a>
     */
    private T execute(final JDBCExecutionUnit jdbcExecutionUnit, final boolean isTrunkThread, final Map<String, Object> dataMap) throws SQLException {
        SQLExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        DataSourceMetaData dataSourceMetaData = getDataSourceMetaData(jdbcExecutionUnit.getStorageResource().getConnection().getMetaData());
        SQLExecutionHook sqlExecutionHook = new SPISQLExecutionHook();
        try {
            SQLUnit sqlUnit = jdbcExecutionUnit.getExecutionUnit().getSqlUnit();
            sqlExecutionHook.start(jdbcExecutionUnit.getExecutionUnit().getDataSourceName(), sqlUnit.getSql(), sqlUnit.getParameters(), dataSourceMetaData, isTrunkThread, dataMap);

            // SQL 重写 2023年1月5日 update by pengsong
//            String rawSql = sqlUnit.getSql();
            // 先进行字符替换
//            String distSql = SqlReplaceEngine.replaceSql(SQLReplaceTypeEnum.REPLACE, rawSql, SQLStrReplaceTriggerModeEnum.BACK_END, null);
            // 再进行SQL重写
//            distSql = SqlReplaceEngine.replaceSql(SQLReplaceTypeEnum.REWRITE, distSql, jdbcExecutionUnit.getExecutionUnit().getDataSourceName(), null);
            // 16进制数据重写
//            String databaseType = jdbcExecutionUnit.getStorageResource().getConnection().getClientInfo("ApplicationName");
//            if (StringUtils.isEmpty(databaseType)) {
//                databaseType = jdbcExecutionUnit.getStorageResource().getConnection().getClientInfo("dbname");
//            }
//            List blobColumnList = this.getBlobColumnList(distSql, jdbcExecutionUnit);
//            distSql = SqlReplaceEngine.replaceSql(SQLReplaceTypeEnum.BINARY, distSql, databaseType, blobColumnList);

//            T result = executeSQL(distSql, jdbcExecutionUnit.getStorageResource(), jdbcExecutionUnit.getConnectionMode());

            T result = executeSQL(sqlUnit.getSql(), jdbcExecutionUnit.getStorageResource(), jdbcExecutionUnit.getConnectionMode());
            sqlExecutionHook.finishSuccess();
            finishReport(dataMap, jdbcExecutionUnit);
            return result;
        } catch (final SQLException ex) {
            if (!isTrunkThread) {
                return null;
            }
            Optional<T> saneResult = getSaneResult(sqlStatement);
            if (saneResult.isPresent()) {
                return saneResult.get();
            }
            sqlExecutionHook.finishFailure(ex);
            SQLExecutorExceptionHandler.handleException(ex);
            return null;
        }
    }

    private List getBlobColumnList(String sql, final JDBCExecutionUnit jdbcExecutionUnit) {
        List blobColumnList = new ArrayList<>();
        try {
            SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(sql, DbType.mysql);
            com.alibaba.druid.sql.ast.SQLStatement statement = parser.parseStatement();
            if (statement instanceof SQLInsertStatement) {
                SQLInsertStatement insertStatement = (com.alibaba.druid.sql.ast.statement.SQLInsertStatement) statement;
                List<SQLExpr> columns = insertStatement.getColumns();
                SQLName tableName = insertStatement.getTableName();
                columns.forEach(item -> {
                    if(item instanceof SQLIdentifierExpr){
                        String columnName = ((SQLIdentifierExpr) item).getName();
                        if (isKingbaseBlob(jdbcExecutionUnit, tableName.getSimpleName(), columnName)) {
                            blobColumnList.add(item);
                        }
                    }
                });
            } else if (statement instanceof SQLUpdateStatement) {
                SQLUpdateStatement updateStatement = (SQLUpdateStatement) statement;
                List<SQLUpdateSetItem> columns = updateStatement.getItems();
                SQLName tableName = updateStatement.getTableName();
                columns.forEach(item -> {
                    String columnName = item.getColumn().toString();
                    if (isKingbaseBlob(jdbcExecutionUnit, tableName.getSimpleName(), columnName)) {
                        blobColumnList.add(columnName);
                    }
                });
            }
        } catch (Exception ex) {
            return blobColumnList;
        }
        return blobColumnList;
    }

    private boolean isKingbaseBlob(JDBCExecutionUnit jdbcExecutionUnit, String tableName, String fieldName) {
        try {
            String databaseName = ThreadLocalManager.getBackendConnectionDatabase();
            Connection connection = jdbcExecutionUnit.getStorageResource().getConnection();
            String dbname = connection.getClientInfo("dbname");
            if (Objects.equals(dbname, "Kingbase8")) {
                ShardingSphereDatabase database = DatabaseHolder.getDatabase(databaseName);
                if (Objects.nonNull(database)) {
                    for (ShardingSphereSchema shardingSphereSchema : database.getSchemas().values()) {
                        for (ShardingSphereTable table : shardingSphereSchema.getTables().values()) {
                            if (Objects.equals(table.getName().toLowerCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT))) {
                                for (ShardingSphereColumn column : table.getColumns().values()) {
                                    if (Objects.equals(column.getName().toLowerCase(Locale.ROOT), fieldName.toLowerCase(Locale.ROOT))) {
                                        return Objects.equals(column.getDataType(), 17) || Objects.equals(column.getDataType(), 1001);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return false;
    }

    private DataSourceMetaData getDataSourceMetaData(final DatabaseMetaData databaseMetaData) throws SQLException {
        String url = databaseMetaData.getURL();
        if (CACHED_DATASOURCE_METADATA.containsKey(url)) {
            return CACHED_DATASOURCE_METADATA.get(url);
        }
        DataSourceMetaData result = databaseType.getDataSourceMetaData(url, databaseMetaData.getUserName());
        CACHED_DATASOURCE_METADATA.put(url, result);
        return result;
    }

    private void finishReport(final Map<String, Object> dataMap, final SQLExecutionUnit executionUnit) {
        if (dataMap.containsKey(ExecuteProcessConstants.EXECUTE_ID.name())) {
            ExecuteProcessEngine.finish(dataMap.get(ExecuteProcessConstants.EXECUTE_ID.name()).toString(), executionUnit);
        }
    }

    protected abstract T executeSQL(String sql, Statement statement, ConnectionMode connectionMode) throws SQLException;

    protected abstract Optional<T> getSaneResult(SQLStatement sqlStatement);
}
