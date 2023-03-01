package org.apache.shardingsphere.infra.replace.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据库类型
 * @author SmileCircle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DatabaseType extends Base {

    /**
     * ID
     */
    private String id;

    /**
     * 数据库中文名称
     */
    private String dbTypeNameCn;

    /**
     * 数据库名称 如 mysql
     */
    private String databaseTypeName;

    /**
     * 数据库版本 如 1 v1
     */
    private String databaseTypeVersion;

    /**
     * 允许类型：1:北向 2:南向
     */
    private String allowType;

    /**
     * 是否启用:0:未启用，1：启用
     */
    private String isEnable;

    /**
     * 连接驱动类地址
     */
    private String driverJarUrl;

    /**
     * 连接驱动类
     */
    private String driverClass;

    /**
     * 连接字符串前缀
     */
    private String prefix;

    /**
     * 备注
     */
    private String remarks;

    /**
     * 排序
     */
    private Integer sort;

}
