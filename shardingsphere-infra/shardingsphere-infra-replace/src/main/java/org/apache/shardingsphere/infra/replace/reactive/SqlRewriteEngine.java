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

package org.apache.shardingsphere.infra.replace.reactive;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.infra.replace.SqlReplace;
import org.apache.shardingsphere.infra.replace.dict.SQLReplaceTypeEnum;
import org.apache.shardingsphere.infra.replace.model.DataBaseInfo;
import org.apache.shardingsphere.infra.replace.model.SouthDatabase;
import org.apache.shardingsphere.infra.replace.model.SqlRewrite;
import org.apache.shardingsphere.infra.replace.util.StringUtil;
import org.apache.shardingsphere.infra.replace.util.etcd.EtcdKey;
import org.apache.shardingsphere.infra.replace.util.etcd.JetcdClientUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SQL 重写
 * @author SmileCircle
 */
@Slf4j
public class SqlRewriteEngine implements SqlReplace {
    
    private static final String INSTANCE_ENV_KEY = "INSTANCE_ID";
    
    private static final String INSTANCE_ID = System.getenv(INSTANCE_ENV_KEY);

    public static final String SQL_SEPARATOR = "<DB_PROXY_SQL_SEPARATOR>";
    
    @Override
    public String replace(String sql, Object obj) {
        if (Objects.nonNull(obj)) {
            return reWriteSql(sql, String.valueOf(obj));
        }
        return sql;
    }
    
    @Override
    public SQLReplaceTypeEnum getType() {
        return SQLReplaceTypeEnum.REWRITE;
    }
    
    /**
     * 重写SQL
     * @param dbName
     * @return
     */
    public static String reWriteSql(String sql, final String dbName) {
        if (StringUtils.isNotBlank(INSTANCE_ID)) {
            try {
                List<SqlRewrite> rewriteList = getSqlReWrite(dbName);
                if (rewriteList.size() > 0) {
//                    Map<String, String> rewriteMap = rewriteList.stream().collect(Collectors.toMap(SqlRewrite::getRawSql, SqlRewrite::getDistSql, (o1, o2) -> o2));
//                    if (rewriteMap.size() > 0) {
//                        for (Map.Entry<String, String> ruleSet : rewriteMap.entrySet()) {
//                            String distSql = reWriteSql(ruleSet.getKey(), ruleSet.getValue(), sql);
//                            if (!Objects.equals(sql, distSql)) {
//                                return distSql;
//                            }
//                        }
//                    }
                    for (SqlRewrite sqlRewrite : rewriteList) {
                        String distSql = reWriteSql(sqlRewrite.getRawSql(), sqlRewrite.getDistSql(), sqlRewrite.getParamRel(), sql);
                        return distSql;
                    }
                }
                return sql;
            } catch (Error | Exception e) {
                e.printStackTrace();
            }
        }
        return sql;
    }
    
    /**
     * 获取SQL重写规则
     * @param dbName
     * @return
     */
    private static List<SqlRewrite> getSqlReWrite(String dbName) {
        List<SqlRewrite> result = new ArrayList<>();
        GetOption getOption = GetOption.newBuilder().withPrefix(ByteSequence.from(EtcdKey.SQL_REWRITE, StandardCharsets.UTF_8)).build();
        GetResponse response = JetcdClientUtil.getWithPrefix(EtcdKey.SQL_REWRITE, getOption);
        if (Objects.nonNull(response)) {
            response.getKvs().forEach(item -> {
                SqlRewrite rewrite = JSONObject.parseObject(item.getValue().toString(StandardCharsets.UTF_8), SqlRewrite.class);
                if (Objects.equals(rewrite.getInstanceId(), SqlRewriteEngine.INSTANCE_ID)) {
                    if (Objects.nonNull(rewrite.getSouthDatabaseId())) {
                        SouthDatabase southDatabase = JetcdClientUtil.getSingleObject(EtcdKey.SQL_SOUTH_DATABASE + rewrite.getSouthDatabaseId(), SouthDatabase.class);
                        if (Objects.nonNull(southDatabase) && StringUtils.equalsIgnoreCase(southDatabase.getName(), dbName)) {
                            result.add(rewrite);
                        }
                    }
                }
            });
        }
        return result;
    }
    
    /**
     * 根据正则重写SQL
     * @param rawSql 原SQL
     * @param distSql 目标SQL
     * @param paramRel 参数关系
     * @param sourceSql 需要改写的SQL
     * @return
     */
    private static String reWriteSql(String rawSql, String distSql, String paramRel, String sourceSql) {
        String rawTrim = StringUtil.trimAllWhitespace(rawSql).toUpperCase(Locale.ROOT);
        log.info("清空空格后的模板: -> {}", rawTrim);
        String tempTrim = StringUtil.trimAllWhitespace(sourceSql).toUpperCase(Locale.ROOT);
        log.info("清空空格后的SQL: -> {}", tempTrim);

        JSONArray paramRelArray = null;
        if(StringUtils.isNotBlank(paramRel)) {
            paramRelArray = JSONArray.parseArray(paramRel);
        }

        boolean matches;
        boolean isHaveParam;
        if (rawTrim.contains("?")) {
            String regex = getMatchRegex(rawTrim);
            log.info("正则表达式: -> {}", regex);
            Pattern matchPatten = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            matches = matchPatten.matcher(tempTrim).matches();
            isHaveParam = true;
        } else {
            matches = Objects.equals(rawTrim, tempTrim);
            isHaveParam = false;
        }
        log.info("是否匹配成功： -> {}", matches);
        if (matches) {
            if (isHaveParam) {
                String replaceRegex = "\\?";
                String[] split = rawSql.split(replaceRegex);
                String paramStr = sourceSql;
                for (String str : split) {
                    int begin = paramStr.indexOf(str);
                    String start = paramStr.substring(0, begin);
                    String end = paramStr.substring(begin + str.length());
                    paramStr = start + SQL_SEPARATOR + end;
                }
                List<String> paramList = Stream.of(paramStr.split(SQL_SEPARATOR)).collect(Collectors.toList());
                paramList.removeIf(StringUtils::isEmpty);
                log.info("参数: -> {}", paramList);
                Pattern pattern = Pattern.compile(replaceRegex);
                Matcher matcher = pattern.matcher(distSql);
                StringBuffer sb = new StringBuffer();
                int index = 0;
                while (matcher.find()) {
                    String param = Objects.nonNull(paramRelArray) ? paramList.get(paramRelArray.getInteger(index) - 1) : paramList.get(index);
                    matcher.appendReplacement(sb, param);
                    index++;
                }
                matcher.appendTail(sb);
                String targetSql = sb.toString();
                log.info("结果SQL： -> {}", targetSql);
                return targetSql;
            } else {
                return distSql;
            }
        }
        return sourceSql;
    }
    
    /**
     * 构建数据库连接信息对象
     * @param connectionUrl
     * @return
     */
    private static DataBaseInfo buildDbInfo(String connectionUrl) {
        String[] split = connectionUrl.split("://");
        String dbInfo = split[1];
        String[] hostSplit = dbInfo.split(":");
        String host = hostSplit[0];
        String[] portSplit = hostSplit[1].split("/");
        String port = portSplit[0];
        String dbName = portSplit[1];
        return new DataBaseInfo(host, port, dbName);
    }
    
    /**
     * 获取匹配正则
     * @param sql
     * @return
     */
    private static String getMatchRegex(String sql) {
        String regex = sql.replace("\\", "\\\\");
        regex = regex.replace("*", "\\*");
        regex = regex.replace(".", "\\.");
        regex = regex.replace("+", "\\+");
        regex = regex.replace("^", "\\^");
        regex = regex.replace("$", "\\$");
        regex = regex.replace("|", "\\|");
        regex = regex.replace("/", "\\/");
        regex = regex.replace("[", "\\[");
        regex = regex.replace("]", "\\]");
        regex = regex.replace("(", "\\(");
        regex = regex.replace(")", "\\)");
        regex = regex.replace("{", "\\{");
        regex = regex.replace("}", "\\}");
        regex = regex.replace("?", ".*");
        return regex;
    }
}
