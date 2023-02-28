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

import com.alibaba.fastjson.JSONObject;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import org.apache.shardingsphere.infra.replace.SqlReplace;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.infra.replace.model.DatabaseType;
import org.apache.shardingsphere.infra.replace.model.SouthDatabase;
import org.apache.shardingsphere.infra.replace.util.StringUtil;
import org.apache.shardingsphere.infra.replace.util.etcd.EtcdKey;
import org.apache.shardingsphere.infra.replace.util.etcd.JetcdClientUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * SQL 字符替换
 * @author SmileCircle
 */
public class SqlBinaryReplaceEngine implements SqlReplace {
    
    private static final String INSTANCE_ENV_KEY = "INSTANCE_ID";
    
    private static final String INSTANCE_ID = "xc7e97a5b5e4a849";


    @Override
    public String replace(String sql, Object obj) {
        return replaceSql(sql, (SQLStrReplaceTriggerModeEnum) obj);
    }

    @Override
    public SQLReplaceTypeEnum getType() {
        return SQLReplaceTypeEnum.BINARY;
    }

    /**
     * 替换SQL
     * @param sql
     * @return
     */
    private static String replaceSql(String sql, SQLStrReplaceTriggerModeEnum triggerMode) {
        if(Objects.equals(SQLStrReplaceTriggerModeEnum.FRONT_END, triggerMode)) {
            throw new RuntimeException("binary rewrite don't support of font-replace!");
        }
        String marker = "0x";
        if(isBinarySql(sql, marker)) {
            GetOption getOption = GetOption.newBuilder().withPrefix(ByteSequence.from(EtcdKey.SQL_SOUTH_DATABASE, StandardCharsets.UTF_8)).build();
            GetResponse response = JetcdClientUtil.getWithPrefix(EtcdKey.SQL_SOUTH_DATABASE, getOption);
            if (Objects.nonNull(response)) {
                SouthDatabase targetSouthDatabase = null;
                for (KeyValue item : response.getKvs()) {
                    SouthDatabase southDatabase = JSONObject.parseObject(item.getValue().toString(StandardCharsets.UTF_8), SouthDatabase.class);
                    if (Objects.equals(southDatabase.getInstanceId(), SqlBinaryReplaceEngine.INSTANCE_ID)) {
                        targetSouthDatabase = southDatabase;
                        break;
                    }
                }
                if(Objects.nonNull(targetSouthDatabase)) {
                    DatabaseType northDatabaseType = JetcdClientUtil.getSingleObject(EtcdKey.SQL_DATABASE_TYPE + targetSouthDatabase.getDatabaseTypeId(), DatabaseType.class);
                    if(Objects.nonNull(northDatabaseType) && Objects.equals(northDatabaseType.getDriverClass(), "com.kingbase8.Driver")) {
                        return handleHexString(sql, marker);
                    }
                }
            }
        }
        return sql;
    }

    private static boolean isBinarySql(String sql, String marker) {
        String tempSql = StringUtil.trimAllWhitespace(sql).toUpperCase(Locale.ROOT);
        return (tempSql.startsWith("INSERT") || tempSql.startsWith("UPDATE")) && sql.contains(marker);
    }

    public static String handleHexString(final String sql, String marker) {
        List<String> characterList = new ArrayList<>();
        characterList.add("(");
        characterList.add(",");
        characterList.add("=");
        String temp = StringUtil.trimAllWhitespace(sql);
        List<String> hexList = new ArrayList<>();
        while (temp.contains(marker)) {
            int index = temp.indexOf(marker);
            // 查询之前是不是符合字符串
            String front = temp.substring(index - 1, index);
            String back = temp.substring(index);
            boolean frontCheck = characterList.contains(front);
            if(frontCheck) {
                List<String> allowBackChar = new ArrayList<>();
                allowBackChar.add(",");
                allowBackChar.add(")");
                String hexString = findUntilChar(back, allowBackChar);
                hexList.add(hexString);
            }
            temp = temp.replaceFirst(marker, "");
        }
        String result = sql;
        for (String hexString : hexList) {
            String replace = hexString.replace(marker, "");
            byte[] bytes = hex2Byte(replace);
            String prefix = result.substring(0, result.indexOf(hexString));
            String byteString = escapeBytes(bytes);
            String last = result.substring(result.indexOf(hexString) + hexString.length());
            result = prefix + "E'" + byteString + "'" + last;
        }
        return result;
    }

    public static String findUntilChar(final String text, List<String> characterList) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String now = text.substring(i, i + 1);
            if(characterList.contains(now)) {
                return sb.toString();
            }
            sb.append(now);
        }
        return sb.toString();
    }

    /**
     * 16进制字符串转byte数组
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
                    sb.append("\\\\000"); break;
                case 39:
                    sb.append("\\\\047"); break;
                case 92:
                    sb.append("\\\\134"); break;
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

    public static boolean isHex(String hex) {
        int digit;
        try {
            //字符串直接转换为数字，存在字符时会自动抛出异常
            digit = Integer.parseInt(hex);
        }catch (Exception e) {
            //字符串存在字母时，捕获异常并转换为数字
            digit = hex.charAt(0) - 'A' + 10;
        }
        return 0 <= digit && digit <= 15;
    }

    public static byte[] intToByteArray(int i) {
        byte[] result = new byte[4];
        result[0] = (byte)((i >> 24) & 0xFF);
        result[1] = (byte)((i >> 16) & 0xFF);
        result[2] = (byte)((i >> 8) & 0xFF);
        result[3] = (byte)(i & 0xFF);
        List<Byte> byteList = new ArrayList<>();
        for (byte b : result) {
            if(!Objects.equals(b, (byte) 0x0)) {
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
        String hex = "ACED5C300573725C30156F72672E71756172747A2E6A6F62646174616D61709FB083E8BFA9B0EB025C305C3078725C30266F72672E71756172747A2E7574696C732E737472696E676B65796469727479666C61676D61708208E8E3FBE55D28025C30017A5C3013616C6C6F77737472616E7369656E746461746178725C301D6F72672E71756172747A2E7574696C732E6469727479666C61676D617013E62EAD28760AEE025C30027A5C300564697274796C5C30036D6170745C300F6C6A6176612F7574696C2F6D61703B78700173725C30116A6176612E7574696C2E686173686D61700507FAE1E31660F1035C3002665C300A6C6F6164666163746F72695C30097468726573686F6C6478703F405C305C305C305C305C300C77085C305C305C30105C305C305C3001745C300D6A6F625F706172616D5F6B657973725C302E696F2E72656E72656E2E6D6F64756C65732E6A6F622E656E746974792E7363686564756C656A6F62656E746974795C305C305C305C305C305C305C3001025C30076C5C30086265616E6E616D65745C30126C6A6176612F6C616E672F737472696E673B6C5C300A63726561746574696D65745C30106C6A6176612F7574696C2F646174653B6C5C300E63726F6E65787072657373696F6E715C307E5C30096C5C30056A6F626964745C30106C6A6176612F6C616E672F6C6F6E673B6C5C3006706172616D73715C307E5C30096C5C300672656D61726B715C307E5C30096C5C3006737461747573745C30136C6A6176612F6C616E672F696E74656765723B7870745C3008746573747461736B73725C300E6A6176612E7574696C2E64617465686A81016B797419035C305C30787077085C305C30017910F26EE878745C300E3020302F3330202A202A202A203F73725C300E6A6176612E6C616E672E6C6F6E673B8BE490EC8F23DF025C30016A5C300576616C756578725C30106A6176612E6C616E672E6E756D62657286AC951D0B94E08B025C305C3078705C305C305C305C305C305C305C3001745C300672656E72656E745C300CE58F82E695B0E6B58BE8AF9573725C30116A6176612E6C616E672E696E746567657212E2A0A4F7818738025C3001695C300576616C756578715C307E5C30135C305C305C305C30785C30";
        String s = escapeBytes(hex2Byte(hex));
        System.out.println(s);
    }
}
