package org.apache.shardingsphere.infra.database;

import java.util.HashMap;

public class ThreadLocalManager {
    private static InheritableThreadLocal<String> databaseThreadLocal = new InheritableThreadLocal<>();

    private static InheritableThreadLocal<HashMap<String, Object>> sqlThreadLocal = new InheritableThreadLocal<>();

    public static String getBackendConnectionDatabase() {
        return databaseThreadLocal.get();
    }
    public static void setBackendConnectionDatabase(String database) {
        databaseThreadLocal.set(database);
    }
    public static void clearBackendConnectionDatabase() {
        databaseThreadLocal.remove();
    }

    public static void clearSqlThreadLocal() {
        sqlThreadLocal.remove();
    }

    public static HashMap<String, Object> getSqlThreadLocal() {
        return sqlThreadLocal.get();
    }
}
