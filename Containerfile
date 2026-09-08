FROM ubuntu:26.04

# 阿里云 apt 镜像（先用 http 装 ca-certificates，之后切回 https）
RUN sed -i 's|^URIs:.*|URIs: http://mirrors.aliyun.com/ubuntu/|' /etc/apt/sources.list.d/ubuntu.sources

RUN apt update && apt install -y ca-certificates

RUN sed -i 's|^URIs:.*|URIs: https://mirrors.aliyun.com/ubuntu/|' /etc/apt/sources.list.d/ubuntu.sources

RUN apt update

RUN DEBIAN_FRONTEND=noninteractive apt install -y sudo git nodejs npm python3 python3-pip curl xz-utils libatomic1

RUN npm config set registry https://registry.npmmirror.com

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
