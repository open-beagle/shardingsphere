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

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.HexUtil;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLHexExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
        return transferHexToChinesePg(sql, getBlobColumnList);
    }

    public static boolean isHexString(String input) {
        String uppercaseInput = input.toUpperCase();
        boolean isHex = !uppercaseInput.isEmpty() && uppercaseInput.matches("[0-9A-F]+");
        return isHex;
    }

//    public static void main(String[] args) {
//        String sql = "UPDATE XLJZ_XLJZ_XLZXJLBDJ SET DB_MC = '一大队', GX_RQ = '2024-10-18 21:49:55.991', GXR_ID = '3000', GXR_XM = '局管理员', XM = '罗永梅', BH = '5201032023000018', XLZXS = '罗永亮', ZXRQ = '2024-10-17', QTZK = '正常', QXZK = '正常', ZZL = '正常', YZL = '正常', RJGX = '正常', ZKGSFTY = '01', GRTC = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', SHZCXT = '正常', XLCY = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', XSZK = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', CBZD = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', ZXFS = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', JDRYZS = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', ZXJSJDC = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', ZXXG = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', XLWTLX = '01', AQFXQX = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', XLWTJY = '正常正常 正常 正常', ZXZRYJ = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', BZ = x'E6ADA3E5B8B8E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8' WHERE ID = 'c0228ce2ceac446baab06585f9457222'";
//        System.out.println(hexToChar(sql, new ArrayList<>()));
//    }

    public static void main(String[] args) {
        String sql = "INSERT INTO GZJJD_JYGZ.XLJZ_XLJZ_XLZXJLBDJ( \n" +
                "     ID,\n" +
                "     SBM,\n" +
                "     SMC,\n" +
                "     DB_MC,\n" +
                "     DB_BM,\n" +
                "     CJ_RQ,\n" +
                "     CJR_ID,\n" +
                "     CJR_XM,\n" +
                "     GX_RQ,\n" +
                "     GXR_ID,\n" +
                "     GXR_XM,\n" +
                "     XM,\n" +
                "     BH,\n" +
                "     XLZXS,\n" +
                "     ZXRQ,\n" +
                "     QTZK,\n" +
                "     QXZK,\n" +
                "     ZZL,\n" +
                "     YZL,\n" +
                "     RJGX,\n" +
                "     ZKGSFTY,\n" +
                "     GRTC,\n" +
                "     SHZCXT,\n" +
                "     XLCY,\n" +
                "     XSZK,\n" +
                "     CBZD,\n" +
                "     ZXFS,\n" +
                "     JDRYZS,\n" +
                "     ZXJSJDC,\n" +
                "     ZXXG,\n" +
                "     XLWTLX,\n" +
                "     AQFXQX,\n" +
                "     XLWTJY,\n" +
                "     ZXZRYJ,\n" +
                "     BZ\n" +
                "    )\n" +
                "     VALUES\n" +
                "     (\n" +
                "     'af9c3ec1124042f9930985762cc70c30',\n" +
                "     '520000',\n" +
                "     '贵州省戒毒管理局',\n" +
                "     '男性专管大队',\n" +
                "     '5200000007',\n" +
                "     '2024-11-06 22:26:36.708',\n" +
                "     '3000',\n" +
                "     '局管理员',\n" +
                "     null,\n" +
                "     null,\n" +
                "     null,\n" +
                "     '罗平',\n" +
                "     '5201062023004420',\n" +
                "     '罗永亮',\n" +
                "     '2024-11-06',\n" +
                "     '正常',\n" +
                "     '正常',\n" +
                "     '正常',\n" +
                "     '正常',\n" +
                "     '正常',\n" +
                "     '01',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     '01',\n" +
                "     '正常\n" +
                "正常\n" +
                "正常',\n" +
                "     x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8',\n" +
                "     x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8',\n" +
                "     x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8'\n" +
                "     )";
        String sql2 = "update mytable set col2=x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8', col4 = x'E6ADA3E5B8B80D0AE6ADA3E5B8B80D0AE6ADA3E5B8B8' where 1=1 ";
        String res  = transferHexToChinesePg(sql, Lists.newArrayList("XLWTJY","BZ"));
//        String res  = transferHexToChinesePg(sql2, Lists.newArrayList("col2","col4"));
        System.out.println(res);
    }

    private static String getRealName(String name) {
        // todo 还需要测试dm在大小写配置敏感时，对小写通过引号的支持
        // 当列名、表名含有特殊字符时，需要去除
        if (!StringUtils.isEmpty(name) &&
                (name.startsWith("`") && name.endsWith("`")) || (name.startsWith("\"") && name.endsWith("\"")) || (name.startsWith("'") && name.endsWith("'"))) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }
    private static String transferHexToChinesePg(String distSql, List<String> blobColumnList) {
        log.info("-------- 当前的sql：{}，是blob、bytea的字段有：{}", distSql, JSON.toJSONString(blobColumnList));
        SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(distSql, DbType.mysql);
        com.alibaba.druid.sql.ast.SQLStatement statement = parser.parseStatement();

        // 字段和数据对应列表
        // sql语句hex字段中文替换
        AtomicReference<String> executeSql = new AtomicReference<>(distSql);
        boolean needModifyFlag = false;
        if (statement instanceof SQLInsertStatement) {
            SQLInsertStatement insertStatement = (com.alibaba.druid.sql.ast.statement.SQLInsertStatement) statement;
            List<SQLExpr> columns = insertStatement.getColumns();
            List<SQLInsertStatement.ValuesClause> values = insertStatement.getValuesList();
            for (int j = 0; j < values.size(); j++) {
                SQLInsertStatement.ValuesClause valuesClause = values.get(j);
                List<SQLExpr> valueList = valuesClause.getValues();
                for (int i = 0; i < valueList.size(); i++) {
                    SQLExpr sqlExpr = valueList.get(i);
                    if (sqlExpr instanceof SQLHexExpr) {
                        String value = ((SQLHexExpr) sqlExpr).getHex();
                        if (CharSequenceUtil.isBlank(value) || isHexString(value)) {
                            SQLExpr sqlColumnExpr = columns.get(i);
                            if (sqlColumnExpr instanceof SQLIdentifierExpr) {
                                SQLIdentifierExpr sqlIdentifierExpr = (SQLIdentifierExpr) sqlColumnExpr;
                                String finalColumnNames = CharSequenceUtil.blankToDefault(getRealName(sqlIdentifierExpr.getSimpleName()), "");
                                boolean blobColumnFlag = blobColumnList.stream().anyMatch(col-> finalColumnNames.equalsIgnoreCase(col));
                                if (!blobColumnFlag) {
                                    // 暂定 由16进制换成 字符串
                                    if (CharSequenceUtil.isBlank(value)) {
                                        valuesClause.getValues().set(i, null);
                                        needModifyFlag = true;
                                    }else {
                                        value = HexUtil.decodeHexStr(value);
                                        valuesClause.getValues().set(i, new SQLCharExpr(value));
                                        needModifyFlag = true;
                                    }
                                }
                                // 如果是blob字段，统一到JdbcExecutorCallBack类中执行
                            }
                        }
                    }
                }
            }
        } else if (statement instanceof SQLUpdateStatement) {
            SQLUpdateStatement updateStatement = (SQLUpdateStatement) statement;
            List<SQLUpdateSetItem> items = updateStatement.getItems();
            for (int i = 0; i < items.size(); i++) {
                SQLUpdateSetItem item = items.get(i);
                String columnName = String.valueOf(item.getColumn());
                SQLExpr value = item.getValue();
                if (!blobColumnList.contains(columnName) && value instanceof SQLHexExpr) {
                    String valueData = ((SQLHexExpr) value).getHex();
                    if (CharSequenceUtil.isBlank(valueData) ) {
                        item.setValue(null);
                        needModifyFlag = true;
                    }else {
                        if (isHexString(valueData)) {
                            valueData = HexUtil.decodeHexStr(valueData);
                            item.setValue(new SQLCharExpr(valueData));
                            needModifyFlag = true;
                        }
                    }
                }
            }
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
            return executeSql.get();
        }
        if (needModifyFlag) {
            // statement.toString 会自动格式化sql，目前找不到对应的方法去除
            // 只有进行了16进制转换中文的，才返回
            return statement.toString();
        }else {
            // 啥都没处理的，返回原sql
            return executeSql.get();
        }
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
