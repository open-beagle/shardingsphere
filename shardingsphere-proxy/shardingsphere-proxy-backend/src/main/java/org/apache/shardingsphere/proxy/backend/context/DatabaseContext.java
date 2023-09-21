package org.apache.shardingsphere.proxy.backend.context;

import org.apache.shardingsphere.infra.database.DatabaseGetAble;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;

public class DatabaseContext implements DatabaseGetAble {

    @Override
    public ShardingSphereDatabase getDatabase(String name) {
        return ProxyContext.getInstance().getDatabase(name);
    }
}
