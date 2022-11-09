# shardingsphere-proxy

https://github.com/apache/shardingsphere

```bash
git remote add upstream git@github.com:apache/shardingsphere.git
git fetch upstream
git merge 5.1.2
```

## build

```bash
# shardingsphere-proxy
docker run -it --rm \
-v $PWD/:/go/src/github.com/apache/shardingsphere \
-w /go/src/github.com/apache/shardingsphere/shardingsphere-proxy \
-e DRONE_WORKSPACE=/go/src/github.com/apache/shardingsphere \
registry.cn-qingdao.aliyuncs.com/wod/devops-maven:3.8-openjdk-11 \
bash -c 'mvn clean install -Prelease'

# shardingsphere-distribution/shardingsphere-proxy-distribution
docker run -it --rm \
-v $PWD/:/go/src/github.com/apache/shardingsphere \
-w /go/src/github.com/apache/shardingsphere/shardingsphere-distribution/shardingsphere-proxy-distribution \
-e DRONE_WORKSPACE=/go/src/github.com/apache/shardingsphere \
registry.cn-qingdao.aliyuncs.com/wod/devops-maven:3.8-openjdk-11 \
bash -c 'mvn clean install -Prelease'
```

## cache

```bash
# 构建缓存-->推送缓存至服务器
docker run --rm \
  -e PLUGIN_REBUILD=true \
  -e PLUGIN_ENDPOINT=$PLUGIN_ENDPOINT \
  -e PLUGIN_ACCESS_KEY=$PLUGIN_ACCESS_KEY \
  -e PLUGIN_SECRET_KEY=$PLUGIN_SECRET_KEY \
  -e DRONE_REPO_OWNER="open-beagle" \
  -e DRONE_REPO_NAME="shardingsphere" \
  -e PLUGIN_MOUNT="./.git,./.repository" \
  -v $(pwd):$(pwd) \
  -w $(pwd) \
  registry.cn-qingdao.aliyuncs.com/wod/devops-s3-cache:1.0

# 读取缓存-->将缓存从服务器拉取到本地
docker run --rm \
  -e PLUGIN_RESTORE=true \
  -e PLUGIN_ENDPOINT=$PLUGIN_ENDPOINT \
  -e PLUGIN_ACCESS_KEY=$PLUGIN_ACCESS_KEY \
  -e PLUGIN_SECRET_KEY=$PLUGIN_SECRET_KEY \
  -e DRONE_REPO_OWNER="open-beagle" \
  -e DRONE_REPO_NAME="shardingsphere" \
  -v $(pwd):$(pwd) \
  -w $(pwd) \
  registry.cn-qingdao.aliyuncs.com/wod/devops-s3-cache:1.0
```
