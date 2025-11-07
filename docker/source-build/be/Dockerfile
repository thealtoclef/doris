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
FROM apache/doris:build-env-ldb-toolchain-latest as builder

# Copy the entire Doris source code
COPY . .

# Build BE
RUN sh build.sh --be DISABLE_BUILD_AZURE=ON

# Stage 2: Runtime Environment following docker/runtime/be/Dockerfile
FROM apache/doris:base-latest

# Use JDK 17 for BE
RUN ln -s /usr/lib/jvm/jdk-17 /usr/lib/jvm/java && rm -rf /usr/lib/jvm/jdk-8
ENV JAVA_HOME=/usr/lib/jvm/java
ENV PATH=$PATH:$JAVA_HOME/bin:/opt/apache-doris/be/bin

# Copy BE built binaries from builder stage
COPY --from=builder /root/output/be /opt/apache-doris/be

# Copy BE resource files from source repository
COPY docker/runtime/be/resource/be_*.sh /opt/apache-doris/

COPY docker/runtime/be/resource/entry_point.sh /usr/local/bin/

COPY docker/runtime/be/resource/init_be.sh /usr/local/bin/

ENTRYPOINT ["bash","entry_point.sh"]
