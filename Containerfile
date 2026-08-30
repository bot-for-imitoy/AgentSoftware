FROM ubuntu:24.04

RUN sed -i 's|^URIs:.*|URIs: http://mirrors.aliyun.com/ubuntu/|' /etc/apt/sources.list.d/ubuntu.sources

RUN apt update && apt install -y ca-certificates

RUN sed -i 's|^URIs:.*|URIs: https://mirrors.aliyun.com/ubuntu/|' /etc/apt/sources.list.d/ubuntu.sources

RUN apt update

RUN DEBIAN_FRONTEND=noninteractive apt install -y sudo git nodejs npm python3 python3-pip curl xz-utils libatomic1

RUN npm install -g --no-fund --no-audit @modelcontextprotocol/server-filesystem

RUN apt-get clean && rm -rf /var/lib/apt/lists/*
