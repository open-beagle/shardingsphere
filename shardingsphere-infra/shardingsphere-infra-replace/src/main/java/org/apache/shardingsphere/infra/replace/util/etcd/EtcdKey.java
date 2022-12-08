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

package org.apache.shardingsphere.infra.replace.util.etcd;

/**
 * @author SmileCircle
 */
public interface EtcdKey {
    
    /**
     * SQL监控
     */
    String SQL_SOUTH_DATABASE = "PRD_SOUTH_DATABASE-";
    /**
     * SQL监控
     */
    String SQL_NORTH_DATABASE = "PRD_NORTH_DATABASE-";
    /**
     * SQL监控
     */
    String SQL_MONITOR = "PRD_SQL_MONITOR-";
    /**
     * SQL改写
     */
    String SQL_REWRITE = "PRD_SQL_REWRITE-";
    /**
     * SQL转换规则
     */
    String SQL_CONVERT = "PRD_SQL_CONVERT-";
}
