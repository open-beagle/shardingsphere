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

import com.alibaba.fastjson.JSONObject;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.grpc.stub.CallStreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author : chenglei
 */
@Slf4j
public class JetcdClientUtil {
    
    private static Client client = JetcdClientFactory.getJetcdClient();
    
    public static Map<String, Watch.Watcher> watcherMap = new ConcurrentHashMap<>();
    
    /**
     * 将字符串转为客户端所需的ByteSequence实例
     *
     * @param val
     * @return
     */
    public static ByteSequence bytesOf(String val) {
        return ByteSequence.from(val, UTF_8);
    }
    
    public static Response.Header put(String key, String value) {
        try {
            return client.getKVClient().put(bytesOf(key), bytesOf(value)).get().getHeader();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        return null;
    }
    
    public static String getSingle(String key) {
        GetResponse getResponse = null;
        try {
            getResponse = client.getKVClient().get(bytesOf(key)).get();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        return getResponse.getCount() > 0 ? getResponse.getKvs().get(0).getValue().toString(UTF_8) : null;
    }
    
    public static <T> T getSingleObject(String key, Class<T> clazz) {
        GetResponse getResponse = null;
        try {
            getResponse = client.getKVClient().get(bytesOf(key)).get();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        if (getResponse.getCount() > 0) {
            String returnStr = getResponse.getKvs().get(0).getValue().toString(UTF_8);
            return JSONObject.parseObject(returnStr, clazz);
        } else {
            return null;
        }
    }
    
    public static GetResponse getRange(String key, GetOption getOption) {
        try {
            return client.getKVClient().get(bytesOf(key), getOption).get();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        return null;
    }
    
    public static GetResponse getWithPrefix(String key, GetOption getOption) {
        GetResponse response = null;
        try {
            if (null == key || "".equals(key)) {
                throw new NullPointerException();
            }
            ByteSequence keyByte = bytesOf(key);
            response = client.getKVClient().get(keyByte, getOption).get();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        return response;
    }
    
    public static long deleteSingle(String key) {
        long result = 0L;
        try {
            result = client.getKVClient().delete(bytesOf(key)).get().getDeleted();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        return result;
    }
    
    public static long deleteRange(String key, DeleteOption deleteOption) {
        long result = 0L;
        try {
            result = client.getKVClient().delete(bytesOf(key), deleteOption).get().getDeleted();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        return result;
    }
    
    public static void putWithLease(String key, String value) {
        Lease leaseClient = client.getLeaseClient();
        leaseClient.grant(10000).thenAccept(result -> {
            // 租约ID
            long leaseId = result.getID();
            log.info("[{}]申请租约成功，租约ID [{}]", key, Long.toHexString(leaseId));
            // 准备好put操作的client
            KV kvClient = client.getKVClient();
            // put操作时的可选项，在这里指定租约ID
            PutOption putOption = PutOption.newBuilder().withLeaseId(leaseId).build();
            // put操作
            kvClient.put(bytesOf(key), bytesOf(value), putOption)
                    .thenAccept(putResponse -> {
                        // put操作完成后，再设置无限续租的操作
                        leaseClient.keepAlive(leaseId, new CallStreamObserver<LeaseKeepAliveResponse>() {
                            
                            @Override
                            public boolean isReady() {
                                return false;
                            }
                            @Override
                            public void setOnReadyHandler(Runnable onReadyHandler) {
                            }
                            @Override
                            public void disableAutoInboundFlowControl() {
                            }
                            @Override
                            public void request(int count) {
                            }
                            @Override
                            public void setMessageCompression(boolean enable) {
                            }
                            /**
                             * 每次续租操作完成后，该方法都会被调用
                             * @param value
                             */
                            @Override
                            public void onNext(LeaseKeepAliveResponse value) {
                                log.info("[{}]续租完成，TTL[{}]", Long.toHexString(leaseId), value.getTTL());
                            }
                            @Override
                            public void onError(Throwable t) {
                                log.error("onError", t);
                            }
                            @Override
                            public void onCompleted() {
                                JetcdClientUtil.deleteSingle(key);
                                log.info("onCompleted");
                            }
                        });
                    });
        });
    }
    
    public static void watchKey(String watchKey) {
        try {
            // 先检查指定的key在etcd中是否存在
            // 查询条件中指定只返回key
            GetOption getOption = GetOption.newBuilder().withPrefix(bytesOf(watchKey)).withCountOnly(true).build();
            // 如果数量小于1,表示指定的key在etcd中不存在
            Long count = client.getKVClient().get(bytesOf(watchKey), getOption).get().getCount();
            if (count > 0) {
                // 实例化一个监听对象，当监听的key发生变化时会被调用
                Watch.Listener listener = Watch.listener(watchResponse -> {
                    log.info("收到[{}]的事件", watchKey);
                    // 被调用时传入的是事件集合，这里遍历每个事件
                    watchResponse.getEvents().forEach(watchEvent -> {
                        // 操作类型
                        WatchEvent.EventType eventType = watchEvent.getEventType();
                        // 操作的键值对
                        KeyValue keyValue = watchEvent.getKeyValue();
                        // 如果是删除操作，就把该key的Watcher找出来close掉
                        if (WatchEvent.EventType.DELETE.equals(eventType)
                                && watcherMap.containsKey(watchKey)) {
                            Watch.Watcher watcher = watcherMap.remove(watchKey);
                            watcher.close();
                        }
                    });
                });
                // 添加监听
                Watch.Watcher watcher = client.getWatchClient().watch(bytesOf(watchKey), listener);
                // 将这个Watcher放入内存中保存，如果该key被删除就要将这个Watcher关闭
                watcherMap.put(watchKey, watcher);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}