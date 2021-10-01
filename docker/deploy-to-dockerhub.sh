#!/bin/bash
cd ..
git pull --allow-unrelated-histories
mvn clean package -Dmaven.test.skip=true
cp target/jd-qinglong-*.jar docker/
cd docker
docker build -t rubyangxg/jd-qinglong:1.1 --build-arg JAR_FILE=jd-qinglong-1.0.jar .
#docker push rubyangxg/jd-qinglong:1.1

#mvn clean package -Dmaven.test.skip=true && docker-compose -f docker-compose-debug.yml --env-file=env.properties  build --no-cache webapp
#docker-compose -f docker-compose-debug.yml --env-file=env.properties  up -d --no-deps && docker logs -f webapp