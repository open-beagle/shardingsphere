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

package org.apache.shardingsphere.proxy.frontend.mysql.command.query.text.query;

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
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLServerErrorCode;
import org.apache.shardingsphere.db.protocol.mysql.packet.MySQLPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.admin.MySQLComSetOptionPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.text.MySQLTextResultSetRowPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.text.query.MySQLComQueryPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.generic.MySQLErrPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.generic.MySQLOKPacket;
import org.apache.shardingsphere.db.protocol.packet.DatabasePacket;
import org.apache.shardingsphere.infra.database.DatabaseHolder;
import org.apache.shardingsphere.infra.database.ThreadLocalManager;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.database.type.DatabaseTypeFactory;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.driver.jdbc.JDBCExecutionUnit;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereColumn;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.database.schema.decorator.model.ShardingSphereTable;
import org.apache.shardingsphere.infra.replace.SqlReplaceEngine;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.parser.rule.SQLParserRule;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.response.header.ResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.update.ClientEncodingResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.update.UpdateResponseHeader;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandler;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandlerFactory;
import org.apache.shardingsphere.proxy.frontend.command.executor.QueryCommandExecutor;
import org.apache.shardingsphere.proxy.frontend.command.executor.ResponseType;
import org.apache.shardingsphere.proxy.frontend.mysql.command.query.builder.ResponsePacketBuilder;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.DeleteStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.EmptyStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.UpdateStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * COM_QUERY command packet executor for MySQL.
 */
@Slf4j
public final class MySQLComQueryPacketExecutor implements QueryCommandExecutor {

    private final TextProtocolBackendHandler textProtocolBackendHandler;
    
    private final int characterSet;
    
    @Getter
    private volatile ResponseType responseType;
    
    private int currentSequenceId;
    
    public MySQLComQueryPacketExecutor(final MySQLComQueryPacket packet, final ConnectionSession connectionSession) throws SQLException {
//        DatabaseType databaseType = DatabaseTypeFactory.getInstance("MySQL");
//        SQLStatement sqlStatement = parseSql(packet.getSql(), databaseType);
//        textProtocolBackendHandler = areMultiStatements(connectionSession, sqlStatement, packet.getSql()) ? new MySQLMultiStatementsHandler(connectionSession, sqlStatement, packet.getSql())
//                : TextProtocolBackendHandlerFactory.newInstance(databaseType,packet.getSql(), () -> Optional.of(sqlStatement), connectionSession);
//        characterSet = connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_CHARACTER_SET_ATTRIBUTE_KEY).get().getId();

        // 前向SQL 替换  2023年2月6日 update by pengsong
        String rawSql = packet.getSql();
        String distSql = SqlReplaceEngine.replaceSql(SQLReplaceTypeEnum.REPLACE, rawSql, SQLStrReplaceTriggerModeEnum.FRONT_END, null);
        distSql = SqlReplaceEngine.hexToChar(distSql, getBlobColumnList(distSql));
        DatabaseType databaseType = DatabaseTypeFactory.getInstance("MySQL");
        SQLStatement sqlStatement = parseSql(distSql, databaseType);
        textProtocolBackendHandler = areMultiStatements(connectionSession, sqlStatement, distSql) ? new MySQLMultiStatementsHandler(connectionSession, sqlStatement, distSql)
                : TextProtocolBackendHandlerFactory.newInstance(databaseType,distSql, () -> Optional.of(sqlStatement), connectionSession);
        characterSet = connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_CHARACTER_SET_ATTRIBUTE_KEY).get().getId();
    }

    private List<String> getBlobColumnList(String sql) {
        List<String> blobColumnList = new ArrayList<>();
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
                        if (isKingbaseBlob(tableName.getSimpleName(), columnName)) {
                            blobColumnList.add(((SQLIdentifierExpr) item).getSimpleName());
                        }
                    }
                });
            } else if (statement instanceof SQLUpdateStatement) {
                SQLUpdateStatement updateStatement = (SQLUpdateStatement) statement;
                List<SQLUpdateSetItem> columns = updateStatement.getItems();
                SQLName tableName = updateStatement.getTableName();
                columns.forEach(item -> {
                    String columnName = item.getColumn().toString();
                    if (isKingbaseBlob(tableName.getSimpleName(), columnName)) {
                        blobColumnList.add(columnName);
                    }
                });
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
                                    log.info("------- {} dataType is {}", fieldName, column.getDataType());
                                    return Objects.equals(column.getDataType(), -2);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        log.info("------- {} is not byteArray", fieldName);
        return false;
    }
    
    private SQLStatement parseSql(final String sql, final DatabaseType databaseType) {
        if (sql.isEmpty()) {
            return new EmptyStatement();
        }
        MetaDataContexts metaDataContexts = ProxyContext.getInstance().getContextManager().getMetaDataContexts();
        Optional<SQLParserRule> sqlParserRule = metaDataContexts.getMetaData().getGlobalRuleMetaData().findSingleRule(SQLParserRule.class);
        Preconditions.checkState(sqlParserRule.isPresent());
        return sqlParserRule.get().getSQLParserEngine(databaseType.getType()).parse(sql, false);
    }
    
    private boolean areMultiStatements(final ConnectionSession connectionSession, final SQLStatement sqlStatement, final String sql) {
        // TODO Multi statements should be identified by SQL Parser instead of checking if sql contains ";".
        return connectionSession.getAttributeMap().hasAttr(MySQLConstants.MYSQL_OPTION_MULTI_STATEMENTS)
                && MySQLComSetOptionPacket.MYSQL_OPTION_MULTI_STATEMENTS_ON == connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_OPTION_MULTI_STATEMENTS).get()
                && (sqlStatement instanceof UpdateStatement || sqlStatement instanceof DeleteStatement) && sql.contains(";");
    }
    
    @Override
    public Collection<DatabasePacket<?>> execute() throws SQLException {
        ResponseHeader responseHeader = textProtocolBackendHandler.execute();
        if (responseHeader instanceof QueryResponseHeader) {
            return processQuery((QueryResponseHeader) responseHeader);
        }
        responseType = ResponseType.UPDATE;
        return responseHeader instanceof UpdateResponseHeader ? processUpdate((UpdateResponseHeader) responseHeader) : processClientEncoding((ClientEncodingResponseHeader) responseHeader);
    }
    
    private Collection<DatabasePacket<?>> processQuery(final QueryResponseHeader queryResponseHeader) {
        responseType = ResponseType.QUERY;
        Collection<DatabasePacket<?>> result = ResponsePacketBuilder.buildQueryResponsePackets(queryResponseHeader, characterSet);
        currentSequenceId = result.size();
        return result;
    }
    
    private Collection<DatabasePacket<?>> processUpdate(final UpdateResponseHeader updateResponseHeader) {
        return ResponsePacketBuilder.buildUpdateResponsePackets(updateResponseHeader);
    }
    
    private Collection<DatabasePacket<?>> processClientEncoding(final ClientEncodingResponseHeader clientEncodingResponseHeader) {
        Optional<String> currentCharsetValue = clientEncodingResponseHeader.getCurrentCharsetValue();
        if (currentCharsetValue.isPresent()) {
            return Collections.singletonList(new MySQLOKPacket(1, 0, 0));
        }
        return Collections.singletonList(new MySQLErrPacket(1, MySQLServerErrorCode.ER_UNKNOWN_CHARACTER_SET, clientEncodingResponseHeader.getInputValue()));
    }
    
    @Override
    public boolean next() throws SQLException {
        return textProtocolBackendHandler.next();
    }
    
    @Override
    public MySQLPacket getQueryRowPacket() throws SQLException {
        return new MySQLTextResultSetRowPacket(++currentSequenceId, textProtocolBackendHandler.getRowData());
    }
    
    @Override
    public void close() throws SQLException {
        textProtocolBackendHandler.close();
    }
}
