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
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.infra.replace.SqlReplace;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.dict.SQLStrReplaceTriggerModeEnum;
import org.apache.shardingsphere.infra.replace.model.SqlConvert;
import org.apache.shardingsphere.infra.replace.util.etcd.EtcdKey;
import org.apache.shardingsphere.infra.replace.util.etcd.JetcdClientUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SQL 字符替换
 * @author SmileCircle
 */
public class SqlStrReplaceEngine implements SqlReplace {
    
    private static final String INSTANCE_ENV_KEY = "INSTANCE_ID";
    
    private static final String INSTANCE_ID = System.getenv(INSTANCE_ENV_KEY);

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
            List<SqlConvert> sqlConvert = getSqlConvert(triggerMode);
            Map<String, String> replaceMap = sqlConvert.stream().collect(Collectors.toMap(SqlConvert::getRaw, SqlConvert::getDist));
            if (replaceMap.size() > 0) {
                for (Map.Entry<String, String> ruleSet : replaceMap.entrySet()) {
                    sql = replace(ruleSet.getKey(), ruleSet.getValue(), sql);
                }
            }
            return sql;
        }
        return sql;
    }
    
    private static String replace(String raw, String dist, String sourceSql) {
        return StringUtils.replace(sourceSql, raw, dist);
    }
    
    /**
     * 获取SQL转换规则
     * @return
     */
    private static List<SqlConvert> getSqlConvert(SQLStrReplaceTriggerModeEnum triggerMode) {
        List<SqlConvert> result = new ArrayList<>();
        GetOption getOption = GetOption.newBuilder().withPrefix(ByteSequence.from(EtcdKey.SQL_CONVERT, StandardCharsets.UTF_8)).build();
        GetResponse response = JetcdClientUtil.getWithPrefix(EtcdKey.SQL_CONVERT, getOption);
        if (Objects.nonNull(response)) {
            response.getKvs().forEach(item -> {
                SqlConvert convert = JSONObject.parseObject(item.getValue().toString(StandardCharsets.UTF_8), SqlConvert.class);
                if (Objects.equals(convert.getInstanceId(), SqlStrReplaceEngine.INSTANCE_ID)) {
                    if(Objects.nonNull(triggerMode) && Objects.equals(triggerMode.getCode(), convert.getTriggerMode())) {
                        result.add(convert);
                    }
                }
            });
        }
        return result;
    }
}
