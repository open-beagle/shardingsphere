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

package org.apache.shardingsphere.infra.replace.reactive;

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
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.infra.replace.SqlReplace;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 二进制替换
 *
 * @author SmileCircle
 */
@Slf4j
public class SqlBinaryReplaceEngine implements SqlReplace {

    /**
     * 匹配 插入/修改SQL 中含有 x开头的 二进制
     */
    private static final String REGEX_X = "^(insert|update|select).*x'.*'.*";
    /**
     * 寻找 x''中间的内容
     */
    private static final String REGEX_FIND_X_DATA = "(?<=x').*?(?=')";

    /**
     * 匹配字符串是否是16进制
     */
    private static final String REGEX_HEX = "[0-9A-F]+";

    private static final String ENABLE_BINARY_REPLACE = System.getenv("ENABLE_BINARY_REPLACE");


    @Override
    public String replace(String sql, Object obj, List<String> blobColumnList) {
        return replaceSql(sql, Objects.nonNull(obj) ? (String) obj : "", blobColumnList);
    }

    @Override
    public SQLReplaceTypeEnum getType() {
        return SQLReplaceTypeEnum.BINARY;
    }

    @Override
    public void init() {

    }

    /**
     * 替换SQL 中的X''
     *
     * @param sql
     * @return
     */
    private static String replaceSql(String sql, String databaseType, List<String> blobColumnList) {
        if (!Objects.equals(ENABLE_BINARY_REPLACE, "false")) {
            if (isHexSql(sql)) {
                if (Objects.equals(databaseType, "Kingbase8 JDBC Driver") || Objects.equals(databaseType, "PostgreSQL")) {
                    return handleHexStringWithPG(sql, blobColumnList);
                } else if (Objects.equals(databaseType, "DAMENG")) {
                    // dm
                    return handleHexStringWithDM(sql, blobColumnList);

                }
            }
        }
        return sql;
    }

    /**
     * 针对dm数据库，对十六进制的数据处理，将x'0a2d3f00转换成0x0a1d格式
     *
     * @param sql
     * @return
     */
    private static String handleHexStringWithDM(String sql, List<String> blobColumnList) {
        Pattern pattern = Pattern.compile(REGEX_FIND_X_DATA, Pattern.DOTALL | Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(sql);
        List<String> hexList = new ArrayList<>();
        while (matcher.find()) {
            String hex = matcher.group();
            if (isHex(hex)) {
                log.info(" ========= hex data: {}", hex);
                hexList.add(hex);
            }

        }

        String distSql = sql;
        // 替换x''
        for (String hex : hexList) {
            String hexRegex = "x'" + hex + "'";
            int index = distSql.indexOf(hexRegex);
            String frontSql = distSql.substring(0, index);
            // 去除hex后面的'符合
            String backSql = distSql.substring(index + hexRegex.length());
            String binaryString = "0x" + hex;
            distSql = frontSql + binaryString + backSql;
        }
        // 将16进制转成中文
        distSql = transferHexToChinese(distSql, blobColumnList);
        return distSql;
    }

    private static String transferHexToChinese(String distSql, List<String> blobColumnList) {
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
                        if(dataList != null && dataList.size() > 0){
                            dataList.add(value);
                        }else{
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
                    if(valueList != null && valueList.size() > 0){
                        valueList.forEach(valueData -> {
                            if (isHexString(valueData)) {
                                String chineseStr = "'" + hexStr2Str(valueData).replaceAll("'","''") + "'";
                                String newSql = executeSql.get();
                                int index = newSql.indexOf(valueData);
                                String frontSql = newSql.substring(0, index - 2);
                                String backSql = newSql.substring(index + valueData.length());
                                if(backSql.startsWith("'")){
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
                        String chineseStr = "'" + hexStr2Str(valueData).replaceAll("'","''") + "'";
                        int index = distSql.indexOf(valueData);
                        String frontSql = distSql.substring(0, index - 2);
                        // 去除hex后面的'符合
                        String backSql = distSql.substring(index + valueData.length());
                        if(backSql.startsWith("'")){
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
        if(conditions != null && conditions.size() > 0) {
            conditions.forEach(item -> {
                String columnName = item.getColumn().getName();
                List<Object> values = item.getValues();
                if (!blobColumnList.contains(columnName)) {
                    if(values != null && values.size() > 0){
                        values.forEach(value -> {
                            if(value instanceof byte[]){
                                byte[] valueByte = (byte[]) value;
                                String valueData = new String(valueByte);
                                String chineseStr = "'" + valueData.replaceAll("'","''") + "'";
                                String hexStr = bytesToHex(valueByte);
                                int index = distSql.indexOf(hexStr);
                                String frontSql = distSql.substring(0, index - 2);
                                // 去除hex后面的'符合
                                String backSql = distSql.substring(index + hexStr.length());
                                if(backSql.startsWith("'")){
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
        byte[] bytes = new byte[hexStr.length() / 2]; //1个byte数值 -> 两个16进制字符
        int n;
        for (int i = 0; i < bytes.length; i++) {
            n = str.indexOf(hexs[2 * i]) * 16;
            n += str.indexOf(hexs[2 * i + 1]);
            bytes[i] = (byte) (n & 0xff);
        }
        return new String(bytes);
    }

    public static boolean isHexString(String input) {
        String uppercaseInput = input.toUpperCase();
        boolean isHex = !uppercaseInput.isEmpty() && uppercaseInput.matches(REGEX_HEX);
        return isHex;
    }

    private static String bytesToHex(byte[] bytes) {
        String hex = new BigInteger(1, bytes).toString(16);
        return hex.toUpperCase();
    }

    /**
     * 校验是否是 INSERT UPDATE 的SQL 并且 包含二进制数据
     *
     * @param sql 需要校验的SQL
     * @return 是否是 INSERT UPDATE 的SQL 并且 包含二进制数据
     */
    public static boolean isHexSql(String sql) {
        if (StringUtils.isNotBlank(sql.toLowerCase(Locale.ROOT))) {
            sql = sql.toLowerCase();
            Pattern pattern = Pattern.compile(REGEX_X, Pattern.DOTALL | Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(sql);
            return matcher.matches();
        }
        return false;
    }

    public static String handleHexStringWithPG(final String sql) {
        Pattern pattern = Pattern.compile(REGEX_FIND_X_DATA, Pattern.DOTALL | Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(distSql);
        List<String> hexList = new ArrayList<>();
        while (matcher.find()) {
            String hex = matcher.group();
            if (isHex(hex)) {
                log.info(" ========= hex data: {}", hex);
                hexList.add(hex);
            }

        }
        // 替换x''
        for (String hex : hexList) {
            int index = distSql.indexOf(hex);
            String frontSql = distSql.substring(0, index - 2);
            String backSql = distSql.substring(index + hex.length());
            String binaryString = escapeBytes(hex2Byte(hex));
            distSql = frontSql + "E'" + binaryString + backSql;
        }
        return distSql;
    }

    /**
     * 16进制字符串转byte数组
     *
     * @param src
     * @return
     */
    public static byte[] hex2Byte(String src) {
        byte[] baKeyword = new byte[src.length() / 2];
        for (int i = 0; i < baKeyword.length; i++) {
            try {
                // 当byte要转化为int的时候，高的24位必然会补1，这样，其二进制补码其实已经不一致了，&0xff可以将高的24位置为0，低8位保持原样。这样做的目的就是为了保证二进制数据的一致性。
                baKeyword[i] = (byte) (0xff & Integer.parseInt(src.substring(i * 2, i * 2 + 2), 16));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return baKeyword;
    }

    /**
     * byte数组转二进制标准字符串
     *
     * @param bytes
     * @return
     */
    private static String escapeBytes(final byte[] bytes) {
        int[] intArray = new int[bytes.length];
        int i = 0;
        for (byte b : bytes) {
            intArray[i++] = b & 0xff;
        }
        StringBuilder sb = new StringBuilder();
        for (int intVal : intArray) {
            switch (intVal) {
                case 0:
                    sb.append("\\\\000");
                    break;
                case 39:
                    sb.append("\\\\047");
                    break;
                case 92:
                    sb.append("\\\\134");
                    break;
                default:
                    if ((intVal >= 0 && intVal <= 31) || (intVal >= 127 && intVal <= 255)) {
                        String octalStr = Integer.toOctalString(intVal);
                        octalStr = String.format("%03d", Integer.valueOf(octalStr));
                        sb.append("\\\\").append(octalStr);
                    } else {
                        sb.append(new String(intToByteArray(intVal), StandardCharsets.UTF_8));
                    }
                    break;
            }
        }
        return sb.toString();
    }

    public static boolean isHex(String value) {
        if (startWith(value, '-')) {
            return false;
        } else {
            int index = 0;
            if (!value.startsWith("0x", index) && !value.startsWith("0X", index)) {
                if (value.startsWith("#", index)) {
                    ++index;
                }
            } else {
                index += 2;
            }

            try {
                new BigInteger(value.substring(index), 16);
                return true;
            } catch (NumberFormatException var3) {
                return false;
            }
        }
    }

    public static boolean startWith(CharSequence str, char c) {
        if (StringUtils.isBlank(str)) {
            return false;
        } else {
            return c == str.charAt(0);
        }
    }

    public static byte[] intToByteArray(int i) {
        byte[] result = new byte[4];
        result[0] = (byte) ((i >> 24) & 0xFF);
        result[1] = (byte) ((i >> 16) & 0xFF);
        result[2] = (byte) ((i >> 8) & 0xFF);
        result[3] = (byte) (i & 0xFF);
        List<Byte> byteList = new ArrayList<>();
        for (byte b : result) {
            if (!Objects.equals(b, (byte) 0x0)) {
                byteList.add(b);
            }
        }
        byte[] rb = new byte[byteList.size()];
        for (int t = 0; t < byteList.size(); t++) {
            rb[t] = byteList.get(t);
        }
        return rb;
    }

    public static void main(String[] args) {
//        String hex = "ACED5C300573725C30156F72672E71756172747A2E6A6F62646174616D61709FB083E8BFA9B0EB025C305C3078725C30266F72672E71756172747A2E7574696C732E737472696E676B65796469727479666C61676D61708208E8E3FBE55D28025C30017A5C3013616C6C6F77737472616E7369656E746461746178725C301D6F72672E71756172747A2E7574696C732E6469727479666C61676D617013E62EAD28760AEE025C30027A5C300564697274796C5C30036D6170745C300F6C6A6176612F7574696C2F6D61703B78700173725C30116A6176612E7574696C2E686173686D61700507FAE1E31660F1035C3002665C300A6C6F6164666163746F72695C30097468726573686F6C6478703F405C305C305C305C305C300C77085C305C305C30105C305C305C3001745C300D6A6F625F706172616D5F6B657973725C302E696F2E72656E72656E2E6D6F64756C65732E6A6F622E656E746974792E7363686564756C656A6F62656E746974795C305C305C305C305C305C305C3001025C30076C5C30086265616E6E616D65745C30126C6A6176612F6C616E672F737472696E673B6C5C300A63726561746574696D65745C30106C6A6176612F7574696C2F646174653B6C5C300E63726F6E65787072657373696F6E715C307E5C30096C5C30056A6F626964745C30106C6A6176612F6C616E672F6C6F6E673B6C5C3006706172616D73715C307E5C30096C5C300672656D61726B715C307E5C30096C5C3006737461747573745C30136C6A6176612F6C616E672F696E74656765723B7870745C3008746573747461736B73725C300E6A6176612E7574696C2E64617465686A81016B797419035C305C30787077085C305C30017910F26EE878745C300E3020302F3330202A202A202A203F73725C300E6A6176612E6C616E672E6C6F6E673B8BE490EC8F23DF025C30016A5C300576616C756578725C30106A6176612E6C616E672E6E756D62657286AC951D0B94E08B025C305C3078705C305C305C305C305C305C305C3001745C300672656E72656E745C300CE58F82E695B0E6B58BE8AF9573725C30116A6176612E6C616E672E696E746567657212E2A0A4F7818738025C3001695C300576616C756578715C307E5C30135C305C305C305C30785C30";
//        String s = escapeBytes(hex2Byte(hex));
//        System.out.println(s);
//        String sql = "INSERT INTO QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, JOB_NAME, JOB_GROUP, DESCRIPTION, NEXT_FIRE_TIME, PREV_FIRE_TIME, TRIGGER_STATE, TRIGGER_TYPE, START_TIME, END_TIME, CALENDAR_NAME, MISFIRE_INSTR, JOB_DATA, PRIORITY)  VALUES('RenrenScheduler', 'TASK_1', 'DEFAULT', 'TASK_1', 'DEFAULT', null, 1619485200000, -1, 'WAITING', 'CRON', 1619485107000, 0, null, 2, x'ACED0005737200156F72672E71756172747A2E4A6F62446174614D61709FB083E8BFA9B0CB020000787200266F72672E71756172747A2E7574696C732E537472696E674B65794469727479466C61674D61708208E8C3FBC55D280200015A0013616C6C6F77735472616E7369656E74446174617872001D6F72672E71756172747A2E7574696C732E4469727479466C61674D617013E62EAD28760ACE0200025A000564697274794C00036D617074000F4C6A6176612F7574696C2F4D61703B787001737200116A6176612E7574696C2E486173684D61700507DAC1C31660D103000246000A6C6F6164466163746F724900097468726573686F6C6478703F4000000000000C7708000000100000000174000D4A4F425F504152414D5F4B45597372002E696F2E72656E72656E2E6D6F64756C65732E6A6F622E656E746974792E5363686564756C654A6F62456E7469747900000000000000010200074C00086265616E4E616D657400124C6A6176612F6C616E672F537472696E673B4C000A63726561746554696D657400104C6A6176612F7574696C2F446174653B4C000E63726F6E45787072657373696F6E71007E00094C00056A6F6249647400104C6A6176612F6C616E672F4C6F6E673B4C0006706172616D7371007E00094C000672656D61726B71007E00094C00067374617475737400134C6A6176612F6C616E672F496E74656765723B7870740008746573745461736B7372000E6A6176612E7574696C2E44617465686A81014B597419030000787077080000017910D26EE87874000E3020302F3330202A202A202A203F7372000E6A6176612E6C616E672E4C6F6E673B8BE490CC8F23DF0200014A000576616C7565787200106A6176612E6C616E672E4E756D62657286AC951D0B94E08B0200007870000000000000000174000672656E72656E74000CE58F82E695B0E6B58BE8AF95737200116A6176612E6C616E672E496E746567657212E2A0A4F781873802000149000576616C75657871007E0013000000007800', 5)";
//        String sql = "INSERT INTO \"dt_easyv_component\"(\"component_id\", \"unique_tag\", \"screen_id\", \"component_type\", \"component_name\", \"component_config\", \"triggers\", \"actions\", \"events\", \"component_data_config\", \"use_filter\", \"filters\", \"auto_update\", \"component_static_data\", \"data_type\", \"is_data_config\", \"dataFrom\", \"is_deleted\", \"parent\", \"from\", \"component_base\", \"update_time\", \"create_time\") VALUES (DEFAULT, 'kZEP6jML4NZ_I1LI2rMUx', 390, DEFAULT, '一级标题bg', '[{\\\"_name\\\":\\\"chart\\\",\\\"_displayName\\\":\\\"组件\\\",\\\"_lock\\\":true,\\\"_value\\\":[{\\\"_name\\\":\\\"dimension\\\",\\\"_displayName\\\":\\\"位置尺寸\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"chartPosition\\\",\\\"_displayName\\\":\\\"图表位置\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"left\\\",\\\"_displayName\\\":\\\"X轴坐标\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":5},{\\\"_name\\\":\\\"top\\\",\\\"_displayName\\\":\\\"Y轴坐标\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":380}],\\\"_labelLayout\\\":8},{\\\"_name\\\":\\\"chartDimension\\\",\\\"_displayName\\\":\\\"图表尺寸\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"width\\\",\\\"_displayName\\\":\\\"宽度\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":132},{\\\"_name\\\":\\\"height\\\",\\\"_displayName\\\":\\\"高度\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":62}],\\\"_labelLayout\\\":8,\\\"_lock\\\":true}]},{\\\"_name\\\":\\\"component\\\",\\\"_displayName\\\":\\\"图片\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"perspective\\\",\\\"_displayName\\\":\\\"透视\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_tip\\\":\\\"开启将影响其他属性失效（比如混合模式中的滤色效果）\\\",\\\"_step\\\":0.01},{\\\"_name\\\":\\\"borderRadius\\\",\\\"_displayName\\\":\\\"圆角\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_name\\\":\\\"margin\\\",\\\"_displayName\\\":\\\"边距\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"left\\\",\\\"_displayName\\\":\\\"左\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_name\\\":\\\"top\\\",\\\"_displayName\\\":\\\"上\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_name\\\":\\\"right\\\",\\\"_displayName\\\":\\\"右\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_name\\\":\\\"bottom\\\",\\\"_displayName\\\":\\\"下\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"}]},{\\\"_name\\\":\\\"size\\\",\\\"_displayName\\\":\\\"尺寸\\\",\\\"_value\\\":\\\"stretch\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"自适应\\\",\\\"value\\\":\\\"auto\\\"},{\\\"name\\\":\\\"拉伸以充满容器\\\",\\\"value\\\":\\\"stretch\\\"},{\\\"name\\\":\\\"真实大小\\\",\\\"value\\\":\\\"original\\\"}]},{\\\"_name\\\":\\\"overflow\\\",\\\"_displayName\\\":\\\"超出隐藏\\\",\\\"_value\\\":true,\\\"_type\\\":\\\"boolean\\\"},{\\\"_name\\\":\\\"justifyContent\\\",\\\"_displayName\\\":\\\"对齐方式\\\",\\\"_value\\\":\\\"flex-start\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"左对齐\\\",\\\"value\\\":\\\"flex-start\\\"},{\\\"name\\\":\\\"右对齐\\\",\\\"value\\\":\\\"flex-end\\\"},{\\\"name\\\":\\\"居中对齐\\\",\\\"value\\\":\\\"center\\\"}]},{\\\"_name\\\":\\\"url\\\",\\\"_labelLayout\\\":24,\\\"_value\\\":\\\"data/11690/193545/img/3LFPGgbMw_1619422981557_oIZVWNQLWZ.png\\\",\\\"_type\\\":\\\"uploadImage\\\",\\\"_updatePath\\\":[\\\"/chart/dimension/chartDimension/width\\\",\\\"/chart/dimension/chartDimension/height\\\"]},{\\\"_name\\\":\\\"event\\\",\\\"_displayName\\\":\\\"接受事件\\\",\\\"_type\\\":\\\"boolean\\\",\\\"_value\\\":true},{\\\"_name\\\":\\\"mode\\\",\\\"_displayName\\\":\\\"混合模式\\\",\\\"_value\\\":\\\"normal\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"正常\\\",\\\"value\\\":\\\"normal\\\"},{\\\"name\\\":\\\"正片叠底\\\",\\\"value\\\":\\\"multiply\\\"},{\\\"name\\\":\\\"滤色\\\",\\\"value\\\":\\\"screen\\\"},{\\\"name\\\":\\\"叠加\\\",\\\"value\\\":\\\"overlay\\\"},{\\\"name\\\":\\\"变暗\\\",\\\"value\\\":\\\"darken\\\"},{\\\"name\\\":\\\"变亮\\\",\\\"value\\\":\\\"lighten\\\"},{\\\"name\\\":\\\"颜色减淡\\\",\\\"value\\\":\\\"color-dodge\\\"},{\\\"name\\\":\\\"颜色加深\\\",\\\"value\\\":\\\"color-burn\\\"},{\\\"name\\\":\\\"强光\\\",\\\"value\\\":\\\"hard-light\\\"},{\\\"name\\\":\\\"柔光\\\",\\\"value\\\":\\\"soft-light\\\"},{\\\"name\\\":\\\"差值\\\",\\\"value\\\":\\\"difference\\\"},{\\\"name\\\":\\\"排除\\\",\\\"value\\\":\\\"exclusion\\\"},{\\\"name\\\":\\\"色相\\\",\\\"value\\\":\\\"hue\\\"},{\\\"name\\\":\\\"饱和度\\\",\\\"value\\\":\\\"saturation\\\"},{\\\"name\\\":\\\"颜色\\\",\\\"value\\\":\\\"color\\\"},{\\\"name\\\":\\\"亮度\\\",\\\"value\\\":\\\"luminosity\\\"}]},{\\\"_name\\\":\\\"initialStyle\\\",\\\"_displayName\\\":\\\"初始样式\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"opacity\\\",\\\"_displayName\\\":\\\"透明度\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":0,\\\"_max\\\":1,\\\"_step\\\":0.01},{\\\"_name\\\":\\\"rotate\\\",\\\"_displayName\\\":\\\"旋转\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"z\\\",\\\"_displayName\\\":\\\"Z\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"scale\\\",\\\"_displayName\\\":\\\"缩放\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"translate\\\",\\\"_displayName\\\":\\\"平移\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]}]}]},{\\\"_name\\\":\\\"animation\\\",\\\"_displayName\\\":\\\"动画\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationTimingFunction\\\",\\\"_displayName\\\":\\\"速度\\\",\\\"_value\\\":\\\"linear\\\",\\\"_options\\\":[{\\\"name\\\":\\\"匀速\\\",\\\"value\\\":\\\"linear\\\"},{\\\"name\\\":\\\"慢快慢\\\",\\\"value\\\":\\\"ease\\\"},{\\\"name\\\":\\\"低速开始\\\",\\\"value\\\":\\\"ease-in\\\"},{\\\"name\\\":\\\"低速结束\\\",\\\"value\\\":\\\"ease-out\\\"},{\\\"name\\\":\\\"低速开始和结束\\\",\\\"value\\\":\\\"ease-in-out\\\"}],\\\"_type\\\":\\\"select\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationDuration\\\",\\\"_displayName\\\":\\\"动画时间\\\",\\\"_value\\\":3,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationDelay\\\",\\\"_displayName\\\":\\\"动画延时\\\",\\\"_value\\\":2,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationIterationCount\\\",\\\"_displayName\\\":\\\"次数\\\",\\\"_value\\\":\\\"infinite\\\",\\\"_options\\\":[{\\\"name\\\":\\\"循环\\\",\\\"value\\\":\\\"infinite\\\"},{\\\"name\\\":\\\"单次\\\",\\\"value\\\":1}],\\\"_type\\\":\\\"select\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"type\\\",\\\"_displayName\\\":\\\"类型\\\",\\\"_value\\\":\\\"opacity\\\",\\\"_options\\\":[{\\\"name\\\":\\\"透明度\\\",\\\"value\\\":\\\"opacity\\\"},{\\\"name\\\":\\\"缩放\\\",\\\"value\\\":\\\"scale\\\"},{\\\"name\\\":\\\"顺时针旋转\\\",\\\"value\\\":\\\"rotateClockwise\\\"},{\\\"name\\\":\\\"逆时针旋转\\\",\\\"value\\\":\\\"rotateAnticlockwise\\\"},{\\\"name\\\":\\\"回旋转\\\",\\\"value\\\":\\\"whirling\\\"},{\\\"name\\\":\\\"自定义\\\",\\\"value\\\":\\\"custom\\\"}],\\\"_type\\\":\\\"select\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true],[\\\"type\\\",\\\"$eq\\\",\\\"custom\\\"]],\\\"_name\\\":\\\"animationSpan\\\",\\\"_displayName\\\":\\\"动画间隔\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true],[\\\"type\\\",\\\"$eq\\\",\\\"custom\\\"]],\\\"_name\\\":\\\"keyframes\\\",\\\"_displayName\\\":\\\"关键帧\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"keyframe\\\",\\\"_displayName\\\":\\\"帧\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"opacity\\\",\\\"_displayName\\\":\\\"透明度\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":0,\\\"_max\\\":1,\\\"_step\\\":0.01},{\\\"_name\\\":\\\"rotate\\\",\\\"_displayName\\\":\\\"旋转\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"z\\\",\\\"_displayName\\\":\\\"Z\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"scale\\\",\\\"_displayName\\\":\\\"缩放\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"translate\\\",\\\"_displayName\\\":\\\"平移\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]}]}],\\\"_type\\\":\\\"array\\\"}]},{\\\"_name\\\":\\\"filter\\\",\\\"_displayName\\\":\\\"滤镜\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"显示\\\",\\\"_type\\\":\\\"boolean\\\",\\\"_value\\\":false},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"blur\\\",\\\"_displayName\\\":\\\"高斯模糊\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(px)\\\",\\\"_value\\\":5,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"brightness\\\",\\\"_displayName\\\":\\\"亮度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":50,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"contrast\\\",\\\"_displayName\\\":\\\"对比度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":50,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"grayscale\\\",\\\"_displayName\\\":\\\"灰度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":50,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"hueRotate\\\",\\\"_displayName\\\":\\\"色相\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"色环角度值\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":0,\\\"_max\\\":360}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"invert\\\",\\\"_displayName\\\":\\\"反色\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"saturate\\\",\\\"_displayName\\\":\\\"饱和度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":100,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"sepia\\\",\\\"_displayName\\\":\\\"褐色\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"dropShadow\\\",\\\"_displayName\\\":\\\"阴影\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"hShadow\\\",\\\"_displayName\\\":\\\"水平偏移\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"vShadow\\\",\\\"_displayName\\\":\\\"垂直偏移\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"blur\\\",\\\"_displayName\\\":\\\"模糊值\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"color\\\",\\\"_displayName\\\":\\\"颜色\\\",\\\"_value\\\":\\\"#000\\\",\\\"_type\\\":\\\"color\\\"}]}]}]},{\\\"_name\\\":\\\"interaction\\\",\\\"_displayName\\\":\\\"交互\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"callback\\\",\\\"_displayName\\\":\\\"回调参数\\\",\\\"_type\\\":\\\"array\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"callback\\\",\\\"_displayName\\\":\\\"回调\\\",\\\"_type\\\":\\\"object\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"action\\\",\\\"_displayName\\\":\\\"匹配动作\\\",\\\"_value\\\":\\\"click\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"鼠标点击\\\",\\\"value\\\":\\\"click\\\"},{\\\"name\\\":\\\"鼠标移入\\\",\\\"value\\\":\\\"mouseEnter\\\"},{\\\"name\\\":\\\"鼠标移出\\\",\\\"value\\\":\\\"mouseLeave\\\"}]},{\\\"_name\\\":\\\"origin\\\",\\\"_displayName\\\":\\\"字段值\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"},{\\\"_name\\\":\\\"target\\\",\\\"_displayName\\\":\\\"变量名\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"}]}]},{\\\"_name\\\":\\\"events\\\",\\\"_displayName\\\":\\\"交互事件\\\",\\\"_type\\\":\\\"array\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"event\\\",\\\"_displayName\\\":\\\"事件\\\",\\\"_type\\\":\\\"object\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"type\\\",\\\"_displayName\\\":\\\"事件类型\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"click\\\",\\\"_options\\\":[{\\\"name\\\":\\\"鼠标单击\\\",\\\"value\\\":\\\"click\\\"}]},{\\\"_name\\\":\\\"action\\\",\\\"_displayName\\\":\\\"动作\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"show\\\",\\\"_options\\\":[{\\\"name\\\":\\\"显示\\\",\\\"value\\\":\\\"show\\\"},{\\\"name\\\":\\\"隐藏\\\",\\\"value\\\":\\\"hide\\\"},{\\\"name\\\":\\\"显隐切换\\\",\\\"value\\\":\\\"show/hide\\\"},{\\\"name\\\":\\\"组件状态切换\\\",\\\"value\\\":\\\"switchState\\\"},{\\\"name\\\":\\\"页面跳转\\\",\\\"value\\\":\\\"redirect\\\"}]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$eq\\\",\\\"redirect\\\"]],\\\"_name\\\":\\\"page\\\",\\\"_displayName\\\":\\\"页面\\\",\\\"_type\\\":\\\"pageOptions\\\",\\\"_value\\\":\\\"\\\",\\\"_options\\\":[]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"redirect\\\"],[\\\"action\\\",\\\"$neq\\\",\\\"switchState\\\"]],\\\"_name\\\":\\\"component\\\",\\\"_displayName\\\":\\\"组件\\\",\\\"_type\\\":\\\"componentOptions\\\",\\\"_value\\\":\\\"\\\",\\\"_options\\\":[]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$eq\\\",\\\"switchState\\\"]],\\\"_name\\\":\\\"panel\\\",\\\"_displayName\\\":\\\"动态面板\\\",\\\"_type\\\":\\\"panelOptions\\\",\\\"_value\\\":\\\"{}\\\",\\\"_options\\\":[]},{\\\"_name\\\":\\\"closeType\\\",\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"redirect\\\"],[\\\"action\\\",\\\"$eq\\\",\\\"show\\\"]],\\\"_displayName\\\":\\\"关闭方式\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"manual\\\",\\\"_options\\\":[{\\\"name\\\":\\\"自动关闭\\\",\\\"value\\\":\\\"auto\\\"},{\\\"name\\\":\\\"手动关闭\\\",\\\"value\\\":\\\"manual\\\"}]},{\\\"_name\\\":\\\"showTime\\\",\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"redirect\\\"],[\\\"action\\\",\\\"$eq\\\",\\\"show\\\"],[\\\"closeType\\\",\\\"$eq\\\",\\\"auto\\\"]],\\\"_displayName\\\":\\\"显示时长(s)\\\",\\\"_value\\\":10,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"command\\\"]],\\\"_name\\\":\\\"animationName\\\",\\\"_displayName\\\":\\\"动画类型\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"fade\\\",\\\"_options\\\":[{\\\"name\\\":\\\"渐隐渐显\\\",\\\"value\\\":\\\"fade\\\"},{\\\"name\\\":\\\"缩放\\\",\\\"value\\\":\\\"zoom\\\"},{\\\"name\\\":\\\"向右滑动\\\",\\\"value\\\":\\\"slide_from_left\\\"},{\\\"name\\\":\\\"向左滑动\\\",\\\"value\\\":\\\"slide_from_right\\\"}]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"command\\\"]],\\\"_name\\\":\\\"duration\\\",\\\"_displayName\\\":\\\"动画时长(ms)\\\",\\\"_value\\\":600,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"action\\\",\\\"$eq\\\",\\\"command\\\"]],\\\"_name\\\":\\\"command\\\",\\\"_displayName\\\":\\\"指令\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"key\\\",\\\"_displayName\\\":\\\"名称\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"},{\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"},{\\\"_name\\\":\\\"random\\\",\\\"_displayName\\\":\\\"随机值\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"}]}]}]},{\\\"_name\\\":\\\"remoteControl\\\",\\\"_displayName\\\":\\\"远程控制\\\",\\\"_type\\\":\\\"array\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"controls\\\",\\\"_displayName\\\":\\\"控制\\\",\\\"_type\\\":\\\"object\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"control\\\",\\\"_displayName\\\":\\\"控制\\\",\\\"_type\\\":\\\"remoteOptions\\\",\\\"_value\\\":\\\"{}\\\"}]}]}]}]', '[{\\\"name\\\":\\\"鼠标点击\\\",\\\"value\\\":\\\"click\\\"},{\\\"name\\\":\\\"鼠标移入\\\",\\\"value\\\":\\\"mouseEnter\\\"},{\\\"name\\\":\\\"鼠标移出\\\",\\\"value\\\":\\\"mouseLeave\\\"}]', '[]', '[]', '{}', 0, '[]', '{\\\"isAuto\\\":false,\\\"interval\\\":10}', '{\\\"data\\\":[{\\\"url\\\":\\\"\\\"}],\\\"fields\\\":[{\\\"name\\\":\\\"url\\\",\\\"value\\\":\\\"url\\\",\\\"desc\\\":\\\"图片地址\\\"}]}', 'static', 1, DEFAULT, 0, NULL, 0, '{\\\"module_name\\\":\\\"image\\\",\\\"version\\\":\\\"2.5.11\\\"}', DEFAULT, DEFAULT)";
//        String sql = "INSERT INTO ACT_GE_BYTEARRAY(ID_, REV_, NAME_, BYTES_, DEPLOYMENT_ID_, GENERATED_) VALUES \n" +
//                "         \n" +
//                "        ('1052502',\n" +
//                "         1,\n" +
//                "         '/usr/local/TongWeb7.0.4.7_Enterprise_Linux/deployment/portal/WEB-INF/classes/process/flowdemo.bpmn',\n" +
//                "         x'3C3F786D6C2076657273696F6E3D22312E302220656E636F64696E673D225554462D3822207374616E64616C6F6E653D22796573223F3E0A3C646566696E6974696F6E7320786D6C6E733D22687474703A2F2F7777772E6F6D672E6F72672F737065632F42504D4E2F32303130303532342F4D4F44454C2220786D6C6E733A61637469766974693D22687474703A2F2F61637469766974692E6F72672F62706D6E2220786D6C6E733A62706D6E64693D22687474703A2F2F7777772E6F6D672E6F72672F737065632F42504D4E2F32303130303532342F44492220786D6C6E733A6F6D6764633D22687474703A2F2F7777772E6F6D672E6F72672F737065632F44442F32303130303532342F44432220786D6C6E733A6F6D6764693D22687474703A2F2F7777772E6F6D672E6F72672F737065632F44442F32303130303532342F44492220786D6C6E733A746E733D22687474703A2F2F7777772E61637469766974692E6F72672F746573742220786D6C6E733A7873643D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D612220786D6C6E733A7873693D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D612D696E7374616E6365222065787072657373696F6E4C616E67756167653D22687474703A2F2F7777772E77332E6F72672F313939392F5850617468222069643D226D3135313533393239393333393222206E616D653D2222207461726765744E616D6573706163653D22687474703A2F2F7777772E61637469766974692E6F72672F746573742220747970654C616E67756167653D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D61223E0A20203C70726F636573732069643D2270726F6365737364656D6F22206973436C6F7365643D2266616C73652220697345786563757461626C653D227472756522206E616D653D22E6B581E7A88BE7A4BAE4BE8B222070726F63657373547970653D224E6F6E65223E0A202020203C73746172744576656E742069643D22537461727422206E616D653D22E5BC80E5A78B222F3E0A202020203C757365725461736B2061637469766974693A63616E64696461746555736572733D22247B64656D6F5F787874787D222061637469766974693A6578636C75736976653D2274727565222069643D227878747822206E616D653D22E4BFA1E681AFE5A1ABE58699222F3E0A202020203C757365725461736B2061637469766974693A63616E64696461746555736572733D22247B64656D6F5F666A71796A4A677D222061637469766974693A6578636C75736976653D2274727565222069643D2264656D6F5F666A71796A4A6722206E616D653D22E58886E79B91E58CBAE6848FE8A781222F3E0A202020203C757365725461736B2061637469766974693A63616E64696461746555736572733D22247B64656D6F5F6A71796A4A677D222061637469766974693A6578636C75736976653D2274727565222069643D2264656D6F5F6A71796A4A6722206E616D653D22E79B91E58CBAE6848FE8A781222F3E0A202020203C757365725461736B2061637469766974693A63616E64696461746555736572733D22247B64656D6F5F797A6B796A4A677D222061637469766974693A6578636C75736976653D2274727565222069643D2264656D6F5F797A6B796A4A6722206E616D653D22E78BB1E694BFE7A791E6848FE8A781222F3E0A202020203C656E644576656E742069643D22456E6422206E616D653D22E7BB93E69D9F222F3E0A202020203C73657175656E6365466C6F772069643D22666C6F77312220736F757263655265663D22537461727422207461726765745265663D2278787478222F3E0A202020203C73657175656E6365466C6F772069643D22666C6F77322220736F757263655265663D227878747822207461726765745265663D2264656D6F5F666A71796A4A67223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B69734A713D3D66616C73657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A202020203C73657175656E6365466C6F772069643D22666C6F77332220736F757263655265663D227878747822207461726765745265663D2264656D6F5F6A71796A4A67223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B69734A713D3D747275657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A202020203C73657175656E6365466C6F772069643D22666C6F77342220736F757263655265663D2264656D6F5F666A71796A4A6722207461726765745265663D2264656D6F5F6A71796A4A67223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B6973456E643D3D66616C73657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A202020203C73657175656E6365466C6F772069643D22666C6F77352220736F757263655265663D2264656D6F5F6A71796A4A6722207461726765745265663D2264656D6F5F797A6B796A4A67223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B6973456E643D3D66616C73657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A202020203C73657175656E6365466C6F772069643D22666C6F77362220736F757263655265663D2264656D6F5F797A6B796A4A6722207461726765745265663D22456E64223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B6973456E643D3D747275657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A202020203C73657175656E6365466C6F772069643D22666C6F77372220736F757263655265663D2264656D6F5F666A71796A4A6722207461726765745265663D22456E64223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B6973456E643D3D747275657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A202020203C73657175656E6365466C6F772069643D22666C6F77382220736F757263655265663D2264656D6F5F6A71796A4A6722207461726765745265663D22456E64223E0A2020202020203C636F6E646974696F6E45787072657373696F6E207873693A747970653D2274466F726D616C45787072657373696F6E223E3C215B43444154415B247B6973456E643D3D747275657D5D5D3E3C2F636F6E646974696F6E45787072657373696F6E3E0A202020203C2F73657175656E6365466C6F773E0A20203C2F70726F636573733E0A20203C62706D6E64693A42504D4E4469616772616D20646F63756D656E746174696F6E3D226261636B67726F756E643D234646464646463B636F756E743D313B686F72697A6F6E74616C636F756E743D313B6F7269656E746174696F6E3D303B77696474683D3834322E343B6865696768743D313139352E323B696D61676561626C6557696474683D3833322E343B696D61676561626C654865696768743D313138352E323B696D61676561626C65583D352E303B696D61676561626C65593D352E30222069643D224469616772616D2D5F3122206E616D653D224E6577204469616772616D223E0A202020203C62706D6E64693A42504D4E506C616E652062706D6E456C656D656E743D2270726F6365737364656D6F223E0A2020202020203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D225374617274222069643D2253686170652D5374617274223E0A20202020202020203C6F6D6764633A426F756E6473206865696768743D2233322E30222077696474683D2233322E302220783D223330302E302220793D22302E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D2233322E30222077696474683D2233322E302220783D22302E302220793D22302E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E53686170653E0A2020202020203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D2278787478222069643D2253686170652D78787478223E0A20202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D223237302E302220793D2237302E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D22302E302220793D22302E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E53686170653E0A2020202020203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D2264656D6F5F666A71796A4A67222069643D2253686170652D64656D6F5F666A71796A4A67223E0A20202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D2234352E302220793D223133302E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D22302E302220793D22302E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E53686170653E0A2020202020203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D2264656D6F5F6A71796A4A67222069643D2253686170652D64656D6F5F6A71796A4A67223E0A20202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D223236352E302220793D223138332E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D22302E302220793D22302E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E53686170653E0A2020202020203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D2264656D6F5F797A6B796A4A67222069643D2253686170652D64656D6F5F797A6B796A4A67223E0A20202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D223236352E302220793D223333362E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D2235352E30222077696474683D223130352E302220783D22302E302220793D22302E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E53686170653E0A2020202020203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D22456E64222069643D2253686170652D456E64223E0A20202020202020203C6F6D6764633A426F756E6473206865696768743D2233322E30222077696474683D2233322E302220783D223133302E302220793D223334362E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D2233322E30222077696474683D2233322E302220783D22302E302220793D22302E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E53686170653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7731222069643D2242504D4E456467655F666C6F77312220736F75726365456C656D656E743D2253746172742220746172676574456C656D656E743D2278787478223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223331362E302220793D2233322E30222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223331362E302220793D2237302E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7732222069643D2242504D4E456467655F666C6F77322220736F75726365456C656D656E743D22787874782220746172676574456C656D656E743D2264656D6F5F666A71796A4A67223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223235352E302220793D2239372E35222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223135302E302220793D223135372E35222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7733222069643D2242504D4E456467655F666C6F77332220736F75726365456C656D656E743D22787874782220746172676574456C656D656E743D2264656D6F5F6A71796A4A67223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223332302E302220793D223132352E30222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223332302E302220793D223138332E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7734222069643D2242504D4E456467655F666C6F77342220736F75726365456C656D656E743D2264656D6F5F666A71796A4A672220746172676574456C656D656E743D2264656D6F5F6A71796A4A67223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223136352E302220793D223135372E35222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223236352E302220793D223231302E35222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7735222069643D2242504D4E456467655F666C6F77352220736F75726365456C656D656E743D2264656D6F5F6A71796A4A672220746172676574456C656D656E743D2264656D6F5F797A6B796A4A67223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223331372E352220793D223233382E30222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223331372E352220793D223333362E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7736222069643D2242504D4E456467655F666C6F77362220736F75726365456C656D656E743D2264656D6F5F797A6B796A4A672220746172676574456C656D656E743D22456E64223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223236352E302220793D223336332E35222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223136322E302220793D223336322E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7737222069643D2242504D4E456467655F666C6F77372220736F75726365456C656D656E743D2264656D6F5F666A71796A4A672220746172676574456C656D656E743D22456E64223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223134302E302220793D223230302E30222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223134302E302220793D223334372E3136373630333032353830383636222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A2020202020203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D22666C6F7738222069643D2242504D4E456467655F666C6F77382220736F75726365456C656D656E743D2264656D6F5F6A71796A4A672220746172676574456C656D656E743D22456E64223E0A20202020202020203C6F6D6764693A776179706F696E7420783D223236352E302220793D223231302E35222F3E0A20202020202020203C6F6D6764693A776179706F696E7420783D223136322E302220793D223336322E30222F3E0A20202020202020203C62706D6E64693A42504D4E4C6162656C3E0A202020202020202020203C6F6D6764633A426F756E6473206865696768743D222D312E30222077696474683D222D312E302220783D222D312E302220793D222D312E30222F3E0A20202020202020203C2F62706D6E64693A42504D4E4C6162656C3E0A2020202020203C2F62706D6E64693A42504D4E456467653E0A202020203C2F62706D6E64693A42504D4E506C616E653E0A20203C2F62706D6E64693A42504D4E4469616772616D3E0A3C2F646566696E6974696F6E733E0A',\n" +
//                "         '1052501',\n" +
//                "         0)\n" +
//                "       ,  \n" +
//                "        ('1052503',\n" +
//                "         1,\n" +
//                "         '/usr/local/TongWeb7.0.4.7_Enterprise_Linux/deployment/portal/WEB-INF/classes/process/flowdemo.processdemo.png',\n" +
//                "         x'89504E470D0A1A0A0000000D4948445200000182000001920806000000EE2CC6120000263A4944415478DAED9D0B7C54D59DC7A15AB1D656DBD556AB75D9D6D6BAED6ABB757D746BCBB6AE956AB0B64E26330334D28AF8A08D22221121D652DA52D02222013162401E2AE2230942880A2A9157A0D5161F044510252EACA8AB28ECDDDF9FCF0D1B9299C96B9EF77CBF9FCFFF33937B0399F9DF73CEF79E73EF3DA7470F0000000080A0E1A509320B00904702282A2A4A7990610000470580080000F24002E914002200004002880000C07509200300002480080000724904D9900022000070BC37800800001CEF0D20020000C77B03880000C0F1DE00220000400488000000110000002200000044000000880000001001000020020000400400008008000000110000801332E008000020020000705906641F000011000080CB3220F300008ECB80AC0300382E03320E00E0B80CC8360080E34220CB00008E0B81EC0200E409480000001209E2AD482472149900007014496083E26B640200C0DD1EC15312C177C9040080A384C3E10725838BC8040080BB22982E115C4A2600001C2512898C538C24130000EE8A60986202990000709470383C502298492600001C251A8DFEB8A8A8A89A4C0000B82B82D3258295640200C05D117C492268241300008E128BC53E1D8944769109000087518F6077DFBE7D7B910900007745B035140A1D47260000DC15C1FA70387C2A9900007057044B153F24130000EE8A609E224C260000DC15C164C595640200C05D11945990090000470987C35759AF804C0000B82B0279A0682E9900007094C2C2C27324825A320100E06E8FE0547B96804C0000388A3D556C4F179309000047B1798624820FC8040080C34804EFF4EBD7EF53640200C05D11348642A1B3C2E1F01FF47E634141C1614C4407001050D4D8F72D2B2BFB981AFC5F35F706145EAB78AD4F9F3E07932D008000D2BF7FFF6325839D711AFFFDA1FDB79029008000130A850E4922828FB4FF44B204001060D4D8F7566C4A20824A320400E000914864683C11D893C6640700C09D9EC17DAD44B0898BC400000EE13F55DCF2AEA132B20200E018FEADA426813DB158EC78320200E018E170382609BC6AB794920D00004789442237281001004057F03CEFE0C6C6C6FBEAEBEBF72E5DBAD45BB26409918341490580B46112C86603B77BF76E2241200200C808D6134004B91B4D4D4D880000D28B0D07D1E0E67ECF80920A006923DB63DF34F4880000F244048F2F79D85B5933DE6BA81EB52FECBD6D43048800001C1041DDE285DEBAAA91DEFAAAE107846DB37D8800110040C045F0CCA2DBDB48A039562C9A8208100100045D046BABCB128AC0F6210244000001174143F50D094560FB1001220000448008100100045B04A39288601422400400107411ACAE1E9B5004B60F1120020008B8089E7CEC5EAFA1AAB46D6F40DB6C1F2240040010701158ACAC99D04604B68D07CA1001003820825AC5AAEA716D4460DB6A1101220080608BC09E1C5E5D93E41A81F675E7E9621A7A440000392A023BD37F6AD1746F7DF5F50925B03FF43BF6BBB588001100403044D05E2F2095BD031A7A4400003928820EF50292F40E10012200807C17415725E0072240040090E72260611A44000088001120020040048800110000224004880000100122400400800810012200004480081001002002448008000011644304FA8871235DFB10010020821C1441A26DE9D8870800001120024400008800112002004004880011000022400488000010012240040080081001220000448008100100E4237DFAF439380822E8D5AB57DC48D73E44000081201C0E9F3966CC18AFA1A161EB430F3D7447494949EF7C1501810800A093141515F52E2D2D7D6BD7AE5D5E33959595DE9A356BBCF2F2726FC890219E7EA74D20024400000120140A1D7EF5D557BFB073E74E2F191B366CF0E6CC99E3A9A780081001000405BB2670EDB5D73EB17DFB76AFA3CC9E3D1B11200200080AD75D77DDDCCE48E0EEBBEFF6860E1D8A081001000481E1C3878FDDBA756BA77A022D258008100100E431C3860DFBF9A64D9B3EEC8C04A64D9BC6C562440000F94C28143A488DF7198ADF0F1A34C89B356B963775EA54AFA2A2A24B124004880000F2807038FC5535D857EAF541C54EBD5FA7D73FE9B55F73635E5C5CBC6FEC7FC48811DED8B1630F9080ED3759C493002240040090830C1830E0736AA0A39148E42EBD6E56BCAA98A19F23B62FD9BFB5863D168BED6BE0ADB790AC278008100100E44EC3FF499DE1F7553B3F410DF37AC50EC502FD7C85F506D2F57779B218110040966831CE3F4AF184E21DC5E36AF46FB0EDB63F139F0311200200C82089C6F975D67F5E4141C161D9F84C88001100407A877BBA3CCE8F08084400909F0D7F56C6F9BBC3B265CBB6D913C934B8B9194D4D4D88002097C99571FEEE5057577761507A04DBB66D0B9C04962F5F8E0800728D5C1CE7CFF7E1A154843598E3C68DF31E78E0012F08DFA77550F300B23BDC93F3E3FCAE73EBADB79EF2F6DB6FEF9D3973E6BE87EA5A3F23418600A0B30D7FDE8DF33B3E3C77F88A152B76343F411DEFC139B20400ED3524793FCEEF2AB606C36DB7DDB6AAE5541AD62B683DB32A9902803604719CDF454A4A4A66B55C92B39989132722827C42C7ECE0C6C6C6FBEAEBEBF72E5DBA34901779B8509513C33D8CF3074FE6D73FFDF4D309675ABDF1C61B1141BE6012E06196DC7D88255F45C0387FB0D1B1BC78D2A4493BF7ECD99350042D27DE2363398EF50410010FB37417C6F9DDC2EE0CEAC8B29C36F32A22C8036C38880697C7DB19E787CE70D965975D620D7C7BD89A0C88200F609E1344C0383F74056BE493ADC8D6BC1603220890081E5FF2B0B7B266BCD7503D6A5FD87BDB8608822B02C6F9A19DE1C013EBEBEBE34AC004515E5ECEC5E22089A06EF1426F5DD5486F7DD5F003C2B6D93E44905D11A8421EC7383F6483D2D2D23FDA7303ADB1E53AB97D3460227866D1ED6D24D01C2B164D41045912812A586F35D27A29AA609C1FB2813D5066E533D1901022089008D65697251481ED4304991781CECE4F5063BDCBAF68DB18E7876C515656765E6565E5FE21A1D612400401114143F50D094560FB10416645A08A759AFFBABFA2D99919E3FC902DE6CF9FFF64A2798610012240042916812AD5F9AD25E0C7B58CF343B6282E2E3ED2A695683DC71022089408462511C12844902111D859BF2AD5FBF12A9ACEF03730CE0FD944E56E8ACADD356422A022585D3D36A1086C1F2248BF08D4A897F867F75E82D8A3F815E3FC9065115C4E26022A82271FBBD76BA82A6DDB1BD036DB8708D22F0255B2779348A039FA50A2218B22A850149389808AC06265CD843622B06D3C50961911E84CCB867B1E57455BE30FFFC413C1CD9468C8A208E6DAEDCC6422A022A855ACAA1ED74604B6AD161164E5F6D1582C76BC2ADD05FE45E115366CA49F1B28D19045112C5419BC904C045004F6E4F0EA9A24D708B4AF3B4F17D3D0A76E8A097BE49F120D5914C16312C1B964224022B033FDA7164DF7D6575F9F5002FB43BF63BF5B8B089C9A7D14A095089629CE26130111417BBD8054F60E68E81101044604AB9A1F76840088A043BD8024BD03448008C049113C178944BE4E26822282AE4AC00F448008C049116C0C87C35F26130111010BD32002802E8860ABE20B64021120024400EE8A6047341AFD0C99400488001180BB2278BFB8B8F8503281081001220037E92911ECB55752810810012200070985429F9008FE874C2002448008C051ECDA805D23201388001120027014BB5BC8EE1A2213880011200270147B7E402278994C20829489401F2B6E64631F2200E89008BE2111FC954C2082948A20D1B64CEF4304001D12C1BF49042BC90422400488001CA5B0B0F07B36FB2899400488001180BB3D82736D3D0232810810012200774570A1AD5046261001224004E0AE088A2291C81C328108100122007745708944701799400488204B2260A22FC83692C01592C1ED64021120820C8B60F2E4C945555555CF6ED8B0C17BFEF9E7199F856C8A60986202994004291341AF5EBDE24636F6E59A08E6CF9FFFFD279E7862F1CB2FBFFCFE871F7EE8B5A4A2A2C22B2A2A8A1B947448F3D0D00D12C16FC90422608A8934456D6DADF7ECB3CFDEFBEAABAFBEBD67CF1E2F1973E6CC4104908D1EC16F4D06640211208234C4EBAFBFEE8D1D3BD6EB0CE5E5E58800322D820912C135640211208234C58C1933BCD9B367774A0693274F4604903154C6A64804979309448008D2788DA0B0B0F01CBB06D051EC775BCA80920E296CF44F53A3FF875028745C8B6D158A62B2830810419A2F16775606466969292280942209F44D745382E29DE6F7260BB2850810411AEE1A32198C18312269E36F95D02E1AB7BA70FC4A241299A9D75F44A3D1AF50F2A19BBD82BF279181C57D6409112082343E47104F0656F9ECA2F2AC59B3E2564C75E34FD2EBA58A4AC566C5EB8AB9FE8340DFE8C1A2E3D009549E0E4922818F14BDC912224004697EA0CC64601791A74D9BB62FDA393BF3E29CD1F5960006EA7586E225C55BFAF941BD5EADD77F55453F88DA01497A04BD15AFC52B6B2A3FD3C910224004197AB2B84F9F3E07A7B0627FC1260EB3BB3F14CF29FE5B51AD18A15EC35983070FFE38B505E2949B36D708545E8E22338800110460D239ABCCAAD417490EB7E875AD7EDEA5D75AFD3C5AAF7D98DF08ECAE219585F75B89A08CCC20024410D0D94755E98F8846A33FB63B4154D957F877872C578CB54549B4FF706A93933238A985045EE6040111200287A6A12E282838CCAE53A8F2FF46F1842F86958AF1EA3D14481A9FA176051F1DEBCFFB17873DBD2F212388001138BC1E41DFBE7D7BA93138DBE69BD1EB62C5DB8A75FA79925E2F1E3060C0E7A86DC1A4ACACEC633AC67BD43B389A6C200244C0C234FBB10BD9EA159C2E110C5723F18862877FEF79B9221A8BC58EA7F6056678E8B33AA6BB1916CA63962D5BB66DFBF6ED34B8391A4D4D4D8158A1CCCE1A25866FAAC1F895E470BF5EB72B1A6D6A025BDD4AF16517EA9BE77907373636DE575F5FBF77E9D2A55EB64FC488F8E19C08EAEAEA2E74A547B065CB96BC93C0F2E5CB035B30D5F89F1C89442E930CEE556CF1C3DE0FB17D41FCCE26017AE0B9DBF3765604B9303C9489A8A9A9F1162C58B0EFE12BCE507213F518BE64BD037F02B38D7EAFE101EB45586FC27A15F9FE1DAD278008E8814396900856D9540C13274E6415AF3CC1AE23D8F504C961AA7F7DC1AE333CE25F773823950FD4650A1B0EA2C1E59A1C64E762D689EA92EF6D9E97C7A6664004F987DD791489447EE6DF89B4CE7FC8CDEE501A65772CD99D4BF4BEE9112002884B6969E903AD97787CE4914710419E535C5C7CA43DB360CF2E289EF5A73778D29E6DB0671C248E4FE6AB081E5FF2B0B7B266BCD7503D6A5FD87BDB8608100174B1373077EEDC8FE24DD73C7DFA742F168B2182E01CEBC3ED29675B3F57C77399FF90DB0AFF69E8F3EDE9E87C1041DDE285DEBAAA91DEFAAAE107846DB37D8800114027B10B8F9B376F4E38777F4B1990ADC0F5180E9514BEAF637BA3CD93E40F25D9BC49B7DA3C4AA99E1C4DC289A54204CF2CBABD8D049A63C5A22988001140677B03C3860DFBA0F5B0506B9AE7F02763C1C6665055637DA6CDA8AAD72AC54EC9E0797FBD5D9B79F50BDD2C6F9FD0FFF16E7745B0B6BA2CA1086C1F224004D0C9DEC0BC79F3DA5DCEB1BCBC1C113888DD8E6A6B2FD81C393AFE0B144DFEDA0C33B4EDE79D5D5CA579264EFD9F7F49F46F3BD2583754DF905004B60F112002E820766BA155CA575E7905114047E929017C5D0DF9E5B67A9BBF8ADB666D9B65ABBBD94C9B497A0347B7BAF9E091968BBB23024400594215F8BC3BEEB8A3DDF57DB9580CC986176D9D677FBDE74D8A37F47EBE6471955E4FE9E12FF1196FE946FD4E436B19744C04A392886014224004D059EC36C2993367261441CB651EC91674400C274800FD555EA6295E50FC97E2217FC9CF78CFA734D9A46C9D11C1EAEAB1094560FB100122802EF60C6CF8A7A2A2E20009D8CF53A64C4104D01D311CA333FF50B2F5A2EDA274F3C23D1D69AC9F7CEC5EAFA1AAB46D6F40DB6C1F224004D08D9EC19D77DE79800866CF9ECDD010749BFEFDFB1F9B4C04CDA11392EB3ADA60AFAC99D04604B68D07CA1001A4A067D0F22EA2D6534D9021E80AB65A5B92C6DF6E4FADF16375471AEB5AC5AAEA716D4460DB6A11012280D4F40CECBA800D0B4D9D3A151140B751D9F9DFE621207B92598DFF38BDBF20DEE23B1D79AA78754D926B04DAD79DA78B69E81101B4E81930E91CA40A35FA7D557EFA746436D464BD80A7164DF7D6575F9F5002FB43BF63BF5B8B08100174FB2CEE225B6C9D4C4026E94A2F2095BD031A7A44002DF0175B7F371767A804B744D0A15E4092DE01224004D0BD5EC1539148E4076402B22A82AE4AC00F448008A01BD8453DC9600C99806C8A808569100164B74770BE64B0844C0022201081A3F82B5CEDCAC7B56F01112002440029C29F32F834320188804004EE8AE07689E06A3201888040048E121112C1036402100181081C4512F8A2E24D3201888040046ECBE0957038FC55320188804004EE0E0FD9528483C804B82E027DC4B891AE7D880072490497D942F7640210418F84DBD2B10F11402E89E0EB12C14B64021001224004EED2D3D69EB565074905200244404975947038FCB0647031990044800828A9EE0E0F5DA7B8954C002240049454774570967A046BC804200244404975945028748844F04EBF7EFD3E453600112002701489E08970387C2E9900448008C0DDE1A1DF4A06BF2113E0AA087AF5EA1537D2B50F11402E8AE03C451D99005745402002E789C5629FB6EB0483070FFE38D900448008C051C2E170836470069900448008C05D114C9208AE251380081001384A2412299408169209400488001C4512F882A2496F7B920D40048800DC95C1C670387C329900448008C0DDE1A19992C1A56402100122007745F04B89E01E320188001180BB22F89A44D0482600112002701889607B28143A8E4C00224004E028E170F841C9204C260011200270777868984430994C00224004E028D168F47489601D9900448008C051FAF4E973B07A05BB42A1D011640352C5B265CBB66DDFBE9D063747A3A9A90911C081A847B0341C0EF72513902AEAEAEA2E4CF5597E555595F7D8638F65AD47B075EBD6C04860F9F2E588000E443D829B2483B16402B2353C648DFCFDF7DFEFDD73CF3DDE5D77DDE5CD9F3FDF7BF4D147F735582FBCF082F7E28B2F7A2AA3FB221B434D2B57AEF4264D9AE43DF8E0835EB687BD52199452D84F6161E139AA60CBC804649AE2E2E2434B4A4AF6354AEBD6ADF3DE7BEF3D2F1EB367CFDE2F824C7F46FDF94377ECD8B1B3B2B2D21B3468D0FECFD132389290F78442A1C36DA11A5BD89E6C40867BA3DF5DB06081D71ED96C745F7BEDB5CAE6CF3171E2444400C1458579B5E23B6402322C82928D1B372695C0D8B163BD69D3A665A5D17DF7DD774FDDBB77EFFFB6FC3C63C68C4104104CC2E1F02D2AD023C8046492912347D6ECD9B327A908EEBEFBEEAC35BA6FBCF14663BCDE492C16430410C833B39FA9403F42262093CC9D3B77577BC342D912C1E6CD9B6F4CF499E6CC998308209022F8BC0AF48E1E2C54031942E5ADF79A356BDABD3ED0725828538DAEFEEC311F7CF0C187893ED3CC9933BDA1438722020864C57C311C0E7F834C4026B8E69A6B06B5372C3475EAD4AC8CC76FD9B265457B82CAE69D4C00E914C10C89E07232019960CE9C390FB5D7D8CE9A352B2B2230012102701249E01215EAD9640232C1DAB56BDF6A39D462618D6BF3509049A0F5B050A61ADDD1A3475F69D701920D0DB5BC4EC0D184C0108D46BFA242FD2A9980743378F0E0C3E6CD9BE7CD9831635FA31AEFCC3F5164EA33DE79E79D1E178BC14954A8DF08854227900948172A5F9F50395BD0D1863F5B22D0E73CD1FE5EBCDB475B5E2846041038C2E1F0FD2AD8513201E960E0C081FFA0F2F58CA252BD828FE7FAE71D3F7EFC6D1515150788A0BCBC9C07CA20F022F8B50AF61D6402524D341AFD92CAD60B8ADFF5C893DB946D9AF6850B177A4C3101AE55D66FAB60FF954C402A51993A4DB1553124DF3EFB55575DF503BB8E6193CEC5BB9D151140E008854207A960BF2D217C866C408A2470BE627B241229C8D7EF505E5E5E930BD72D003259711787C3E10BC804A4A02C5DAA78DD9644CDE7EF515C5C7C242200A7900446AB70FF9E4C40372570B3E22595A72F07E4FBD0E0833B141616FE870AFDD36402BA82DD0DA4F273B7A23E12891C1520B121027087828282C354E8DFB5D5A3C80674867EFDFA7DCA8616150F59390A580F07118073DDFA7AF50CBE4726A0A3F4EFDFFFD87038DCA0B233C56E3A08609D4004E016AAD07F52C12F2513D011D4F0FFB3CACB2B2A37D707F8E40811805B4422919FA8E0579309680FEB39DAD42492402CE0BD644400CE89E02855EC9D6565651F231B90A49C14AA817C53AF3F08FA774504E0242AF87F970C4E2513904002C3544636C762B17F71A43E2002705204D32482ABC804B4C47A892A1B7F56D9F88B2470BC43F50111807BA8A20F54E19F4B26A019BBA5D89FA1766928143AC2B113234400EEA18AFE4F3651189900BF3C7C56E5E1295BC54EEF0F71B0878C08C04D4C04260432C149412412D9E04F3DD2D3D1BA8008C05911CC530C2013EEE24F4DBE5522B8C2F1BA8008C04DEC62B15D342613CE1EFFBE3685B45E2FE4A4081180BB6783DF5405F81B9970B2E1FB85629B247026D94004E030FEAD82FF1DA45924A17D74BC6FD2717FD91670271B8800C02A400D43036E606BF44A0277E9983F2B091C4D46100140730528B549E8C844B051C37FB88EF322C5C3419B421A110074137F52B17A321168091CA363BC5612981AC429A411014037B1A7496DA11ACE1283891AFF93757C3731ED38220068AF123C6D4B589289C01DD7B36D0A699E154104001D396BFC832AC28D642250C7346453482B7E4836100140471A8D0B6C2D5A32119846ED6A9B423A12899C423610014087F0271C7B9B0B89F98D3D1722A9DFA263F99CE28B6404110074B6223C67F3CE9089FCC4BFE87F9FE271BD3F928C200280AE54843B7436F96B3291B73DBAE58A7B5D9C421A1100A4085B9CDCCE28C9447EA15EDC3FDAB2A39148E48F3D1C9D421A1100A4EEACF2049B848C4CE455E3F52DC516961C450400A9AC0C9B99882C6F7A023FB229A4D513F809D9400400A9AC0CB37576790999C86DEC1859EF4D12388B6C20028054373097AB42CC201339DD608D516CD4B1FA2AD94004002927168BFD8B2AC40B6422F7B029A4756CEE54AC1A3060C0E7C808220048173D552176D0D0E4163685B48E4BB5E2511D9B4F9211440090EE0AF168381CFE2999C81909D814D26B14E53CF98D080032552146D834056422272470928E47A36214D940040019A3B0B0F0DF6D1C9A4CE4C47178231289FC9C6C2002808CD2B76FDF5EAA14EFD8B834D9C80E6AFC7F665348EBF53FC9062200C856A558A633D273C844E6B1F99E94FFD7F47A2AD9400400D9AC14BF539491898CD2533D80098AE76DBA0FD2810800B27D56DA5715632999C80CFE70DC3C49E049A6904604003981CE488F50A3B4CB1E62221BE9251A8D7EC604A08668AE09818C2002805CAA18EBD4489D4E26D22ADC136C28483DB03FF5600A69440090831563B21AA86BC844DA7A02DFB48BC28A5F910D440090AB15431E083F4826528FF27AAEDD1ECA13DC880020A7098542C7D97CF76422B5D803623685B4E23B64031100E443E5D864D31C908994E5F3469B429A9C2202807CAA1CF7E80CF69764A2DBBDAB8394CB698AD5CAE7E7C9082200C8A7CA3158713799E83A366D74381CAEB269A499421A1100E41D6AC04EB6A10C32D135ECECDF26F0531EA7F34C062200C8576CA19AB7FAF7EF7F2CA9E8B444BFEA2F29399A6C2002807CAF200FE9CCB6904C742A67DFF1EF0C2A261B8800200815E45A9DD54E22131DCED745F68C40341AFD11D9400400814012385395642D99681FF59C862A575B14DF221B880020300C1E3CF8E3B6504D2C16FB34D948885D4B19AFF89B7A02FF483A100140102BC9E30C75C427140A1DA29EC01C5BCCC766122523880020A895E4376AEC7E4B260EC4D60E506E9E506EE633853422000834FE24694F9089031A8E2F2A9E534CECC114D2880020E8F4EBD7EF53FE82F687908D7D623CD5A690564FA0846C200200972ACA1A357C67B99E87C2C2C273ECF650C5C5940A4400E05A45F9B3CE84873BDE1318A83CBC21217E97128108005CAC2817AB217CD86109DCA01C344A025FA33420020027098542C7A8B2FC570FC72E8CDA14D292C0541B1AB31C5012100180EB95E5259D117FDD95EF5B50507098BEF3238A1A49E0704A002200A0B2141555480497B9F05D070C18F0397DDF958A194C218D0800E0FF2BCB2F149541FF9ED168F42BFA9E2F2BCA38EA8800005AE0CFB1FF4AC0BFE399FE14D28338E2880000E257983763B1D8F141FC6E9148E427FA7EDBF57A1E471A110040E20AF3801ACA4800BFD7953685B47A04FFCA5146040090BCC25CADC6F2F6007DA59EFA3E7FD0F7FABBA23747181100403BA8D1FC37C55F82F05D6CEE243500B315CBF5FEB31C5D4400001DC06EA58C4422BB6C0AE63C97C011FA1E7592DAFDFA2E87726411015900E85CA5A98D46A33FCED7CF6F17BBF51DFE2A11DC5A5656F6318EA873E5D71E12F4DA8955640A2079451AA346745C3E7E767DEE53F4F937AB27700D47D24D6C185065E0A3642250F9B8804C012417C10F6D5C3D4F3FF79B92412147D16D6C7833890856F03439403B0C1830E093AA2CEFE6D3F28CAAF8FD6D0A69C5D91C41F07B05714560CF939021808E9D5DAFCC9779F9F53947EAF36E5277FF648E1C3463D787E25D1BA03700D071114C54C37A7D8E9FF51DA4CF79873E6743FFFEFD8FE5A8419C5EC1475C1B00E8BA082E52A5A9CAD5CFE74F21FD90E2315B7399230609CAF10E7A03005D3F9B3A5A22D8998BB75FDA6753A5AEB769B3A9D8D08E0836B6B836C01C53009D45156783DD8E99631238D116D051FC8623041DA1F95A01270D005D3B9BBA5322B8428DEF71EA1DF4D5EB49763751163FCF198AD7F5997EC9D1814E9C3C7C96278B013A899D39A9E13FD7E6EC8F73EB5D56BAD7FADBFD6C0AE97C7EE2395FF03CEFE0C6C6C6FBEAEBEBF72E5DBAD45BB264099183414985B4E03F3FF07EB227322582A332FDB924A5CBF5B7B74A02DFE628A51F9340361BB8DDBB77130902114026CEBA37B623824CCFCFD2D3A6BAB06B15EADEFF13472833584F0011E46E3435352102482F36536792795AEEC8D4E7B029A4258059FA9B4F0F1C38F01F383299C38683687073BF67404985740FC5142510C1901436F4C725DA178BC53EADBFB5D4564B630AE9CC93EDB16F1A7A4400B92383075B8B408DF731A9F8BFED2960FF7AC3E7E309C216C6514C620AE9DC16C1E34B1EF656D68CF71AAA47ED0B7B6FDB1001228080A006F904BB26D0E222F19BA9FABFED1654FFFFFDBBFD9D16F2F986B6BDAAB8962390DB22A85BBCD05B5735D25B5F35FC80B06DB60F1120020808FE99FB36BFD1AE4991047AB7EA69D4980C0A0B0BFFC3A6905684C97CEE8BE09945B7B7914073AC5834051120020812FE53BCBBD523589C8AFFCFBF18DD7A91903A9B425A7FE3FB643C3F44B0B6BA2CA1086C1F2240041020ECE1321381DF33A8B1E19CF6567FEA4AA857703AD9CE1F113454DF905004B60F11200208007661580D745949498997EA46DF48F0B0DA50328F0868E81101E4400FC0D621282E2E4EB900DA13813DCC2619947014F24104A39288601422400490C7BD8013478C189136012411813DCDFCB809C8EE1CE248E4BE0856578F4D2802DB87081001E4216A80CF1C326448DA25D04204766BEA6DB662940474384720BF44F0E463F77A0D55A56D7B03DA66FB100122803C94403A8782E28980ACE7B7082C56D64C682302DBC603658800F27038E8D24B2FCD98045A0747203F4550AB58553DAE8D086C5B2D224004903FD885E14C5C134004C112813D39BCBA26C93502EDEBCED3C534F48800323B24747D36258008F24B0476A6FFD4A2E9DEFAEAEB134A607FE877EC776B110122809C1E123A2693D70510417E8BA0BD5E402A7B0734F4880032843D2C966D092082FC1141877A01497A07880011400E5E1B48C713C388207F8E7FA745D05509F881081001E4DEB5810B724102C944A07DE7DB2A651CADD4128944FAC76231CBFDC59DBD46C0C234880082352C7473AE8AC017C04992D54E7A0CA9C36E131E3D7AF48A0D1B36ECCBFBF0E1C3DB3D3688001140B04550936B22504375B4F500183A4AFD30D0A04183CA6A6A6A76ECD9B3C76BA6A2A2C25BB870A197EC860144800820D82278295744A0D8A8D8D1DC034004291D06FAEEC48913376DDBB6CD4B84E578CC9831880011808322F828574490480088A0EBE82CFFC8929292596BD7AE7DCBEB20D6B0A8E7800810013824022F8FC216C4D9A2D8ACD8E4F7205ED4D9EE06BDFE4DF157C57AC55AC56AC5B38A158AA714CB6C5653C552C562496791A24AEF1F512C542C50DCA798A7B857FFE72CC54CBDAF50DCA998A6B843FFE676C524EDBB553141DBC62B7EAFF89D5D6FD1B69BF43A46314A51AA18A1DF1FAEB8C6A6D5B67516B4ED4AC510C560C52FB4EF12C540BB78AB88D8129D760157DB7EAAB8509B0AA2D1E88FF57A9EE23FB5EF878A3E858585DF53FCBBB69DA5FDA76BDB698A6FE9DF9CEAAFF97C467979B9F7DE7BEF799DA5B2B2D29B3469122240048008321B6AD47EA446EDD9242B971DA3384EEFBF68EB1DABF1FB92E22B764159F1CFD6F8E9DF9F628DA1B67FDB1A479B44CF1A4B6D3BDB1A4FEDFF8135A6DAF7236B5CEDAE296B6CB5EF226B7CB5AFD01A636D8B59E3AC6DC5D6582B2EB5C65BBBAEB0C65CFB7E6D8DBBB65D6B8DBDB68DB4C65FDB46FBCF65D845F8B12609EDFBA34943FB6E318968DB64938AA2DC24A37D77F9D2A9340929E6FA527AC024A57FF3B02F2DBB9EB358516B52D3BF79D297DC33BEF456F9125C6752D4FEE76FBAE9A6D79A27F7EB0C3367CEA4478008806B04D9BB58AC06EC27FED93D43432940EDFAC173E6CCD97796DF1E76E178C68C19DEAC59B31001220087445093ABB78FEA2CB8A8A510385ADD438D7CC1ECD9B3134A209E00725104E6B678918D7D88008222829BF3E081321B2F6FE068751F5BF8C772DDB27760EFF3E93982788D70CB463B93FB100104027F188629261CE3BAEBAE8B4D9C38D1B3983A752A224004E032F690112270F7D8B7F73BB938C504224004909EE1A1F1880010012200B7C78D8F410480081001D02BB8191100224004E030C5C5C5872202400488001CC79F96001100224004E032858585E72002C86511F4EAD52B6E64631F22006480081001534C200260980811200244800820C064E202B241A6110122400490E3A4F33903B28B08100122803C221DD351905544800810010020024480080000112002440000880011200200400488001100002240048800001001224004008008100122000044800810010020021A7A440000D966D9B265DBB66FDF4E839BA3D1D4D484080020BDD4D5D55D488F207725B07CF972440000C11F1E22DA0F4A29000000000000000000000000000000000000000000000000000024E5FF00E0AA3DAE106C1D500000000049454E44AE426082',\n" +
//                "         '1052501',\n" +
//                "         1)";
//        boolean hexSql = isHexSql(sql);
//        System.out.println(hexSql);
//        if(hexSql) {
//            String string = handleHexStringWithDM(sql);
//            System.out.println(string);
//        }
    }
}
