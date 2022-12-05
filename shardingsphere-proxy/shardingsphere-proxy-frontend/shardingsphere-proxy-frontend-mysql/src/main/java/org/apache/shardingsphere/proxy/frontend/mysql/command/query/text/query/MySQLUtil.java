package org.apache.shardingsphere.proxy.frontend.mysql.command.query.text.query;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL工具类 by wuwanli
 */
@Slf4j
public class MySQLUtil {
    /**
     * 关键字处理 TODO 后续根据读取配置动态替换
     */
    public static String replaceKeyword(String sql) {
        log.info("MySQL 原始SQL:" + sql);
        String packetSql = sql.replace(".rank", ".`rank`").replace("rank,", "`rank`,");
        log.info("MySQL 处理关键字后SQL:" + packetSql);
        return packetSql;
    }
}
