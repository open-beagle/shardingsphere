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

package org.apache.shardingsphere.proxy.frontend.mysql.command.query.binary.execute;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.google.common.base.Preconditions;
import lombok.Getter;
import org.apache.shardingsphere.db.protocol.binary.BinaryCell;
import org.apache.shardingsphere.db.protocol.binary.BinaryRow;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLBinaryColumnType;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.db.protocol.mysql.packet.MySQLPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.binary.execute.MySQLBinaryResultSetRowPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.binary.execute.MySQLComStmtExecutePacket;
import org.apache.shardingsphere.db.protocol.packet.DatabasePacket;
import org.apache.shardingsphere.infra.binder.SQLStatementContextFactory;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.type.TableAvailable;
import org.apache.shardingsphere.infra.database.DatabaseHolder;
import org.apache.shardingsphere.infra.database.ThreadLocalManager;
import org.apache.shardingsphere.infra.database.type.DatabaseTypeEngine;
import org.apache.shardingsphere.infra.database.type.DatabaseTypeFactory;
import org.apache.shardingsphere.infra.executor.check.SQLCheckEngine;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereColumn;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereTable;
import org.apache.shardingsphere.infra.replace.SqlReplaceEngine;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.parser.rule.SQLParserRule;
import org.apache.shardingsphere.proxy.backend.communication.DatabaseCommunicationEngineFactory;
import org.apache.shardingsphere.proxy.backend.communication.SQLStatementDatabaseHolder;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.JDBCDatabaseCommunicationEngine;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.response.data.QueryResponseCell;
import org.apache.shardingsphere.proxy.backend.response.data.QueryResponseRow;
import org.apache.shardingsphere.proxy.backend.response.data.impl.BinaryQueryResponseCell;
import org.apache.shardingsphere.proxy.backend.response.header.ResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.update.UpdateResponseHeader;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandler;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandlerFactory;
import org.apache.shardingsphere.proxy.frontend.command.executor.QueryCommandExecutor;
import org.apache.shardingsphere.proxy.frontend.command.executor.ResponseType;
import org.apache.shardingsphere.proxy.frontend.mysql.command.query.builder.ResponsePacketBuilder;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.tcl.TCLStatement;
import org.apache.shardingsphere.transaction.utils.AutoCommitUtils;

import java.sql.SQLException;
import java.util.*;

/**
 * COM_STMT_EXECUTE command executor for MySQL.
 */
public final class MySQLComStmtExecuteExecutor implements QueryCommandExecutor {
    
    private final JDBCDatabaseCommunicationEngine databaseCommunicationEngine;
    
    private final TextProtocolBackendHandler textProtocolBackendHandler;
    
    private final int characterSet;
    
    @Getter
    private volatile ResponseType responseType;
    
    private int currentSequenceId;
    
    public MySQLComStmtExecuteExecutor(final MySQLComStmtExecutePacket packet, final ConnectionSession connectionSession) throws SQLException {
//        String databaseName = connectionSession.getDatabaseName();
//        MetaDataContexts metaDataContexts = ProxyContext.getInstance().getContextManager().getMetaDataContexts();
//        Optional<SQLParserRule> sqlParserRule = metaDataContexts.getMetaData().getGlobalRuleMetaData().findSingleRule(SQLParserRule.class);
//        Preconditions.checkState(sqlParserRule.isPresent());
//        SQLStatement sqlStatement = sqlParserRule.get().getSQLParserEngine(
//                DatabaseTypeEngine.getTrunkDatabaseTypeName(metaDataContexts.getMetaData().getDatabases().get(databaseName).getProtocolType())).parse(packet.getSql(), true);
//        if (AutoCommitUtils.needOpenTransaction(sqlStatement)) {
//            connectionSession.getBackendConnection().handleAutoCommit();
//        }
//        SQLStatementContext<?> sqlStatementContext = SQLStatementContextFactory.newInstance(metaDataContexts.getMetaData().getDatabases(), packet.getParameters(),
//                sqlStatement, connectionSession.getDefaultDatabaseName());
//        // TODO optimize SQLStatementDatabaseHolder
//        if (sqlStatementContext instanceof TableAvailable) {
//            ((TableAvailable) sqlStatementContext).getTablesContext().getDatabaseName().ifPresent(SQLStatementDatabaseHolder::set);
//        }
//        SQLCheckEngine.check(sqlStatement, Collections.emptyList(), getRules(databaseName), databaseName, metaDataContexts.getMetaData().getDatabases(), connectionSession.getGrantee());
//        characterSet = connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_CHARACTER_SET_ATTRIBUTE_KEY).get().getId();
//        // TODO Refactor the following branch
//        if (sqlStatement instanceof TCLStatement) {
//            databaseCommunicationEngine = null;
//            textProtocolBackendHandler =
//                    TextProtocolBackendHandlerFactory.newInstance(DatabaseTypeFactory.getInstance("MySQL"), packet.getSql(), () -> Optional.of(sqlStatement), connectionSession);
//            return;
//        }
//        textProtocolBackendHandler = null;
//        databaseCommunicationEngine = DatabaseCommunicationEngineFactory.getInstance().newBinaryProtocolInstance(sqlStatementContext, packet.getSql(), packet.getParameters(),
//                connectionSession.getBackendConnection());

        // 前向SQL 替换  2023年2月6日 update by pengsong
        String rawSql = packet.getSql();
        String distSql = SqlReplaceEngine.replaceSql(SQLReplaceTypeEnum.REPLACE, rawSql, SQLStrReplaceTriggerModeEnum.FRONT_END, null);

        String databaseName = connectionSession.getDatabaseName();
        MetaDataContexts metaDataContexts = ProxyContext.getInstance().getContextManager().getMetaDataContexts();
        Optional<SQLParserRule> sqlParserRule = metaDataContexts.getMetaData().getGlobalRuleMetaData().findSingleRule(SQLParserRule.class);
        Preconditions.checkState(sqlParserRule.isPresent());
        SQLStatement sqlStatement = sqlParserRule.get().getSQLParserEngine(
                DatabaseTypeEngine.getTrunkDatabaseTypeName(metaDataContexts.getMetaData().getDatabases().get(databaseName).getProtocolType())).parse(distSql, true);
        if (AutoCommitUtils.needOpenTransaction(sqlStatement)) {
            connectionSession.getBackendConnection().handleAutoCommit();
        }
//        List<Integer> blobColumnList = getBlobColumnList(distSql);
//        if(blobColumnList != null && blobColumnList.size() > 0) {
//            List<Object> parameters = packet.getParameters();
//            for (Integer index : blobColumnList) {
//                if(parameters.size() >= index) {
//                    String param = parameters.get(index).toString();
//                    if(SqlReplaceEngine.isHexString(param)) {
//                        parameters.set(index, SqlReplaceEngine.hexStr2Str(param));
//                    }
//                }
//            }
//        }


        SQLStatementContext<?> sqlStatementContext = SQLStatementContextFactory.newInstance(metaDataContexts.getMetaData().getDatabases(), packet.getParameters(),
                sqlStatement, connectionSession.getDefaultDatabaseName());
        // TODO optimize SQLStatementDatabaseHolder
        if (sqlStatementContext instanceof TableAvailable) {
            ((TableAvailable) sqlStatementContext).getTablesContext().getDatabaseName().ifPresent(SQLStatementDatabaseHolder::set);
        }
        SQLCheckEngine.check(sqlStatement, Collections.emptyList(), getRules(databaseName), databaseName, metaDataContexts.getMetaData().getDatabases(), connectionSession.getGrantee());
        characterSet = connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_CHARACTER_SET_ATTRIBUTE_KEY).get().getId();
        // TODO Refactor the following branch
        if (sqlStatement instanceof TCLStatement) {
            databaseCommunicationEngine = null;
            textProtocolBackendHandler =
                    TextProtocolBackendHandlerFactory.newInstance(DatabaseTypeFactory.getInstance("MySQL"), distSql, () -> Optional.of(sqlStatement), connectionSession);
            return;
        }
        textProtocolBackendHandler = null;
        databaseCommunicationEngine = DatabaseCommunicationEngineFactory.getInstance().newBinaryProtocolInstance(sqlStatementContext, distSql, packet.getParameters(),
                connectionSession.getBackendConnection());
    }

    private List<Integer> getBlobColumnList(String sql) {
        List<Integer> blobColumnList = new ArrayList<>();
        try {
            SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(sql, DbType.mysql);
            com.alibaba.druid.sql.ast.SQLStatement statement = parser.parseStatement();
            if (statement instanceof SQLInsertStatement) {
                SQLInsertStatement insertStatement = (com.alibaba.druid.sql.ast.statement.SQLInsertStatement) statement;
                List<SQLExpr> columns = insertStatement.getColumns();
                SQLName tableName = insertStatement.getTableName();
                for (int i = 0; i < columns.size(); i++) {
                    SQLExpr item = columns.get(i);
                    if(item instanceof SQLIdentifierExpr){
                        String columnName = ((SQLIdentifierExpr) item).getName();
                        if (isKingbaseBlob(tableName.getSimpleName(), columnName)) {
                            blobColumnList.add(i);
                        }
                    }
                }
            } else if (statement instanceof SQLUpdateStatement) {
                SQLUpdateStatement updateStatement = (SQLUpdateStatement) statement;
                List<SQLUpdateSetItem> columns = updateStatement.getItems();
                SQLName tableName = updateStatement.getTableName();
                for (int i = 0; i < columns.size(); i++) {
                    SQLUpdateSetItem item = columns.get(i);
                    String columnName = item.getColumn().toString();
                    if (isKingbaseBlob(tableName.getSimpleName(), columnName)) {
                        blobColumnList.add(i);
                    }
                }
            }
        } catch (Exception ex) {
            return blobColumnList;
        }
        return blobColumnList;
    }

    private boolean isKingbaseBlob(String tableName, String fieldName) {
        try {
            String databaseName = ThreadLocalManager.getBackendConnectionDatabase();
            ShardingSphereDatabase database = DatabaseHolder.getDatabase(databaseName);
            if (Objects.nonNull(database)) {
                for (ShardingSphereSchema shardingSphereSchema : database.getSchemas().values()) {
                    for (ShardingSphereTable table : shardingSphereSchema.getTables().values()) {
                        if (Objects.equals(table.getName().toLowerCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT))) {
                            for (ShardingSphereColumn column : table.getColumns().values()) {
                                if (Objects.equals(column.getName().toLowerCase(Locale.ROOT), fieldName.toLowerCase(Locale.ROOT))) {
                                    return Objects.equals(column.getDataType(), 17) || Objects.equals(column.getDataType(), 1001) || Objects.equals(column.getDataType(), -2);
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
    
    private static Collection<ShardingSphereRule> getRules(final String databaseName) {
        Collection<ShardingSphereRule> result;
        result = new LinkedList<>(ProxyContext.getInstance().getContextManager().getMetaDataContexts().getMetaData().getDatabases().get(databaseName).getRuleMetaData().getRules());
        result.addAll(ProxyContext.getInstance().getContextManager().getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getRules());
        return result;
    }
    
    @Override
    public Collection<DatabasePacket<?>> execute() throws SQLException {
        ResponseHeader responseHeader = null != databaseCommunicationEngine ? databaseCommunicationEngine.execute() : textProtocolBackendHandler.execute();
        return responseHeader instanceof QueryResponseHeader ? processQuery((QueryResponseHeader) responseHeader) : processUpdate((UpdateResponseHeader) responseHeader);
    }
    
    private Collection<DatabasePacket<?>> processQuery(final QueryResponseHeader queryResponseHeader) {
        responseType = ResponseType.QUERY;
        Collection<DatabasePacket<?>> result = ResponsePacketBuilder.buildQueryResponsePackets(queryResponseHeader, characterSet);
        currentSequenceId = result.size();
        return result;
    }
    
    private Collection<DatabasePacket<?>> processUpdate(final UpdateResponseHeader updateResponseHeader) {
        responseType = ResponseType.UPDATE;
        return ResponsePacketBuilder.buildUpdateResponsePackets(updateResponseHeader);
    }
    
    @Override
    public boolean next() throws SQLException {
        return null != databaseCommunicationEngine && databaseCommunicationEngine.next();
    }
    
    @Override
    public MySQLPacket getQueryRowPacket() throws SQLException {
        QueryResponseRow queryResponseRow = databaseCommunicationEngine.getQueryResponseRow();
        return new MySQLBinaryResultSetRowPacket(++currentSequenceId, createBinaryRow(queryResponseRow));
    }
    
    private BinaryRow createBinaryRow(final QueryResponseRow queryResponseRow) {
        List<BinaryCell> result = new ArrayList<>(queryResponseRow.getCells().size());
        for (QueryResponseCell each : queryResponseRow.getCells()) {
            result.add(new BinaryCell(MySQLBinaryColumnType.valueOfJDBCType(((BinaryQueryResponseCell) each).getJdbcType()), each.getData()));
        }
        return new BinaryRow(result);
    }
    
    @Override
    public void close() throws SQLException {
        if (null != databaseCommunicationEngine) {
            databaseCommunicationEngine.close();
        }
        if (null != textProtocolBackendHandler) {
            textProtocolBackendHandler.close();
        }
    }
}
