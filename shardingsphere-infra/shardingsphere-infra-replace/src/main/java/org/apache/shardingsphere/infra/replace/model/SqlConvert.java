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

package org.apache.shardingsphere.infra.replace.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * SQL字符转换
 * @author SmileCircle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SqlConvert extends Base {
    
    /**
     * ID
     */
    private String id;
    /**
     * 实例ID
     */
    private String instanceId;
    /**
     * 转换规则名称
     */
    private String name;
    /**
     * 转换规则描述
     */
    private String desc;
    /**
     * 原字符串
     */
    private String raw;
    /**
     * 目标字符串
     */
    private String dist;
    /**
     * 是否是正则
     */
    private Boolean isRegular;
    /**
     * 是否是Base64
     */
    private Boolean isBase64;
    /**
     * 触发模式 北向 - frontend 南向 - backend
     */
    private String triggerMode;
}
