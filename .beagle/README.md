# shardingsphere-proxy

https://github.com/apache/shardingsphere

```bash
git remote add upstream git@github.com:apache/shardingsphere.git
git fetch upstream
git merge 5.1.2
```

## build

```bash
docker run -it --rm \
-v $PWD/:/go/src/github.com/apache/shardingsphere/ \
-w /go/src/github.com/apache/shardingsphere/proxy \
-e DRONE_WORKSPACE=/go/src/github.com/apache/shardingsphere \
registry.cn-qingdao.aliyuncs.com/wod/devops-maven:3.8-openjdk-11 \
bash -c 'mvn clean install -Prelease'
```
