#!/bin/bash
export JAVA_HOME=/home/home/jdk25
export PATH=/home/home/jdk25/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
cd /mnt/c/Users/home/IdeaProjects/cash-control/cash-control-api
./gradlew test 2>&1
