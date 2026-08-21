# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Stage 1: Build Environment following official Doris methodology
FROM apache/doris:build-env-ldb-toolchain-latest AS builder

# Copy the entire Doris source code
COPY . .

# Build MS
RUN DISABLE_BUILD_AZURE=ON DISABLE_BUILD_UI=ON DISABLE_BUILD_HIVE_UDF=ON \
    sh build.sh --cloud --clean --exclude-obs-dependencies --exclude-cos-dependencies

# Stage 2: Runtime Environment following docker/runtime/ms/Dockerfile
FROM apache/doris:base-6.0

# Apply base image security updates.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Use JDK 17
RUN ln -s /usr/lib/jvm/jdk-17 /usr/lib/jvm/java && rm -rf /usr/lib/jvm/jdk-8

# Set environment variables
ENV JAVA_HOME=/usr/lib/jvm/java
ENV PATH=$PATH:$JAVA_HOME/bin:/opt/apache-doris/ms/bin

# Copy built MS directory from builder stage
COPY --from=builder /root/output/ms /opt/apache-doris/ms

# Copy MS runtime resources
COPY docker/runtime/ms/resource/ms_*.sh /opt/apache-doris/

WORKDIR /opt/apache-doris

ENTRYPOINT ["bash","ms_disaggregated_entrypoint.sh"]
