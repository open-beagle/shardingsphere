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

package org.apache.shardingsphere.infra.database.metadata.dialect;

import java.util.Properties;

import org.apache.shardingsphere.infra.database.metadata.DataSourceMetaData;
import org.apache.shardingsphere.infra.database.metadata.url.JdbcUrl;
import org.apache.shardingsphere.infra.database.metadata.url.StandardJdbcUrlParser;

import lombok.Getter;

/**
 * Data source metadata for GBase. by wuwanli
 */
@Getter
public final class GBaseDataSourceMetaData implements DataSourceMetaData {

    private static final int DEFAULT_PORT = 11088;

    private final String hostname;

    private final int port;

    private final String catalog;

    private final String schema;

    private final Properties queryProperties;


    public GBaseDataSourceMetaData(final String url, final String username) {
        String replaceUrl=url.replace("gbasedbt-sqli","gbasedbt");
        JdbcUrl jdbcUrl = new StandardJdbcUrlParser().parse(replaceUrl);
        hostname = jdbcUrl.getHostname();
        port = -1 == jdbcUrl.getPort() ? DEFAULT_PORT : jdbcUrl.getPort();
        catalog = jdbcUrl.getDatabase();
        schema = username;
        queryProperties = jdbcUrl.getQueryProperties();
    }

    @Override
    public Properties getDefaultQueryProperties() {
        return new Properties();
    }
}
