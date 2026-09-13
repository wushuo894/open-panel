# Open Panel

[![Build](https://github.com/wushuo894/open-panel/actions/workflows/build.yml/badge.svg)](https://github.com/wushuo894/open-panel/actions/workflows/build.yml)
[![License: GPL-2.0](https://img.shields.io/badge/License-GPL--2.0-blue.svg)](LICENSE)

Open Panel 是一个面向家庭服务器和自托管服务的导航面板。它把常用网站、内外网服务地址、主机指标和 Docker 容器状态放在同一个响应式页面中，并提供带权限控制的可视化设置。

## 功能

- 大封面与列表两种页面模式，均可展示内置 WebP 壁纸，并支持背景上传、多壁纸轮换和可调遮罩
- 可配置 Banner 时间、秒数、日期、周几、标题、一句话及最多四行页脚
- 卡片分组支持仅图标、图标 + 标题 + 备注两种布局
- 分组与所属卡片在同一区域编辑，支持分别上移、下移调整首页顺序
- 自定义链接支持内网/公网地址切换、新窗口/当前窗口打开、自定义图标上传，以及从网页自动补全标题、简介和图标
- 系统卡片展示 CPU、内存和网络信息；存储卡片可按文件夹路径显示所在分区的已用容量和总容量
- 服务卡片支持 Emby、ani-rss、qBittorrent、OpenList 的 API Key 专属状态采集，并保留通用 Web 探活
- Docker 卡片展示指定容器的运行状态和运行时间；已登录管理员可启动、停止或重启容器
- 设置页在 Docker 可用时显示容器管理页，可查看运行时间、启停或重启容器、查看对应的 `docker-compose.yaml`，并清理未被容器引用的镜像；进入后自动检测镜像更新，支持一键重建、实时进度与更新日志，以及在安全阶段中断任务
- 管理员可在首页直接新增、编辑、删除和拖拽排序卡片，设置页专注于卡片分组管理
- 卡片、分组和搜索引擎的 MDI 图标支持从完整列表中搜索选择，也可手动输入
- 系统信息、软件服务和 Docker 状态使用独立接口并行加载，单个慢服务不会阻塞其他卡片
- 管理员可扫描指定 IP/域名的 80-65535 端口，默认使用当前页面的 IP 或域名；仅保留 HTTP 200 的 HTML 服务，识别标题、图标和介绍后批量添加，并自动跳过 Open Panel 自身
- Google、Bing、GitHub 等可编辑搜索引擎
- 用户名密码登录、JWT 会话及浏览器密码管理器兼容
- 管理员可在安全设置中验证当前密码后修改用户名，并使旧令牌失效
- 可选免登录只读访问；匿名用户无法进入设置
- 单会话、公网访问限制、登录 IP 绑定、尝试次数、CORS、IP 白名单、可信反代 IP 和登录有效期
- JSON 配置导入导出、PWA 安装与公开页面离线缓存
- GitHub Releases 自更新，可配置 GitHub Token 避免匿名 API 请求受 IP 频率限制；容器部署提示拉取新镜像，不在容器内替换程序

首次启动按“系统信息、Docker、常用网站”创建默认分组：系统信息包含 CPU、内存和网络卡片；检测到 Docker Socket 或 `DOCKER_HOST` 时创建 Docker 分组；常用网站包含知乎、百度贴吧、QQ 邮箱、GitHub、哔哩哔哩、YouTube、ChatGPT 和 Cloudflare。
大封面默认不放置卡片分组，向下滚动后展示完整导航；管理员可以主动选择需要显示在封面的分组。

软件服务填写访问地址后即可作为导航打开；填写 API Key 后，进入首页时会并行采集一次专属摘要：Emby 显示播放与转码，ani-rss 显示订阅和下载任务，qBittorrent 显示版本、下载数和做种数，OpenList 显示存储总容量和存储数量。API Key 只由后端请求服务 API，不会随公开面板配置返回。

## 快速开始

### Docker Compose

本地构建会直接复制宿主机已经编译好的 JAR，不会在 Docker 中运行 Maven、Node 或 pnpm：

```bash
mvn -DskipTests package
docker compose up -d --build
```

访问 `http://localhost:7788`。首次点击右上角登录按钮时会进入管理员初始化页面。

运行镜像基于 `wushuo894/eclipse-temurin:26-jre-alpine`。配置保存在 `./config/open-panel.json`。如果需要 Docker 容器状态，请按 `docker-compose.yml` 中的注释挂载 `/var/run/docker.sock`，并设置宿主机 Docker 组的 `DOCKER_GID`。

### 运行 JAR

环境要求：JDK 25、Maven 3.9，以及构建过程中可访问 npm 软件源。

```bash
mvn -DskipTests package
java -jar open-panel-application/target/open-panel.jar
```

默认监听 `0.0.0.0:7788`，配置目录为当前路径下的 `config`。可通过环境变量修改：

```bash
OPEN_PANEL_CONFIG_DIR=/path/to/config java -jar open-panel.jar
```

### 前端开发

先启动后端，再运行：

```bash
cd open-panel-ui
pnpm install
pnpm dev
```

开发服务器地址为 `http://localhost:37788`，`/api` 会代理至 `http://127.0.0.1:7788`。

## 安全说明

- 首次初始化密码至少 8 个字符，密码仅以 BCrypt 哈希保存。
- JWT 保存在浏览器 `localStorage`，关闭标签页、浏览器或重启服务后继续有效；登录有效时间设为 `0` 时 Token 永久有效。主动退出、到期或服务端撤销后会清除本地 Token。浏览器可通过标准登录表单自行记住密码，Open Panel 不保存明文密码。
- IP 白名单限制登录与管理接口，不代表免认证，也不会绕过管理员权限。
- 只有请求来源属于“信任的反代 IP”时，服务才读取 `X-Forwarded-For`。
- 配置导出包含服务凭据和登录密钥，应按敏感文件保管。
- Docker Socket 拥有较高宿主机权限，并允许管理员通过面板控制容器，只应挂载到可信 Open Panel 实例。
- 端口扫描功能仅供扫描自己拥有或已获授权的主机，扫描接口只对已登录管理员开放。

## 更新

设置页可检查 [GitHub Releases](https://github.com/wushuo894/open-panel/releases)，并可选填 GitHub Token 用于更新检查。Token 会持久化保存，但管理接口只返回脱敏值。非容器、非 Windows 的 JAR 部署可在校验发行文件 SHA-256 后自动替换并重启。Docker 部署请拉取新镜像：

```bash
docker compose pull
docker compose up -d
```

## 技术栈

- Java 25、Spring Boot、Gson
- Vue 3、Vite、pnpm、Vuetify、PWA
- OSHI、docker-java

## 开源协议

本项目采用 [GNU General Public License v2.0](LICENSE) 开源。

项目地址：[github.com/wushuo894/open-panel](https://github.com/wushuo894/open-panel)
