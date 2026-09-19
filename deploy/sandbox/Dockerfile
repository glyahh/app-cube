# 运行时沙箱镜像：承载用户生成项目的常驻服务。
#
# 与生成沙箱共用 ubuntu:24.04 基底（docker_sandbox.py 校验镜像一致性时按名字比对，
# 但运行时容器是独立生命周期，不受 _validate_container_config 约束）。
# 预装 Node.js LTS / Python 3 / OpenJDK 21 / SQLite，覆盖首期支持的运行环境。
#
# 构建与推送见同目录 build.ps1；镜像名由 AGENT_SANDBOX_RUNTIME_IMAGE 引用。

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# tzdata 先装好，避免后续包交互式询问时区
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
        tzdata \
    && rm -rf /var/lib/apt/lists/*

# ---------- Node.js 22 LTS（NodeSource 官方源） ----------
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# ---------- Python 3 + pip + venv ----------
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*

# ---------- OpenJDK 21（Ubuntu 官方源）+ Maven ----------
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        maven \
    && rm -rf /var/lib/apt/lists/*

# ---------- SQLite ----------
RUN apt-get update \
    && apt-get install -y --no-install-recommends sqlite3 \
    && rm -rf /var/lib/apt/lists/*

# 非特权运行用户：项目进程不以 root 跑。
# ubuntu:24.04 自带 UID 1000 的 ubuntu 用户，runner 从 1001 起。
RUN useradd -m -u 1001 -s /bin/bash runner

# 统一工作目录约定：运行时容器同样把工作区挂到 /workspace
RUN mkdir -p /workspace && chown runner:runner /workspace
WORKDIR /workspace

USER runner

# 让 java/mvn 在非登录 shell 也可用
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 自检：任何一层失败构建即失败
RUN node --version && npm --version \
    && python3 --version && pip3 --version \
    && java -version 2>&1 | head -1 && javac -version && mvn -version | head -1 \
    && sqlite3 --version
