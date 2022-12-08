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

package org.apache.shardingsphere.proxy.frontend.util;

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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

/**
 * 南向：原始SQL字符替换 by wuwanli
 */
@Slf4j
public class SQLReplaceUtil {
    public static Map<String, String> REPLACE_STRING_MAP = new ConcurrentHashMap<>();
    public static final String REFLESH_SQL = "REFLESH_SQL";
    public static final String SQL_FILE_PATH = "SQL_FILE_PATH";
    static {
        SQLReplaceUtil.parseFile();
    }

    public static ByteBuf replace(final ByteBuf message) {
        ByteBuf newMessage = message;
        try {
            // 是否动态刷新
            String isRefreshSql = System.getenv(REFLESH_SQL);
            if ("true".equalsIgnoreCase(isRefreshSql)) {
                REPLACE_STRING_MAP.clear();
                parseFile();
            }
            byte[] result = new byte[message.readableBytes()];
            message.readBytes(result);
            final String originalSQL = new String(result, StandardCharsets.UTF_8);
            String sql = SQLReplaceUtil.replace(originalSQL);
            newMessage = Unpooled.wrappedBuffer(sql.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            log.error("sql replace exception", exception);
        }
        return newMessage;
    }

    // 初次加载json 到map里头
    private static void parseFile() {
        try {
            String sqlFilePath = System.getenv(SQL_FILE_PATH);
            if (StringUtils.isBlank(sqlFilePath)) {
                return;
            }
            ObjectMapper objectMapper = new ObjectMapper();
            ArrayNode arrayNode = (ArrayNode)objectMapper.readTree(new File(sqlFilePath));
            for (JsonNode jsonNode : arrayNode) {
                String key = jsonNode.get("key").textValue();
                String value = jsonNode.get("value").textValue();
                REPLACE_STRING_MAP.put(key, value);
            }
        } catch (Exception exception) {
            log.error("parseFile exception:", exception);
        }
    }

    private static String replace(final String originalSQL) {
        String changeSQL = originalSQL;
        log.info("sql字符替换之前：" + changeSQL);
        try {
            Set<String> keySet = REPLACE_STRING_MAP.keySet();
            for (String key : keySet) {
                String value = REPLACE_STRING_MAP.get(key);
                if (StringUtils.isBlank(value)) {
                    continue;
                }
                key = decode(key);
                if (changeSQL.contains(key)) {
                    changeSQL = changeSQL.replace(key, decode(value));
                }
            }
            // 删除最后sql 以;字符结尾
            changeSQL = changeSQL.replaceFirst(";$", "");
            if (originalSQL.equals(changeSQL)) {
                log.info("sql没有更改");
            } else {
                log.info("sql字符替换之后：" + changeSQL);
            }
        } catch (Exception exception) {
            log.error("sql replace exception:", exception);
        }
        return changeSQL;
    }

    /**
     * Base64 解码
     *
     * @param encodeStr 编码后字符
     * @return 解码后字符
     */
    private static String decode(String encodeStr) {
        byte[] decodeBytes = Base64.getDecoder().decode(encodeStr);
        return new String(decodeBytes);
    }

    /**
     * Base64 编码
     *
     * @param str 原始字符
     * @return 编码后字符
     */
    private static String encode(String str) {
        return Base64.getEncoder().encodeToString(str.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) {
        String str = "\"country code\"";
        System.out.println(SQLReplaceUtil.encode(str));
    }
}