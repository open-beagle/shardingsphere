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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.infra.replace.SqlReplace;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.infra.replace.model.DatabaseType;
import org.apache.shardingsphere.infra.replace.model.SouthDatabase;
import org.apache.shardingsphere.infra.replace.util.etcd.EtcdKey;
import org.apache.shardingsphere.infra.replace.util.etcd.JetcdClientUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 二进制替换
 * @author SmileCircle
 */
@Slf4j
public class SqlBinaryReplaceEngine implements SqlReplace {
    
    private static final String INSTANCE_ENV_KEY = "INSTANCE_ID";

    private static final String INSTANCE_ID = System.getenv(INSTANCE_ENV_KEY);

    /**
     * 匹配 插入/修改SQL 中含有 x开头的 二进制
     */
    private static final String REGEX_X = "^(insert|update).*x'.*'.*";
    /**
     * 寻找 x''中间的内容
     */
    private static final String REGEX_FIND_X_DATA = "(?<=x').*?(?=')";


    @Override
    public String replace(String sql, Object obj) {
        return replaceSql(sql, (SQLStrReplaceTriggerModeEnum) obj);
    }

    @Override
    public SQLReplaceTypeEnum getType() {
        return SQLReplaceTypeEnum.BINARY;
    }

    /**
     * 替换SQL 中的X''
     * @param sql
     * @return
     */
    private static String replaceSql(String sql, SQLStrReplaceTriggerModeEnum triggerMode) {
        if(Objects.equals(SQLStrReplaceTriggerModeEnum.FRONT_END, triggerMode)) {
            throw new RuntimeException("binary rewrite don't support of font-replace!");
        }
        if(isHexSql(sql)) {
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
                        return handleHexString(sql);
                    }
                }
            }
        }
        return sql;
    }

    /**
     * 校验是否是 INSERT UPDATE 的SQL 并且 包含二进制数据
     * @param sql 需要校验的SQL
     * @return 是否是 INSERT UPDATE 的SQL 并且 包含二进制数据
     */
    public static boolean isHexSql(String sql) {
        if(StringUtils.isNotBlank(sql.toLowerCase(Locale.ROOT))) {
            sql = sql.toLowerCase();
            Pattern pattern = Pattern.compile(REGEX_X, Pattern.DOTALL|Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(sql);
            return matcher.matches();
        }
        return false;
    }

    public static String handleHexString(final String sql) {
        Pattern pattern = Pattern.compile(REGEX_FIND_X_DATA, Pattern.DOTALL|Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(sql);
        List<String> hexList = new ArrayList<>();
        while (matcher.find()) {
            String hex = matcher.group();
            if(isHex(hex)) {
                log.info(" ========= hex data: {}", hex);
                hexList.add(hex);
            }

        }

        String distSql = sql;
        // 替换x''
        for (String hex : hexList) {
            int index = sql.indexOf(hex);
            String frontSql = sql.substring(0, index - 2);
            String backSql = sql.substring(index + hex.length());
            String binaryString = escapeBytes(hex2Byte(hex));
            distSql = frontSql + "E'" + binaryString + backSql;
        }
        return distSql;
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
//        String hex = "ACED5C300573725C30156F72672E71756172747A2E6A6F62646174616D61709FB083E8BFA9B0EB025C305C3078725C30266F72672E71756172747A2E7574696C732E737472696E676B65796469727479666C61676D61708208E8E3FBE55D28025C30017A5C3013616C6C6F77737472616E7369656E746461746178725C301D6F72672E71756172747A2E7574696C732E6469727479666C61676D617013E62EAD28760AEE025C30027A5C300564697274796C5C30036D6170745C300F6C6A6176612F7574696C2F6D61703B78700173725C30116A6176612E7574696C2E686173686D61700507FAE1E31660F1035C3002665C300A6C6F6164666163746F72695C30097468726573686F6C6478703F405C305C305C305C305C300C77085C305C305C30105C305C305C3001745C300D6A6F625F706172616D5F6B657973725C302E696F2E72656E72656E2E6D6F64756C65732E6A6F622E656E746974792E7363686564756C656A6F62656E746974795C305C305C305C305C305C305C3001025C30076C5C30086265616E6E616D65745C30126C6A6176612F6C616E672F737472696E673B6C5C300A63726561746574696D65745C30106C6A6176612F7574696C2F646174653B6C5C300E63726F6E65787072657373696F6E715C307E5C30096C5C30056A6F626964745C30106C6A6176612F6C616E672F6C6F6E673B6C5C3006706172616D73715C307E5C30096C5C300672656D61726B715C307E5C30096C5C3006737461747573745C30136C6A6176612F6C616E672F696E74656765723B7870745C3008746573747461736B73725C300E6A6176612E7574696C2E64617465686A81016B797419035C305C30787077085C305C30017910F26EE878745C300E3020302F3330202A202A202A203F73725C300E6A6176612E6C616E672E6C6F6E673B8BE490EC8F23DF025C30016A5C300576616C756578725C30106A6176612E6C616E672E6E756D62657286AC951D0B94E08B025C305C3078705C305C305C305C305C305C305C3001745C300672656E72656E745C300CE58F82E695B0E6B58BE8AF9573725C30116A6176612E6C616E672E696E746567657212E2A0A4F7818738025C3001695C300576616C756578715C307E5C30135C305C305C305C30785C30";
//        String s = escapeBytes(hex2Byte(hex));
//        System.out.println(s);
        String sql = "INSERT INTO QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, JOB_NAME, JOB_GROUP, DESCRIPTION, NEXT_FIRE_TIME, PREV_FIRE_TIME, TRIGGER_STATE, TRIGGER_TYPE, START_TIME, END_TIME, CALENDAR_NAME, MISFIRE_INSTR, JOB_DATA, PRIORITY)  VALUES('RenrenScheduler', 'TASK_1', 'DEFAULT', 'TASK_1', 'DEFAULT', null, 1619485200000, -1, 'WAITING', 'CRON', 1619485107000, 0, null, 2, x'ACED0005737200156F72672E71756172747A2E4A6F62446174614D61709FB083E8BFA9B0CB020000787200266F72672E71756172747A2E7574696C732E537472696E674B65794469727479466C61674D61708208E8C3FBC55D280200015A0013616C6C6F77735472616E7369656E74446174617872001D6F72672E71756172747A2E7574696C732E4469727479466C61674D617013E62EAD28760ACE0200025A000564697274794C00036D617074000F4C6A6176612F7574696C2F4D61703B787001737200116A6176612E7574696C2E486173684D61700507DAC1C31660D103000246000A6C6F6164466163746F724900097468726573686F6C6478703F4000000000000C7708000000100000000174000D4A4F425F504152414D5F4B45597372002E696F2E72656E72656E2E6D6F64756C65732E6A6F622E656E746974792E5363686564756C654A6F62456E7469747900000000000000010200074C00086265616E4E616D657400124C6A6176612F6C616E672F537472696E673B4C000A63726561746554696D657400104C6A6176612F7574696C2F446174653B4C000E63726F6E45787072657373696F6E71007E00094C00056A6F6249647400104C6A6176612F6C616E672F4C6F6E673B4C0006706172616D7371007E00094C000672656D61726B71007E00094C00067374617475737400134C6A6176612F6C616E672F496E74656765723B7870740008746573745461736B7372000E6A6176612E7574696C2E44617465686A81014B597419030000787077080000017910D26EE87874000E3020302F3330202A202A202A203F7372000E6A6176612E6C616E672E4C6F6E673B8BE490CC8F23DF0200014A000576616C7565787200106A6176612E6C616E672E4E756D62657286AC951D0B94E08B0200007870000000000000000174000672656E72656E74000CE58F82E695B0E6B58BE8AF95737200116A6176612E6C616E672E496E746567657212E2A0A4F781873802000149000576616C75657871007E0013000000007800', 5)";
//        String sql = "INSERT INTO \"dt_easyv_component\"(\"component_id\", \"unique_tag\", \"screen_id\", \"component_type\", \"component_name\", \"component_config\", \"triggers\", \"actions\", \"events\", \"component_data_config\", \"use_filter\", \"filters\", \"auto_update\", \"component_static_data\", \"data_type\", \"is_data_config\", \"dataFrom\", \"is_deleted\", \"parent\", \"from\", \"component_base\", \"update_time\", \"create_time\") VALUES (DEFAULT, 'kZEP6jML4NZ_I1LI2rMUx', 390, DEFAULT, '一级标题bg', '[{\\\"_name\\\":\\\"chart\\\",\\\"_displayName\\\":\\\"组件\\\",\\\"_lock\\\":true,\\\"_value\\\":[{\\\"_name\\\":\\\"dimension\\\",\\\"_displayName\\\":\\\"位置尺寸\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"chartPosition\\\",\\\"_displayName\\\":\\\"图表位置\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"left\\\",\\\"_displayName\\\":\\\"X轴坐标\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":5},{\\\"_name\\\":\\\"top\\\",\\\"_displayName\\\":\\\"Y轴坐标\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":380}],\\\"_labelLayout\\\":8},{\\\"_name\\\":\\\"chartDimension\\\",\\\"_displayName\\\":\\\"图表尺寸\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"width\\\",\\\"_displayName\\\":\\\"宽度\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":132},{\\\"_name\\\":\\\"height\\\",\\\"_displayName\\\":\\\"高度\\\",\\\"_labelLayout\\\":24,\\\"_labelPosition\\\":\\\"bottom\\\",\\\"_value\\\":62}],\\\"_labelLayout\\\":8,\\\"_lock\\\":true}]},{\\\"_name\\\":\\\"component\\\",\\\"_displayName\\\":\\\"图片\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"perspective\\\",\\\"_displayName\\\":\\\"透视\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_tip\\\":\\\"开启将影响其他属性失效（比如混合模式中的滤色效果）\\\",\\\"_step\\\":0.01},{\\\"_name\\\":\\\"borderRadius\\\",\\\"_displayName\\\":\\\"圆角\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_name\\\":\\\"margin\\\",\\\"_displayName\\\":\\\"边距\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"left\\\",\\\"_displayName\\\":\\\"左\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_name\\\":\\\"top\\\",\\\"_displayName\\\":\\\"上\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_name\\\":\\\"right\\\",\\\"_displayName\\\":\\\"右\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_name\\\":\\\"bottom\\\",\\\"_displayName\\\":\\\"下\\\",\\\"_value\\\":0,\\\"_min\\\":0,\\\"_type\\\":\\\"number\\\"}]},{\\\"_name\\\":\\\"size\\\",\\\"_displayName\\\":\\\"尺寸\\\",\\\"_value\\\":\\\"stretch\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"自适应\\\",\\\"value\\\":\\\"auto\\\"},{\\\"name\\\":\\\"拉伸以充满容器\\\",\\\"value\\\":\\\"stretch\\\"},{\\\"name\\\":\\\"真实大小\\\",\\\"value\\\":\\\"original\\\"}]},{\\\"_name\\\":\\\"overflow\\\",\\\"_displayName\\\":\\\"超出隐藏\\\",\\\"_value\\\":true,\\\"_type\\\":\\\"boolean\\\"},{\\\"_name\\\":\\\"justifyContent\\\",\\\"_displayName\\\":\\\"对齐方式\\\",\\\"_value\\\":\\\"flex-start\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"左对齐\\\",\\\"value\\\":\\\"flex-start\\\"},{\\\"name\\\":\\\"右对齐\\\",\\\"value\\\":\\\"flex-end\\\"},{\\\"name\\\":\\\"居中对齐\\\",\\\"value\\\":\\\"center\\\"}]},{\\\"_name\\\":\\\"url\\\",\\\"_labelLayout\\\":24,\\\"_value\\\":\\\"data/11690/193545/img/3LFPGgbMw_1619422981557_oIZVWNQLWZ.png\\\",\\\"_type\\\":\\\"uploadImage\\\",\\\"_updatePath\\\":[\\\"/chart/dimension/chartDimension/width\\\",\\\"/chart/dimension/chartDimension/height\\\"]},{\\\"_name\\\":\\\"event\\\",\\\"_displayName\\\":\\\"接受事件\\\",\\\"_type\\\":\\\"boolean\\\",\\\"_value\\\":true},{\\\"_name\\\":\\\"mode\\\",\\\"_displayName\\\":\\\"混合模式\\\",\\\"_value\\\":\\\"normal\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"正常\\\",\\\"value\\\":\\\"normal\\\"},{\\\"name\\\":\\\"正片叠底\\\",\\\"value\\\":\\\"multiply\\\"},{\\\"name\\\":\\\"滤色\\\",\\\"value\\\":\\\"screen\\\"},{\\\"name\\\":\\\"叠加\\\",\\\"value\\\":\\\"overlay\\\"},{\\\"name\\\":\\\"变暗\\\",\\\"value\\\":\\\"darken\\\"},{\\\"name\\\":\\\"变亮\\\",\\\"value\\\":\\\"lighten\\\"},{\\\"name\\\":\\\"颜色减淡\\\",\\\"value\\\":\\\"color-dodge\\\"},{\\\"name\\\":\\\"颜色加深\\\",\\\"value\\\":\\\"color-burn\\\"},{\\\"name\\\":\\\"强光\\\",\\\"value\\\":\\\"hard-light\\\"},{\\\"name\\\":\\\"柔光\\\",\\\"value\\\":\\\"soft-light\\\"},{\\\"name\\\":\\\"差值\\\",\\\"value\\\":\\\"difference\\\"},{\\\"name\\\":\\\"排除\\\",\\\"value\\\":\\\"exclusion\\\"},{\\\"name\\\":\\\"色相\\\",\\\"value\\\":\\\"hue\\\"},{\\\"name\\\":\\\"饱和度\\\",\\\"value\\\":\\\"saturation\\\"},{\\\"name\\\":\\\"颜色\\\",\\\"value\\\":\\\"color\\\"},{\\\"name\\\":\\\"亮度\\\",\\\"value\\\":\\\"luminosity\\\"}]},{\\\"_name\\\":\\\"initialStyle\\\",\\\"_displayName\\\":\\\"初始样式\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"opacity\\\",\\\"_displayName\\\":\\\"透明度\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":0,\\\"_max\\\":1,\\\"_step\\\":0.01},{\\\"_name\\\":\\\"rotate\\\",\\\"_displayName\\\":\\\"旋转\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"z\\\",\\\"_displayName\\\":\\\"Z\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"scale\\\",\\\"_displayName\\\":\\\"缩放\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"translate\\\",\\\"_displayName\\\":\\\"平移\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]}]}]},{\\\"_name\\\":\\\"animation\\\",\\\"_displayName\\\":\\\"动画\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationTimingFunction\\\",\\\"_displayName\\\":\\\"速度\\\",\\\"_value\\\":\\\"linear\\\",\\\"_options\\\":[{\\\"name\\\":\\\"匀速\\\",\\\"value\\\":\\\"linear\\\"},{\\\"name\\\":\\\"慢快慢\\\",\\\"value\\\":\\\"ease\\\"},{\\\"name\\\":\\\"低速开始\\\",\\\"value\\\":\\\"ease-in\\\"},{\\\"name\\\":\\\"低速结束\\\",\\\"value\\\":\\\"ease-out\\\"},{\\\"name\\\":\\\"低速开始和结束\\\",\\\"value\\\":\\\"ease-in-out\\\"}],\\\"_type\\\":\\\"select\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationDuration\\\",\\\"_displayName\\\":\\\"动画时间\\\",\\\"_value\\\":3,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationDelay\\\",\\\"_displayName\\\":\\\"动画延时\\\",\\\"_value\\\":2,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"animationIterationCount\\\",\\\"_displayName\\\":\\\"次数\\\",\\\"_value\\\":\\\"infinite\\\",\\\"_options\\\":[{\\\"name\\\":\\\"循环\\\",\\\"value\\\":\\\"infinite\\\"},{\\\"name\\\":\\\"单次\\\",\\\"value\\\":1}],\\\"_type\\\":\\\"select\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"type\\\",\\\"_displayName\\\":\\\"类型\\\",\\\"_value\\\":\\\"opacity\\\",\\\"_options\\\":[{\\\"name\\\":\\\"透明度\\\",\\\"value\\\":\\\"opacity\\\"},{\\\"name\\\":\\\"缩放\\\",\\\"value\\\":\\\"scale\\\"},{\\\"name\\\":\\\"顺时针旋转\\\",\\\"value\\\":\\\"rotateClockwise\\\"},{\\\"name\\\":\\\"逆时针旋转\\\",\\\"value\\\":\\\"rotateAnticlockwise\\\"},{\\\"name\\\":\\\"回旋转\\\",\\\"value\\\":\\\"whirling\\\"},{\\\"name\\\":\\\"自定义\\\",\\\"value\\\":\\\"custom\\\"}],\\\"_type\\\":\\\"select\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true],[\\\"type\\\",\\\"$eq\\\",\\\"custom\\\"]],\\\"_name\\\":\\\"animationSpan\\\",\\\"_displayName\\\":\\\"动画间隔\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true],[\\\"type\\\",\\\"$eq\\\",\\\"custom\\\"]],\\\"_name\\\":\\\"keyframes\\\",\\\"_displayName\\\":\\\"关键帧\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"keyframe\\\",\\\"_displayName\\\":\\\"帧\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"opacity\\\",\\\"_displayName\\\":\\\"透明度\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":0,\\\"_max\\\":1,\\\"_step\\\":0.01},{\\\"_name\\\":\\\"rotate\\\",\\\"_displayName\\\":\\\"旋转\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"z\\\",\\\"_displayName\\\":\\\"Z\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":-180,\\\"_max\\\":180,\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"scale\\\",\\\"_displayName\\\":\\\"缩放\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":1,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]},{\\\"_name\\\":\\\"translate\\\",\\\"_displayName\\\":\\\"平移\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"x\\\",\\\"_displayName\\\":\\\"X\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"y\\\",\\\"_displayName\\\":\\\"Y\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\",\\\"_step\\\":0.01}]}]}],\\\"_type\\\":\\\"array\\\"}]},{\\\"_name\\\":\\\"filter\\\",\\\"_displayName\\\":\\\"滤镜\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"显示\\\",\\\"_type\\\":\\\"boolean\\\",\\\"_value\\\":false},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"blur\\\",\\\"_displayName\\\":\\\"高斯模糊\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(px)\\\",\\\"_value\\\":5,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"brightness\\\",\\\"_displayName\\\":\\\"亮度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":50,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"contrast\\\",\\\"_displayName\\\":\\\"对比度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":50,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"grayscale\\\",\\\"_displayName\\\":\\\"灰度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":50,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"hueRotate\\\",\\\"_displayName\\\":\\\"色相\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"色环角度值\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"range\\\",\\\"_min\\\":0,\\\"_max\\\":360}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"invert\\\",\\\"_displayName\\\":\\\"反色\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"saturate\\\",\\\"_displayName\\\":\\\"饱和度\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":100,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"sepia\\\",\\\"_displayName\\\":\\\"褐色\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值(%)\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"}]},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"dropShadow\\\",\\\"_displayName\\\":\\\"阴影\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"show\\\",\\\"_displayName\\\":\\\"开启\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"hShadow\\\",\\\"_displayName\\\":\\\"水平偏移\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"vShadow\\\",\\\"_displayName\\\":\\\"垂直偏移\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"blur\\\",\\\"_displayName\\\":\\\"模糊值\\\",\\\"_value\\\":0,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"show\\\",\\\"$eq\\\",true]],\\\"_name\\\":\\\"color\\\",\\\"_displayName\\\":\\\"颜色\\\",\\\"_value\\\":\\\"#000\\\",\\\"_type\\\":\\\"color\\\"}]}]}]},{\\\"_name\\\":\\\"interaction\\\",\\\"_displayName\\\":\\\"交互\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"callback\\\",\\\"_displayName\\\":\\\"回调参数\\\",\\\"_type\\\":\\\"array\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"callback\\\",\\\"_displayName\\\":\\\"回调\\\",\\\"_type\\\":\\\"object\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"action\\\",\\\"_displayName\\\":\\\"匹配动作\\\",\\\"_value\\\":\\\"click\\\",\\\"_type\\\":\\\"select\\\",\\\"_options\\\":[{\\\"name\\\":\\\"鼠标点击\\\",\\\"value\\\":\\\"click\\\"},{\\\"name\\\":\\\"鼠标移入\\\",\\\"value\\\":\\\"mouseEnter\\\"},{\\\"name\\\":\\\"鼠标移出\\\",\\\"value\\\":\\\"mouseLeave\\\"}]},{\\\"_name\\\":\\\"origin\\\",\\\"_displayName\\\":\\\"字段值\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"},{\\\"_name\\\":\\\"target\\\",\\\"_displayName\\\":\\\"变量名\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"}]}]},{\\\"_name\\\":\\\"events\\\",\\\"_displayName\\\":\\\"交互事件\\\",\\\"_type\\\":\\\"array\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"event\\\",\\\"_displayName\\\":\\\"事件\\\",\\\"_type\\\":\\\"object\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"type\\\",\\\"_displayName\\\":\\\"事件类型\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"click\\\",\\\"_options\\\":[{\\\"name\\\":\\\"鼠标单击\\\",\\\"value\\\":\\\"click\\\"}]},{\\\"_name\\\":\\\"action\\\",\\\"_displayName\\\":\\\"动作\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"show\\\",\\\"_options\\\":[{\\\"name\\\":\\\"显示\\\",\\\"value\\\":\\\"show\\\"},{\\\"name\\\":\\\"隐藏\\\",\\\"value\\\":\\\"hide\\\"},{\\\"name\\\":\\\"显隐切换\\\",\\\"value\\\":\\\"show/hide\\\"},{\\\"name\\\":\\\"组件状态切换\\\",\\\"value\\\":\\\"switchState\\\"},{\\\"name\\\":\\\"页面跳转\\\",\\\"value\\\":\\\"redirect\\\"}]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$eq\\\",\\\"redirect\\\"]],\\\"_name\\\":\\\"page\\\",\\\"_displayName\\\":\\\"页面\\\",\\\"_type\\\":\\\"pageOptions\\\",\\\"_value\\\":\\\"\\\",\\\"_options\\\":[]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"redirect\\\"],[\\\"action\\\",\\\"$neq\\\",\\\"switchState\\\"]],\\\"_name\\\":\\\"component\\\",\\\"_displayName\\\":\\\"组件\\\",\\\"_type\\\":\\\"componentOptions\\\",\\\"_value\\\":\\\"\\\",\\\"_options\\\":[]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$eq\\\",\\\"switchState\\\"]],\\\"_name\\\":\\\"panel\\\",\\\"_displayName\\\":\\\"动态面板\\\",\\\"_type\\\":\\\"panelOptions\\\",\\\"_value\\\":\\\"{}\\\",\\\"_options\\\":[]},{\\\"_name\\\":\\\"closeType\\\",\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"redirect\\\"],[\\\"action\\\",\\\"$eq\\\",\\\"show\\\"]],\\\"_displayName\\\":\\\"关闭方式\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"manual\\\",\\\"_options\\\":[{\\\"name\\\":\\\"自动关闭\\\",\\\"value\\\":\\\"auto\\\"},{\\\"name\\\":\\\"手动关闭\\\",\\\"value\\\":\\\"manual\\\"}]},{\\\"_name\\\":\\\"showTime\\\",\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"redirect\\\"],[\\\"action\\\",\\\"$eq\\\",\\\"show\\\"],[\\\"closeType\\\",\\\"$eq\\\",\\\"auto\\\"]],\\\"_displayName\\\":\\\"显示时长(s)\\\",\\\"_value\\\":10,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"command\\\"]],\\\"_name\\\":\\\"animationName\\\",\\\"_displayName\\\":\\\"动画类型\\\",\\\"_type\\\":\\\"select\\\",\\\"_value\\\":\\\"fade\\\",\\\"_options\\\":[{\\\"name\\\":\\\"渐隐渐显\\\",\\\"value\\\":\\\"fade\\\"},{\\\"name\\\":\\\"缩放\\\",\\\"value\\\":\\\"zoom\\\"},{\\\"name\\\":\\\"向右滑动\\\",\\\"value\\\":\\\"slide_from_left\\\"},{\\\"name\\\":\\\"向左滑动\\\",\\\"value\\\":\\\"slide_from_right\\\"}]},{\\\"_rule\\\":[[\\\"action\\\",\\\"$neq\\\",\\\"command\\\"]],\\\"_name\\\":\\\"duration\\\",\\\"_displayName\\\":\\\"动画时长(ms)\\\",\\\"_value\\\":600,\\\"_type\\\":\\\"number\\\"},{\\\"_rule\\\":[[\\\"action\\\",\\\"$eq\\\",\\\"command\\\"]],\\\"_name\\\":\\\"command\\\",\\\"_displayName\\\":\\\"指令\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"key\\\",\\\"_displayName\\\":\\\"名称\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"},{\\\"_name\\\":\\\"value\\\",\\\"_displayName\\\":\\\"值\\\",\\\"_type\\\":\\\"input\\\",\\\"_value\\\":\\\"\\\"},{\\\"_name\\\":\\\"random\\\",\\\"_displayName\\\":\\\"随机值\\\",\\\"_value\\\":false,\\\"_type\\\":\\\"boolean\\\"}]}]}]},{\\\"_name\\\":\\\"remoteControl\\\",\\\"_displayName\\\":\\\"远程控制\\\",\\\"_type\\\":\\\"array\\\",\\\"_value\\\":[],\\\"_template\\\":[{\\\"_name\\\":\\\"controls\\\",\\\"_displayName\\\":\\\"控制\\\",\\\"_type\\\":\\\"object\\\",\\\"_value\\\":[{\\\"_name\\\":\\\"control\\\",\\\"_displayName\\\":\\\"控制\\\",\\\"_type\\\":\\\"remoteOptions\\\",\\\"_value\\\":\\\"{}\\\"}]}]}]}]', '[{\\\"name\\\":\\\"鼠标点击\\\",\\\"value\\\":\\\"click\\\"},{\\\"name\\\":\\\"鼠标移入\\\",\\\"value\\\":\\\"mouseEnter\\\"},{\\\"name\\\":\\\"鼠标移出\\\",\\\"value\\\":\\\"mouseLeave\\\"}]', '[]', '[]', '{}', 0, '[]', '{\\\"isAuto\\\":false,\\\"interval\\\":10}', '{\\\"data\\\":[{\\\"url\\\":\\\"\\\"}],\\\"fields\\\":[{\\\"name\\\":\\\"url\\\",\\\"value\\\":\\\"url\\\",\\\"desc\\\":\\\"图片地址\\\"}]}', 'static', 1, DEFAULT, 0, NULL, 0, '{\\\"module_name\\\":\\\"image\\\",\\\"version\\\":\\\"2.5.11\\\"}', DEFAULT, DEFAULT)";
        boolean hexSql = isHexSql(sql);
        System.out.println(hexSql);
        if(hexSql) {
            String string = handleHexString(sql);
            System.out.println(string);
        }
    }
}
