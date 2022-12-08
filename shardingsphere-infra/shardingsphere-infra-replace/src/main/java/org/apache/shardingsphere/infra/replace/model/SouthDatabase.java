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
 * 南向数据库
 * @author SmileCircle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SouthDatabase extends Base {
    
    /**
     * ID
     */
    private String id;
    /**
     * 实例ID
     */
    private String instanceId;
    /**
     * 南向数据库名称
     */
    private String name;
    /**
     * 南向数据库中文名称
     */
    private String nameCn;
    /**
     * 数据库类型ID
     */
    private String databaseTypeId;
    /**
     * 主机名
     */
    private String host;
    /**
     * 端口
     */
    private String port;
    /**
     * 用户名
     */
    private String userName;
    /**
     * 密码
     */
    private String password;
    /**
     * 映射北向数据库
     */
    private String northDataBaseId;
    /**
     * 是否成功连接
     */
    private String connected;
}
