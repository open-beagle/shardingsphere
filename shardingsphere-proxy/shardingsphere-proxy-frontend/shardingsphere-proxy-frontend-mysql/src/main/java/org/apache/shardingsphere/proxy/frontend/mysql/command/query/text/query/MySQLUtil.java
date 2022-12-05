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
package org.apache.shardingsphere.proxy.frontend.mysql.command.query.text.query;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL工具类 by wuwanli
 */
@Slf4j
public class MySQLUtil {
    /**
     * 关键字处理 TODO 后续根据读取配置动态替换
     */
    public static String replaceKeyword(String sql) {
        log.info("MySQL 原始SQL:" + sql);
        String packetSql = sql.replace(".rank", ".`rank`").replace("rank,", "`rank`,");
        log.info("MySQL 处理关键字后SQL:" + packetSql);
        return packetSql;
    }
}
