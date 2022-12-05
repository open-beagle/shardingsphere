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

package org.apache.shardingsphere.proxy.backend.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 字符替换 by wuwanli
 */
@Slf4j
public class SQLReplaceUtil {
    public static Map<String, String> BEFORE_REPLACE_STRING_MAP = new ConcurrentHashMap<>();
    public static Map<String, String> BEFORE_REPLACE_SQL_MAP = new ConcurrentHashMap<>();
    public static final String REFLESH_SQL = "REFLESH_SQL";
    public static final String SQL_FILE_PATH = "SQL_FILE_PATH";
    static {
        SQLReplaceUtil.parseSqlFile();
    }

    // 初次加载json 到map里头
    private static void parseSqlFile() {
        // String sqlFilePath = "E:\\beagle\\信创迁移\\test.json";
        String sqlFilePath = System.getenv(SQL_FILE_PATH);
        if (StringUtils.isNotBlank(sqlFilePath)) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                ArrayNode arrayNode = (ArrayNode)objectMapper.readTree(new File(sqlFilePath));
                for (JsonNode jsonNode : arrayNode) {
                    String type = jsonNode.get("type").textValue();
                    ArrayNode mapList = (ArrayNode)jsonNode.get("list");
                    for (JsonNode jsonNode1 : mapList) {
                        String key = jsonNode1.get("key").textValue();
                        String value = jsonNode1.get("value").textValue();
                        if ("string".equalsIgnoreCase(type)) {
                            BEFORE_REPLACE_STRING_MAP.put(key, value);
                        }
                        if ("sql".equalsIgnoreCase(type)) {
                            BEFORE_REPLACE_SQL_MAP.put(key, value);
                        }
                    }
                }
            } catch (Exception exception) {
                log.error("parseSqlFile exception:", exception);
            }
        }
    }

    private static String decode(String encodeStr) {
        byte[] decodeBytes = Base64.getDecoder().decode(encodeStr);
        return new String(decodeBytes);
    }

    public static String replace(final String originalSQL) {
        log.debug("sql字符替换之前：" + originalSQL);
        String changeSQL = originalSQL;
        try {
            // 是否动态刷新
            // String isRefreshSql = "true";
            String isRefreshSql = System.getenv(REFLESH_SQL);
            if ("true".equalsIgnoreCase(isRefreshSql)) {
                BEFORE_REPLACE_STRING_MAP.clear();
                BEFORE_REPLACE_SQL_MAP.clear();
                parseSqlFile();
            }
            // 优先替换整个sql
            Set<String> sqlKeySet = BEFORE_REPLACE_SQL_MAP.keySet();
            for (String key : sqlKeySet) {
                String value = BEFORE_REPLACE_SQL_MAP.get(key);
                key = decode(key);
                if (changeSQL.contains(key)) {
                    if (StringUtils.isBlank(value)) {
                        continue;
                    }
                    return decode(value);
                }
            }
            // 其次替换字符
            Set<String> stringKeySet = BEFORE_REPLACE_STRING_MAP.keySet();
            for (String key : stringKeySet) {
                String value = decode(BEFORE_REPLACE_STRING_MAP.get(key));
                key = decode(key);
                if (changeSQL.contains(key)) {
                    changeSQL = changeSQL.replace(key, value);
                }
            }
            changeSQL = changeSQL.replaceFirst(";$", "");
            log.debug("sql字符替换之后：" + changeSQL);
        } catch (Exception exception) {
            log.error("sql replace exception:", exception);
        }
        // 删除最后sql 以;字符结尾
        return changeSQL;
    }

    public static void main(String[] args) {
        String encodeStr = "\"country code\"";
        String encodeToString = Base64.getEncoder().encodeToString(encodeStr.getBytes(StandardCharsets.UTF_8));
        System.out.println(encodeToString);
    }
}
