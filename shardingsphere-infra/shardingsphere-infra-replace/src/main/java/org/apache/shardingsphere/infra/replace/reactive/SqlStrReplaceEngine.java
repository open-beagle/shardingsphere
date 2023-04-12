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
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.infra.replace.SqlReplace;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.infra.replace.model.SqlConvert;
import org.apache.shardingsphere.infra.replace.util.etcd.EtcdKey;
import org.apache.shardingsphere.infra.replace.util.etcd.JetcdClientUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * SQL 字符替换
 * @author SmileCircle
 */
@Slf4j
public class SqlStrReplaceEngine implements SqlReplace {

    private static final String INSTANCE_ENV_KEY = "INSTANCE_ID";

        private static final String INSTANCE_ID = System.getenv(INSTANCE_ENV_KEY);

    private static final List<SqlConvert> SQL_CONVERT_RULE= new ArrayList<>();

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    @Override
    public String replace(String sql, Object obj) {
        return replaceSql(sql, (SQLStrReplaceTriggerModeEnum) obj);
    }

    @Override
    public SQLReplaceTypeEnum getType() {
        return SQLReplaceTypeEnum.REPLACE;
    }

    /**
     * 替换SQL
     * @param sql
     * @return
     */
    private static String replaceSql(String sql, SQLStrReplaceTriggerModeEnum triggerMode) {
        if (StringUtils.isNotBlank(INSTANCE_ID)) {
            List<SqlConvert> sqlConvert = getSqlConvert();
            if(sqlConvert.size() > 0) {
                List<SqlConvert> sqlConvertList = sqlConvert.stream().filter(item -> Objects.equals(triggerMode.getCode(), item.getTriggerMode())).collect(Collectors.toList());
                for (SqlConvert convert : sqlConvertList) {
                    String raw = convert.getRaw();
                    String dist = convert.getDist();
                    if(Objects.equals(convert.getIsBase64(), Boolean.TRUE)) {
                        raw = (decode(raw));
                        dist = (decode(dist));
                    }
                    sql = replace(raw, dist, sql, convert.getIsRegular());
                }
            }
            return sql;
        }
        return sql;
    }

    /**
     * SQL 字符替换
     * @param raw 原始字符串
     * @param dist 目标字符串
     * @param sourceSql SQL
     * @param isRegular 是否正则替换
     * @return 替换后的SQL
     */
    private static String replace(String raw, String dist, String sourceSql, Boolean isRegular) {
        if(Objects.isNull(isRegular) || Objects.equals(isRegular, false)) {
            return StringUtils.replace(sourceSql, raw, dist);
        } else {
            return StringUtils.replacePattern(sourceSql, raw, dist);
        }
    }

    @Override
    public void init() {
        if(StringUtils.isNotBlank(INSTANCE_ID)) {
            loadSqlConvert();
            scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    SqlStrReplaceEngine.loadSqlConvert();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }, 10L, 30L, TimeUnit.SECONDS);
        }
    }

    /**
     * 获取SQL转换规则
     * @return
     */
    private static void loadSqlConvert() {
        lock.writeLock().lock();
        try {
            SQL_CONVERT_RULE.clear();
            GetOption getOption = GetOption.newBuilder().withPrefix(ByteSequence.from(EtcdKey.SQL_CONVERT, StandardCharsets.UTF_8)).build();
            GetResponse response = JetcdClientUtil.getWithPrefix(EtcdKey.SQL_CONVERT, getOption);
            if (Objects.nonNull(response)) {
                response.getKvs().forEach(item -> {
                    SqlConvert convert = JSONObject.parseObject(item.getValue().toString(StandardCharsets.UTF_8), SqlConvert.class);
                    if (Objects.equals(convert.getInstanceId(), SqlStrReplaceEngine.INSTANCE_ID)) {
                        SQL_CONVERT_RULE.add(convert);
                    }
                });
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static List<SqlConvert> getSqlConvert() {
        lock.readLock().lock();
        try {
            return SQL_CONVERT_RULE;
        } finally {
            lock.readLock().unlock();
        }
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
}
