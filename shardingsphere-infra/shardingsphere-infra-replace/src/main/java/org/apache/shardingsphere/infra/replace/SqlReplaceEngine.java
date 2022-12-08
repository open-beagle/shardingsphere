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

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author SmileCircle
 */
@Slf4j
public class SqlReplaceEngine {
    
    private static ConcurrentHashMap<String, SqlReplace> ENGINE = new ConcurrentHashMap<>(10);
    
    static {
        ServiceLoader<SqlReplace> loader = ServiceLoader.load(SqlReplace.class);
        loader.forEach(engine -> {
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
    public static String replaceSql(@Nonnull SQLReplaceTypeEnum type, @Nonnull final String sql, @Nullable Object obj) {
        String rawSql = sql;
        SqlReplace engine = ENGINE.get(type.getCode());
        if (Objects.nonNull(engine)) {
            try {
                String distSql = engine.replace(rawSql, obj);
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
}
