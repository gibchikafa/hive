/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@Library("jenkins-library@main")

import com.logicalclocks.jenkins.k8s.ImageBuilder

pipeline {
  agent { label 'local' }

  options {
    disableConcurrentBuilds()
    skipDefaultCheckout(true)
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  parameters {
    booleanParam(name: 'BUILD_IMAGE_ONLY', defaultValue: false, description: 'Only build and push the Docker image using an existing Hive package.')
    string(name: 'BRANCH_TO_BUILD', defaultValue: 'hops-4.1.0', description: 'Git branch to build.')
    string(name: 'MAVEN_CMD', defaultValue: 'mvn', description: 'Maven executable to use.')
    string(name: 'MAVEN_ARGS', defaultValue: 'clean install deploy -Pdist -DskipTests -Denforcer.skip=true', description: 'Maven goals and arguments.')
    booleanParam(name: 'FORCE_UPDATE', defaultValue: true, description: 'Pass -U to Maven to refresh snapshot and cached dependency resolution.')
  }

  environment {
    DOCKER_IMAGE = 'maven:3.9.9-eclipse-temurin-17'
    HIVE_PACKAGE_DIR = '/opt/repository/master/hive'
    HOST_MAVEN_REPO = '/home/jenkinsmaster/.m2'
    MAVEN_LOCAL_REPO = '/maven-repo/repository'
    MAVEN_OPTS = '-Xmx4G'
    MAVEN_SETTINGS = "${WORKSPACE}@tmp/mvn-settings.xml"
  }

  stages {
    stage('Checkout') {
      steps {
        deleteDir()
        checkout([$class: 'GitSCM',
          branches: [[name: "${params.BRANCH_TO_BUILD}"]],
          userRemoteConfigs: [[
            url: 'git@github.com:gibchikafa/hive.git',
            credentialsId: 'id_rsa'
          ]]
        ])
      }
    }

    stage('Prepare Maven') {
      when {
        expression { !params.BUILD_IMAGE_ONLY }
      }
      steps {
        withCredentials([usernamePassword(credentialsId: 'a0770738-4ef3-4acc-a6ba-097ee6c85b44', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
          sh '''#!/bin/bash -eu
            rm -rf "$WORKSPACE/.m2" "$HOST_MAVEN_REPO/repository/io/hops/hive"
            mkdir -p "$(dirname "$MAVEN_SETTINGS")" "$HOST_MAVEN_REPO/repository"
            cat > "$MAVEN_SETTINGS" <<EOF
<settings>
  <localRepository>${MAVEN_LOCAL_REPO}</localRepository>
  <servers>
    <server>
      <id>HopsEE</id>
      <username>$USERNAME</username>
      <password>$PASSWORD</password>
    </server>
    <server>
      <id>Hops</id>
      <username>$USERNAME</username>
      <password>$PASSWORD</password>
    </server>
    <server>
      <id>HopsHive</id>
      <username>$USERNAME</username>
      <password>$PASSWORD</password>
    </server>
    <server>
      <id>hops-releases</id>
      <username>$USERNAME</username>
      <password>$PASSWORD</password>
    </server>
    <server>
      <id>hive-releases</id>
      <username>$USERNAME</username>
      <password>$PASSWORD</password>
    </server>
    <server>
      <id>Hive</id>
      <username>$USERNAME</username>
      <password>$PASSWORD</password>
    </server>
  </servers>
</settings>
EOF
          '''
        }
      }
    }

    stage('Resolve Version') {
      steps {
        sh '''#!/bin/bash -eu
          perl -0ne 'if (m{<artifactId>hive</artifactId>\\s*<version>([^<]+)</version>}) { print "$1"; exit }' pom.xml > version.log
          echo "POM_VERSION=$(cat version.log)"
        '''
      }
    }

    stage('Build and Deploy') {
      when {
        expression { !params.BUILD_IMAGE_ONLY }
      }
      steps {
        sh '''#!/bin/bash -eu
          UPDATE_ARG=""
          if [ "$FORCE_UPDATE" = "true" ]; then
            UPDATE_ARG="-U"
          fi

          docker run --rm \
            -u "$(id -u):$(id -g)" \
            -v "$WORKSPACE:$WORKSPACE" \
            -v "$(dirname "$MAVEN_SETTINGS"):$(dirname "$MAVEN_SETTINGS")" \
            -v "$HOST_MAVEN_REPO:/maven-repo" \
            -w "$WORKSPACE" \
            -e GITHUB_ACTIONS=true \
            -e HOME=/tmp \
            -e MAVEN_CONFIG=/tmp/maven-config \
            -e MAVEN_LOCAL_REPO="$MAVEN_LOCAL_REPO" \
            -e MAVEN_OPTS="$MAVEN_OPTS" \
            -e MAVEN_CMD="$MAVEN_CMD" \
            -e MAVEN_SETTINGS="$MAVEN_SETTINGS" \
            -e MAVEN_ARGS="$MAVEN_ARGS" \
            -e UPDATE_ARG="$UPDATE_ARG" \
            "$DOCKER_IMAGE" \
            bash -lc '
              set -eu
              export PATH="$JAVA_HOME/bin:$PATH"
              JAVA_VERSION="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F"= " "/java.specification.version =/{print \\$2; exit}")"
              if [ "$JAVA_VERSION" != "17" ]; then
                echo "Java 17 is required, but JAVA_HOME=$JAVA_HOME reports java.specification.version=$JAVA_VERSION" >&2
                exit 1
              fi
              test -x "$JAVA_HOME/bin/javadoc"
              "$MAVEN_CMD" -s "$MAVEN_SETTINGS" -Dmaven.repo.local="$MAVEN_LOCAL_REPO" $UPDATE_ARG $MAVEN_ARGS
            '
        '''
      }
    }

    stage('Copy Hive Package') {
      when {
        expression { !params.BUILD_IMAGE_ONLY }
      }
      steps {
        sh '''#!/bin/bash -eu
          HIVE_VERSION="$(tr -d '\\r\\n' < version.log)"
          mkdir -p "$HIVE_PACKAGE_DIR"

          docker run --rm \
            -u "$(id -u):$(id -g)" \
            -v "$WORKSPACE:$WORKSPACE" \
            -v "$(dirname "$MAVEN_SETTINGS"):$(dirname "$MAVEN_SETTINGS")" \
            -v "$HOST_MAVEN_REPO:/maven-repo" \
            -v "$HIVE_PACKAGE_DIR:$HIVE_PACKAGE_DIR" \
            -w "$WORKSPACE" \
            -e GITHUB_ACTIONS=true \
            -e HOME=/tmp \
            -e MAVEN_CONFIG=/tmp/maven-config \
            -e MAVEN_LOCAL_REPO="$MAVEN_LOCAL_REPO" \
            -e MAVEN_OPTS="$MAVEN_OPTS" \
            -e MAVEN_CMD="$MAVEN_CMD" \
            -e MAVEN_SETTINGS="$MAVEN_SETTINGS" \
            -e HIVE_PACKAGE_DIR="$HIVE_PACKAGE_DIR" \
            -e HIVE_VERSION="$HIVE_VERSION" \
            "$DOCKER_IMAGE" \
            bash -lc '
              set -eu
              "$MAVEN_CMD" -s "$MAVEN_SETTINGS" -Dmaven.repo.local="$MAVEN_LOCAL_REPO" dependency:copy \
                -Dartifact=io.hops.hive:hive-packaging:${HIVE_VERSION}:tar.gz:bin \
                -DoutputDirectory="$HIVE_PACKAGE_DIR" \
                -Dmdep.stripVersion=true \
                -U
              mv "$HIVE_PACKAGE_DIR/hive-packaging-bin.tar.gz" "$HIVE_PACKAGE_DIR/hive-packaging-${HIVE_VERSION}-bin.tar.gz"
            '

          ls -l "$HIVE_PACKAGE_DIR/hive-packaging-${HIVE_VERSION}-bin.tar.gz"
        '''
      }
    }

    stage('Build and Push Images') {
      steps {
        script {
          def version = readFile("${env.WORKSPACE}/version.log").trim()
          def imageBuildVersion = readFile("${env.WORKSPACE}/dockerfiles/image-build-version.properties")
              .readLines()
              .find { line -> line.trim() && !line.trim().startsWith('#') && line.contains('IMAGE_BUILD_VERSION=') }
              ?.split('=', 2)[1]
              ?.trim()

          if (!imageBuildVersion) {
            error('IMAGE_BUILD_VERSION is missing from dockerfiles/image-build-version.properties')
          }

          withCredentials([usernamePassword(credentialsId: 'a0770738-4ef3-4acc-a6ba-097ee6c85b44', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            withEnv(["HIVE_VERSION=${version}", "IMAGE_BUILD_VERSION=${imageBuildVersion}"]) {
              def builder = new ImageBuilder(this)

              sh '''#!/bin/bash -eu
                test -f "$HIVE_PACKAGE_DIR/hive-packaging-${HIVE_VERSION}-bin.tar.gz"
                cp "$HIVE_PACKAGE_DIR/hive-packaging-${HIVE_VERSION}-bin.tar.gz" "$WORKSPACE/dockerfiles/hive-packaging-${HIVE_VERSION}-bin.tar.gz"
                printf "user=%s\npassword=%s" "$USERNAME" "$PASSWORD" > "$WORKSPACE/dockerfiles/wgetrc"
                ls -l "$WORKSPACE/dockerfiles"
              '''

              def manifest = readFile("${env.WORKSPACE}/dockerfiles/build-manifest.json")
              builder.run(manifest)
            }
          }
        }
      }
    }
  }

  post {
    always {
      sh '''#!/bin/bash
        rm -f "$MAVEN_SETTINGS"
      '''
      archiveArtifacts artifacts: 'version.log', allowEmptyArchive: true
    }
  }
}
