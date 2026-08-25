# ── 员工电脑基础镜像 (电脑默认容器镜像) ────────────────────────────
# 定义所有角色电脑 (podman 容器) 共用的系统层:
#   1) 镜像源 → 阿里云 (deb822 格式)
#   2) 员工电脑标配包 (sudo/git/node/python + hermes 安装依赖)
#   3) Hermes Agent (装到 /usr/local/bin 全局, 员工用户可用)
#   4) MCP filesystem 服务器 (容器内全局 npm, 角色容器免装)
# 任何一步失败 → 整体失败 (镜像必须完整).
# 员工用户 (拼音 + uid) 不进镜像 — 每角色不同, 容器创建时各自添加
# (见 src/core/computer.py PodmanComputer._ensure_container).
#
# 构建 (电脑初始化时 ensure_base_image() 自动执行, 也可手动):
#   podman build -t maf-base:latest .
FROM ubuntu:24.04

# 1) 镜像源 → 阿里云 (deb822 格式, 幂等)
RUN sed -i 's|^URIs:.*|URIs: http://mirrors.aliyun.com/ubuntu/|' \
        /etc/apt/sources.list.d/ubuntu.sources

# 2) 员工电脑标配包 (sudo/git/node/python + hermes 安装依赖)
RUN apt-get update -qq \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq -o DPkg::Lock::Timeout=600 \
        sudo git nodejs npm python3 python3-pip curl xz-utils libatomic1

# 3) Hermes Agent (装到 /usr/local/bin 全局, 员工用户可用).
#    install.sh 用 git clone 从 github.com 拉源码 — 该域名 TLS 常被重置
#    (gnutls_handshake failed), 实测 ghfast.top 镜像可用: 用 git 全局
#    url.insteadOf 重写, install.sh 内部 git clone 自动走镜像.
#    node 26 从 nodejs.org 下载也可能卡顿: curl/install.sh 均加超时,
#    重试 2 次 — 避免镜像构建被拖到超时.
RUN git config --global url."https://ghfast.top/https://github.com/".insteadOf "https://github.com/" \
 && { command -v hermes >/dev/null 2>&1 || { \
        curl -fsSL --connect-timeout 20 --max-time 240 \
          https://hermes-agent.nousresearch.com/install.sh \
          -o /tmp/hermes-install.sh 2>/dev/null \
        && for i in 1 2; do \
             timeout 600 bash /tmp/hermes-install.sh >/dev/null 2>&1 && break || sleep 3; \
           done; \
      }; } \
 && command -v hermes >/dev/null 2>&1 \
    || { echo "Hermes 安装失败" >&2; exit 1; }

# 4) MCP filesystem 服务器 (容器内全局 npm, 角色容器免装, 启动即用)
RUN npm ls -g --depth=0 2>/dev/null | grep -q 'server-filesystem' \
 || npm install -g --no-fund --no-audit @modelcontextprotocol/server-filesystem

# 5) 清理 apt 缓存, 缩小镜像体积
RUN apt-get clean && rm -rf /var/lib/apt/lists/*
