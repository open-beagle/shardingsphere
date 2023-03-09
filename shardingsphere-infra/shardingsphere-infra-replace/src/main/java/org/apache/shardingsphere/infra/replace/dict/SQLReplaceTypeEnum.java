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

package org.apache.shardingsphere.infra.replace.dict;

import lombok.Getter;

/**
 * SQL替换类型
 * @author SmileCircle
 */
public enum SQLReplaceTypeEnum {
    
    /**
     * SQL 字符替换
     */
    REPLACE("REPLACE", "SQL 字符替换"),
    /**
     * SQL 重写
     */
    REWRITE("REWRITE", "SQL 重写"),
    /**
     * 二进制 重写
     */
    BINARY("BINARY", "二进制 重写");
    
    /**
     * 编码
     */
    @Getter
    private final String code;
    /**
     * 名称
     */
    @Getter
    private final String name;
    
    SQLReplaceTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
