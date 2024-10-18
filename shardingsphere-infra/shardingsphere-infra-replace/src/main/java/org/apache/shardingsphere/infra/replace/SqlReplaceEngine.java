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

package org.apache.shardingsphere.infra.replace;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLHexExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author SmileCircle
 */
@Slf4j
public class SqlReplaceEngine {
    
    private static ConcurrentHashMap<String, SqlReplace> ENGINE = new ConcurrentHashMap<>(10);
    
    public static void init() {
        ServiceLoader<SqlReplace> loader = ServiceLoader.load(SqlReplace.class);
        loader.forEach(engine -> {
            engine.init();
            ENGINE.put(engine.getType().getCode(), engine);
            log.info("===> {} 已加载", engine.getType().getName());
        });
    }
    
    /**
     *
     * @param type
     * @param sql
     * @return
     */
    public static String replaceSql(@Nonnull SQLReplaceTypeEnum type, @Nonnull final String sql, @Nullable Object obj, List<String> blobColumnList) {
        String rawSql = sql;
        SqlReplace engine = ENGINE.get(type.getCode());
        if (Objects.nonNull(engine)) {
            try {
                String distSql = engine.replace(rawSql, obj, blobColumnList);
                if (!Objects.equals(sql, distSql)) {
                    log.info("---------> 使用 {} 替换前 -> {}", type.getName(), rawSql);
                    log.info("---------> 替换后 -> {}", distSql);
                }
                return distSql;
            } catch (Exception | Error e) {
                log.info("---> 异常SQL : {}", sql);
                log.error("SQL替换异常", e);
                return sql;
            }
        }
        return sql;
    }

    public static String hexToChar(String sql, List<String> getBlobColumnList) {
        log.info("---------> hexToChar 前 -> {}", sql);
        String distSql = transferHexToChinesePg(sql, getBlobColumnList);
        log.info("---------> hexToChar 后 -> {}", distSql);
        return distSql;
    }

    public static boolean isHexString(String input) {
        String uppercaseInput = input.toUpperCase();
        boolean isHex = !uppercaseInput.isEmpty() && uppercaseInput.matches("[0-9A-F]+");
        return isHex;
    }

    private static String transferHexToChinesePg(String distSql, List<String> blobColumnList) {
        SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(distSql, DbType.mysql);
        com.alibaba.druid.sql.ast.SQLStatement statement = parser.parseStatement();

        // 字段和数据对应列表
        // sql语句hex字段中文替换
        AtomicReference<String> executeSql = new AtomicReference<>(distSql);
        if (statement instanceof SQLInsertStatement) {
            SQLInsertStatement insertStatement = (com.alibaba.druid.sql.ast.statement.SQLInsertStatement) statement;
            List<SQLExpr> columns = insertStatement.getColumns();
            Map<Integer, String> columnMap = columns.stream().collect(Collectors.toMap(columns::indexOf, item -> item.toString()));

            LinkedHashMap<Integer, List<String>> valueMap = new LinkedHashMap<>();
            List<SQLInsertStatement.ValuesClause> values = insertStatement.getValuesList();
            values.forEach(valuesClause -> {
                List<SQLExpr> valueList = valuesClause.getValues();
                for (int i = 0; i < valueList.size(); i++) {
                    SQLExpr sqlExpr = valueList.get(i);
                    if (sqlExpr instanceof SQLHexExpr) {
                        String value = ((SQLHexExpr) sqlExpr).getHex();
                        List<String> dataList = valueMap.get(i);
                        if (dataList != null && dataList.size() > 0) {
                            dataList.add(value);
                        } else {
                            dataList = new ArrayList<>();
                            dataList.add(value);
                        }
                        valueMap.put(i, dataList);
                    }
                }
            });

            columnMap.forEach((key, item) -> {
                if (!blobColumnList.contains(item)) {
                    List<String> valueList = valueMap.get(key);
                    if (valueList != null && valueList.size() > 0) {
                        valueList.forEach(valueData -> {
                            if (isHexString(valueData)) {
                                String chineseStr = "'" + hexStr2Str(valueData).replaceAll("'", "''") + "'";
                                String newSql = executeSql.get();
                                int index = newSql.indexOf(valueData);
                                String frontSql = newSql.substring(0, index - 2);
                                String backSql = newSql.substring(index + valueData.length());
                                if (backSql.startsWith("'")) {
                                    backSql = backSql.substring(1);
                                }
                                executeSql.set(frontSql + chineseStr + backSql);
                            }
                        });
                    }
                }
            });
        } else if (statement instanceof SQLUpdateStatement) {
            SQLUpdateStatement updateStatement = (SQLUpdateStatement) statement;
            List<SQLUpdateSetItem> items = updateStatement.getItems();
            items.forEach(item -> {
                String columnName = String.valueOf(item.getColumn());
                SQLExpr value = item.getValue();
                if (!blobColumnList.contains(columnName) && value instanceof SQLHexExpr) {
                    String valueData = ((SQLHexExpr) value).getHex();
                    if (isHexString(valueData)) {
                        String chineseStr = "'" + hexStr2Str(valueData).replaceAll("'", "''") + "'";
                        int index = distSql.indexOf(valueData);
                        String frontSql = distSql.substring(0, index - 2);
                        // 去除hex后面的'符合
                        String backSql = distSql.substring(index + valueData.length());
                        if (backSql.startsWith("'")) {
                            backSql = backSql.substring(1);
                        }
                        executeSql.set(frontSql + chineseStr + backSql);
                    }
                }
            });
        } else if (statement instanceof SQLSelectStatement) {
            SchemaStatVisitor visitor = new SchemaStatVisitor(DbType.valueOf(DbType.mysql.name()));
            statement.accept(visitor);
            List<TableStat.Condition> conditions = visitor.getConditions();
            if (conditions != null && conditions.size() > 0) {
                conditions.forEach(item -> {
                    String columnName = item.getColumn().getName();
                    List<Object> values = item.getValues();
                    if (!blobColumnList.contains(columnName)) {
                        if (values != null && values.size() > 0) {
                            values.forEach(value -> {
                                if (value instanceof byte[]) {
                                    byte[] valueByte = (byte[]) value;
                                    String valueData = new String(valueByte);
                                    String chineseStr = "'" + valueData.replaceAll("'", "''") + "'";
                                    String hexStr = bytesToHex(valueByte);
                                    int index = distSql.indexOf(hexStr);
                                    String frontSql = distSql.substring(0, index - 2);
                                    // 去除hex后面的'符合
                                    String backSql = distSql.substring(index + hexStr.length());
                                    if (backSql.startsWith("'")) {
                                        backSql = backSql.substring(1);
                                    }
                                    executeSql.set(frontSql + chineseStr + backSql);
                                }
                            });
                        }
                    }
                });
            }
        }
        return executeSql.get();
    }

    // 16进制直接转换成为汉字
    public static String hexStr2Str(String hexStr) {
        String str = "0123456789ABCDEF";
        char[] hexs = hexStr.toCharArray();
        byte[] bytes = new byte[hexStr.length() / 2]; // 1个byte数值 -> 两个16进制字符
        int n;
        for (int i = 0; i < bytes.length; i++) {
            n = str.indexOf(hexs[2 * i]) * 16;
            n += str.indexOf(hexs[2 * i + 1]);
            bytes[i] = (byte) (n & 0xff);
        }
        return new String(bytes);
    }

    private static String bytesToHex(byte[] bytes) {
        String hex = new BigInteger(1, bytes).toString(16);
        return hex.toUpperCase();
    }
}
