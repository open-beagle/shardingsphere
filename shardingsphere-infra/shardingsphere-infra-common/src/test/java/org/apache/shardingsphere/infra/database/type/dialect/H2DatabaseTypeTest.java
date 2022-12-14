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

package org.apache.shardingsphere.infra.database.type.dialect;

import org.apache.shardingsphere.infra.database.metadata.dialect.H2DataSourceMetaData;
import org.apache.shardingsphere.sql.parser.sql.common.constant.QuoteCharacter;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class H2DatabaseTypeTest {
    
    @Test
    public void assertGetName() {
        assertThat(new H2DatabaseType().getType(), is("H2"));
    }
    
    @Test
    public void assertGetJdbcUrlPrefixes() {
        assertThat(new H2DatabaseType().getJdbcUrlPrefixes(), is(Collections.singleton("jdbc:h2:")));
    }
    
    @Test
    public void assertGetDataSourceMetaData() {
        assertThat(new H2DatabaseType().getDataSourceMetaData("jdbc:h2:~:primary_ds_0", "sa"), instanceOf(H2DataSourceMetaData.class));
        assertThat(new H2DatabaseType().getDataSourceMetaData("jdbc:h2:mem:primary_ds_0", "sa"), instanceOf(H2DataSourceMetaData.class));
    }
    
    @Test
    public void assertGetTrunkDatabaseType() {
        assertThat(new H2DatabaseType().getTrunkDatabaseType().getType(), is("MySQL"));
    }
    
    @Test
    public void assertGetSchema() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("ds");
        assertThat(new H2DatabaseType().getSchema(connection), is("ds"));
    }
    
    @Test
    public void assertFormatTableNamePattern() {
        assertThat(new H2DatabaseType().formatTableNamePattern("tbl"), is("tbl"));
    }
    
    @Test
    public void assertGetQuoteCharacter() {
        QuoteCharacter actual = new H2DatabaseType().getQuoteCharacter();
        assertThat(actual.getStartDelimiter(), is("\""));
        assertThat(actual.getEndDelimiter(), is("\""));
    }
}
