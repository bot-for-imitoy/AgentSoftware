FROM ubuntu:24.04

# 阿里云 apt 镜像（先用 http 装 ca-certificates，之后切回 https）
RUN sed -i 's|^URIs:.*|URIs: http://mirrors.aliyun.com/ubuntu/|' /etc/apt/sources.list.d/ubuntu.sources

RUN apt update && apt install -y ca-certificates

RUN sed -i 's|^URIs:.*|URIs: https://mirrors.aliyun.com/ubuntu/|' /etc/apt/sources.list.d/ubuntu.sources

RUN apt update

RUN DEBIAN_FRONTEND=noninteractive apt install -y sudo git nodejs npm python3 python3-pip curl xz-utils libatomic1

# ── Node.js ────────────────────────────────────────────────────────────────
# Ubuntu 24.04 自带的 Node 是 18.x，而新版 @playwright/mcp 依赖的 playwright
# 要求 Node >= 20 —— 用 n 升级到 Node 22 LTS（二进制从 npmmirror 下载，
# 可用 --build-arg N_MIRROR=... 覆盖为其他镜像源）。
ARG N_MIRROR=https://npmmirror.com/mirrors/node
ENV N_MIRROR=${N_MIRROR}
RUN npm install -g --no-fund --no-audit n \
    && n install 22

# ── MCP servers（全局预装；每个角色电脑的容器都从该镜像继承，开箱即用）────
# 1) 官方 filesystem MCP server（原有）—— 文件读写/搜索
# 2) 浏览器 MCP server：微软 @playwright/mcp —— 浏览器自动化（打开网页、点击、
#    填表、截图等）。chromium 及系统依赖随镜像一并装好；浏览器二进制统一放在
#    镜像内 /ms-playwright（PLAYWRIGHT_BROWSERS_PATH），所有角色用户共享，
#    无需各自联网下载。
#    角色电脑内启动示例：playwright-mcp --headless            # stdio 模式
#                        playwright-mcp --headless --port 8931  # SSE 模式
ARG PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright
ENV PLAYWRIGHT_DOWNLOAD_HOST=${PLAYWRIGHT_DOWNLOAD_HOST} \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN npm install -g --no-fund --no-audit \
        @modelcontextprotocol/server-filesystem \
        @playwright/mcp \
    && PW_VER="$(npm view @playwright/mcp@latest dependencies.playwright | tail -n 1)" \
    && test -n "${PW_VER}" \
    && npm install -g --no-fund --no-audit "playwright@${PW_VER}" \
    && playwright install --with-deps chromium

RUN apt-get clean && rm -rf /var/lib/apt/lists/*
