russian consulate bot
----

# How to build a docker image and run locally

```bash
./gradlew bootBuildImage
# You will receive smth like this
# Successfully built image 'docker.io/library/russian-consulate-bot:0.0.1-SNAPSHOT'
docker run --rm russian-consulate-bot:0.0.1-SNAPSHOT
```

# How to deploy a new docker image to the remove server

```bash
./gradlew bootBuildImage
# You will receive smth like this
# Successfully built image 'docker.io/library/russian-consulate-bot:0.0.1-SNAPSHOT'

# To send docker image to the server use
docker save russian-consulate-bot:0.0.1-SNAPSHOT | ssh -C root@188.166.87.53 docker load

# To restart a docker use
ssh root@188.166.87.53 "docker stop bot && docker rm bot && docker run --name=bot --restart=always -d russian-consulate-bot:0.0.1-SNAPSHOT"
```

# How to login to the remote server
```bash
ssh root@188.166.87.53
```

