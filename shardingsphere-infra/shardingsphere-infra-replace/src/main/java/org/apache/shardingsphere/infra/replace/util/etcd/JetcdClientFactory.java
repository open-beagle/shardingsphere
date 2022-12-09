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

import io.etcd.jetcd.Client;
import lombok.extern.slf4j.Slf4j;

/**
 * @author : chenglei
 */
@Slf4j
public class JetcdClientFactory {
    
    public static final String ETCD_URI = "SQL_RULE_ETCD_URIS";
    
    private static JetcdClientFactory instance = null;
    
    private static String endpoints = System.getenv(ETCD_URI);
    
    public void setEndpoints(String endpoints) {
        JetcdClientFactory.endpoints = endpoints;
    }
    
    private JetcdClientFactory() {
    }
    
    public synchronized static JetcdClientFactory getInstance() {
        if (instance == null) {
            instance = new JetcdClientFactory();
        }
        return instance;
    }
    
    public static synchronized Client getJetcdClient() {
        Client client = null;
        try {
            client = Client.builder().endpoints(endpoints.split(",")).build();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("get new client error");
        }
        return client;
    }
    
}