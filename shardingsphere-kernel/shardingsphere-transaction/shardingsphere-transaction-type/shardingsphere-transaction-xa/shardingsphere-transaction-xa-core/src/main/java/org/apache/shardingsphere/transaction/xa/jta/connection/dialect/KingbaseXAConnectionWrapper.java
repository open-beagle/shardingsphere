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

package org.apache.shardingsphere.transaction.xa.jta.connection.dialect;

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.XAConnection;
import javax.sql.XADataSource;

import org.apache.shardingsphere.transaction.xa.jta.connection.XAConnectionWrapper;

import lombok.SneakyThrows;

/**
 * XA connection wrapper for Kingbase.
 */
public final class KingbaseXAConnectionWrapper implements XAConnectionWrapper {
    
    private static volatile Class<Connection> jdbcConnectionClass;
    
    private static volatile Constructor<?> xaConnectionConstructor;
    
    private static volatile boolean initialized;
    
    @Override
    public XAConnection wrap(final XADataSource xaDataSource, final Connection connection) throws SQLException {
        if (!initialized) {
            loadReflection();
            initialized = true;
        }
        return createXAConnection(connection.unwrap(jdbcConnectionClass));
    }
    
    private void loadReflection() {
        jdbcConnectionClass = getJDBCConnectionClass();
        xaConnectionConstructor = getXAConnectionConstructor();
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows(ReflectiveOperationException.class)
    private Class<Connection> getJDBCConnectionClass() {
        return (Class<Connection>) Class.forName("com.kingbase8.core.BaseConnection");
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private Constructor<?> getXAConnectionConstructor() {
        return Class.forName("com.kingbase8.xa.KBXAConnection").getConstructor(Class.forName("com.kingbase8.core.BaseConnection"));
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private XAConnection createXAConnection(final Connection connection) {
        return (XAConnection) xaConnectionConstructor.newInstance(connection);
    }
    
    @Override
    public String getType() {
        return "kingbase8";
    }
}
