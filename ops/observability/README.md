# AgentDemo 可观测性部署指南

> **本文档目标**: 让任何一台 Windows 开发机在 30 分钟内跑起 Prometheus + Grafana,
> 并能从 `http://localhost:3000` 看到 AgentDemo 的指标。
>
> **架构选型说明**: 见本文档末尾"为什么选 Prometheus + Grafana"。

---

## 一、整体架构

```
┌─────────────────────────┐
│ AgentDemo 主应用 :9999  │
│ ├─ /actuator/prometheus │◄────── 拉取 (每 15 秒)
│ └─ (业务接口正常工作)    │            │
└─────────────────────────┘            │
                                       │
                            ┌──────────┴──────────┐
                            │ Prometheus :9090    │ ◄── 浏览器访问可用 PromQL 调试
                            │ (本机二进制运行)      │
                            └──────────┬──────────┘
                                       │
                                  数据源 (proxy)
                                       │
                            ┌──────────┴──────────┐
                            │ Grafana :3000       │ ◄── 浏览器访问大屏
                            │ (本机二进制运行)      │
                            └─────────────────────┘
```

三个组件都跑在**本机** localhost, 互联走 localhost 端口, 不依赖网络。

---

## 二、下载

### 2.1 Prometheus

- 下载地址: https://prometheus.io/download/
- 选择: **prometheus-3.x.x.windows-amd64.zip** (页面里搜索 "windows-amd64")
- 注意: 64 位 Windows 几乎必然选 amd64. 如果不确定, 命令行执行 `echo %PROCESSOR_ARCHITECTURE%`, 输出 `AMD64` 就选 amd64.
- 文件大小约 100MB

### 2.2 Grafana

- 下载地址: https://grafana.com/grafana/download?platform=windows&edition=oss
- 选择: **OSS 版** (开源, 免费, 演示项目用就够), **Standalone Windows Binary (.zip)** 而不是 Installer (`.msi`)
- 区别: zip 解压即跑, 不写注册表不装服务; msi 走系统安装, 会装成 Windows 服务. 我们要 zip 那个.
- 文件大小约 200MB

### 2.3 解压位置

建议两个解压到同一个目录方便管理, 例如:

```
D:\tools\
├── prometheus-3.x.x.windows-amd64\
│   ├── prometheus.exe
│   ├── promtool.exe
│   └── ...
└── grafana-v11.x.x\
    ├── bin\
    │   └── grafana-server.exe
    ├── conf\
    │   └── ...
    ├── data\
    └── public\
```

**重要**: 解压位置避开有空格、中文、`Program Files` 这种系统目录, 否则启动可能各种奇怪报错。`D:\tools\` 或 `C:\dev\tools\` 都行。

---

## 三、配置文件准备

本项目仓库里有两份配置物料, 不要复制粘贴, 直接用路径引用:

```
agent-demo/
└── ops/
    └── observability/
        ├── README.md                                     ← 你正在看的文件
        ├── prometheus.yml                                ← Prometheus 主配置
        └── grafana-provisioning/
            └── datasources/
                └── prometheus.yml                        ← Grafana 数据源
```

### 3.1 Prometheus 不用动

仓库里的 `ops/observability/prometheus.yml` 已写好, 启动命令直接指向这个文件路径即可。

### 3.2 Grafana 需要把数据源配置软链过去

Grafana 启动时只读它**安装目录下的** `conf/provisioning/datasources/`, 不能像 Prometheus 一样用命令行参数指定外部位置。三种处理方式:

**方式 A (推荐, 配置跟代码同步)**: Windows 软链接

```cmd
:: 以管理员身份开 cmd, 在 Grafana 安装目录执行
cd /d D:\tools\grafana-v11.x.x\conf\provisioning\datasources
:: 先备份原有空 provisioning 目录里的样例文件
ren sample.yaml sample.yaml.bak
:: 软链到项目仓库
mklink prometheus.yml D:\workspace\agent-demo\ops\observability\grafana-provisioning\datasources\prometheus.yml
```

之后 git pull 拿到的配置更新会自动生效, 不用手动复制。

**方式 B (无管理员权限时)**: 直接复制文件

```cmd
copy D:\workspace\agent-demo\ops\observability\grafana-provisioning\datasources\prometheus.yml ^
     D:\tools\grafana-v11.x.x\conf\provisioning\datasources\prometheus.yml
```

缺点: 配置改了要重新复制。

**方式 C**: 启动时改 Grafana 的 paths.provisioning 配置项, 见下文 4.2 节末尾备注, 一般不需要。

---

## 四、启动

启动顺序: **AgentDemo → Prometheus → Grafana**。AgentDemo 不需要先于 Prometheus 启动 (Prometheus 抓不到目标只是 Targets 页面显示 DOWN, 后启动 AgentDemo 也会被自动发现), 但通常按这个顺序最直观。

### 4.1 启动 Prometheus

在 cmd 里执行:

```cmd
cd /d D:\tools\prometheus-3.x.x.windows-amd64

prometheus.exe ^
    --config.file=D:\workspace\agent-demo\ops\observability\prometheus.yml ^
    --storage.tsdb.path=data ^
    --storage.tsdb.retention.time=7d ^
    --web.listen-address=:9090
```

参数说明:
- `--config.file`: 指向仓库里的配置, 改完不用复制
- `--storage.tsdb.path=data`: 数据存到当前目录的 data 子目录
- `--storage.tsdb.retention.time=7d`: 数据保留 7 天 (默认 15 天, 节约磁盘)
- `--web.listen-address=:9090`: 默认端口 9090

成功标志: 控制台最后一行类似:
```
ts=2026-05-21T... msg="Server is ready to receive web requests."
```

浏览器访问 http://localhost:9090, 顶部菜单 **Status → Targets**, 应该能看到两个抓取目标:

| Endpoint                              | State |
|---------------------------------------|-------|
| http://localhost:9999/actuator/prometheus | DOWN (B1 完成前是预期的) |
| http://localhost:9090/metrics         | UP    |

**State=DOWN 不是错误**, AgentDemo 还没加 actuator 端点, B1 完成后会变 UP。

### 4.2 启动 Grafana

新开一个 cmd 窗口 (不要关 Prometheus 那个):

```cmd
cd /d D:\tools\grafana-v11.x.x\bin

grafana-server.exe server
```

无参数即可, 全部走默认。如果想改端口/数据存储位置, 编辑 `..\conf\defaults.ini`, 但 demo 不需要。

成功标志: 控制台出现:
```
logger=http.server msg="HTTP Server Listen" address=[::]:3000
```

浏览器访问 http://localhost:3000:
- 默认账号密码: **admin / admin**
- 首次登录会要求改密码, demo 可以选 Skip
- 左侧菜单 **Connections → Data Sources**, 应该已经有 **Prometheus** (绿色 ✓), 这就是 provisioning 自动挂载的效果

如果数据源未自动出现, 见下方"故障排查"。

> **方式 C 详细说明** (3.2 节提到的): 如果你既没管理员权限做软链, 又不想每次手动复制,
> 可以让 Grafana 直接从仓库读 provisioning. 在 Grafana 安装目录的 `conf/custom.ini`
> (没有的话从 `conf/sample.ini` 复制一份), 加上:
> ```ini
> [paths]
> provisioning = D:/workspace/agent-demo/ops/observability/grafana-provisioning
> ```
> 然后启动 Grafana 时加 `--config conf/custom.ini`。注意 Grafana **不允许 provisioning 目录在项目内**这个说法是网传谣言, 实测可以。

### 4.3 启动 AgentDemo

按你平常的方式启动 (IDEA 或 jar)。**注意**: B0 阶段 AgentDemo 还没加 actuator 依赖,
所以 `/actuator/prometheus` 端点返回 404, Prometheus Targets 显示 DOWN, 这是预期的。
B1 完成后回过头来刷新就会变 UP。

---

## 五、验收

### B0 验收 (当前阶段)

- [ ] `http://localhost:9090` 能打开, Prometheus 主页正常
- [ ] `http://localhost:9090/targets` 看到 2 个 target (prometheus 自身 UP, agent-demo DOWN)
- [ ] `http://localhost:3000` 能打开, Grafana 登录界面
- [ ] Grafana 登录后 **Connections → Data Sources** 看到 Prometheus 数据源
- [ ] Grafana 点击 Prometheus 数据源 → 滚到底点 **Save & test** → 显示绿色 "Successfully queried the Prometheus API"

### B1 验收 (下一批完成后)

- [ ] `curl http://localhost:9999/actuator/prometheus` 返回大段文本指标 (jvm_*/system_*/http_*)
- [ ] Prometheus Targets 页面 agent-demo 变 UP
- [ ] Grafana 顶部 Explore 选 Prometheus 数据源, 查询 `up{job="agent-demo"}` 返回 1

---

## 六、故障排查

### Prometheus 启动报错 `bind: address already in use`
端口 9090 被占。要么改 `--web.listen-address=:9091`, 要么用 `netstat -ano | findstr :9090`
找占用进程号, 再 `taskkill /PID xxx /F` 杀掉。

### Grafana 启动报错 `failed to load configuration`
通常是 `conf/provisioning/datasources/prometheus.yml` YAML 缩进错了 (有人编辑时把 Tab 混进去了)。
我们仓库里的文件是 UTF-8 + LF + 空格缩进, 直接用即可, 不要在 Windows 记事本里编辑后另存为
(记事本默认 UTF-8 带 BOM, Grafana 会读失败)。要编辑用 VSCode 或 IDEA 的文件编辑器。

### Grafana 看不到 Prometheus 数据源
按顺序排查:
1. provisioning 文件位置对不对? 看 Grafana 启动日志, 应该有一行
   `provisioning datasources name=Prometheus type=prometheus`. 没有就是没读到。
2. YAML 文件语法对不对? Grafana 启动日志末尾如果有 ERROR 关键字, 直接搜原因。
3. 软链接做错? cmd 执行 `dir D:\tools\grafana-v11.x.x\conf\provisioning\datasources`,
   软链接条目应该是 `<SYMLINK>` 标识。

### Grafana 数据源能加但查不到数据
Grafana 数据源详情页点 **Save & test** 如果显示红字 `HTTP Error Bad Gateway` 或
`error reading Prometheus`, 多半是 Prometheus 还没启动或地址写错了。检查
`http://localhost:9090` 浏览器能不能打开。

### AgentDemo 启动后 Prometheus Targets 还是 DOWN
- B1 没做完: 没加 actuator 依赖, `/actuator/prometheus` 404
- B1 做完了但 actuator 端点没开放: 检查 application.yml 里
  `management.endpoints.web.exposure.include` 是否包含 `prometheus`
- 端口冲突: AgentDemo 不是跑在 9999? 改 prometheus.yml 的 target 端口

---

## 七、为什么选 Prometheus + Grafana

简短版给面试可讲的:

1. **Pull 模式** = 服务方暴露 `/metrics` 端点, Prometheus 主动拉. 优点是被监控方零依赖,
   没拉到就是没拉到, 不会因为推送失败影响业务. push 模式 (Pushgateway/StatsD) 偶尔在
   batch job 场景必要, 演示项目走 pull 是教科书选型.
2. **Time Series 数据模型** = `metric_name{label=value}` + 时间戳 + 浮点值. 简单但能表达
   所有可观测性需求 (counter / gauge / histogram / summary). 高基数 label 是大忌, 见 B2
   埋点设计。
3. **PromQL** = 函数式时序查询语言. 一行表达 P95、增长率、比率、百分位. 直接拿
   `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))` 这种就能在面试时
   解释。
4. **Grafana 不绑定 Prometheus** = Grafana 支持几十种数据源, 这里用 Prometheus 但项目里
   也能并存 MySQL 数据源 (B4 的 KPI 大屏走 MySQL 直查).
5. **二进制部署** = 单文件零依赖, 在受限的公司开发机上跑得起来, 而 Docker Desktop 一般是
   被 IT 部门禁的目标. 这是工程上的"够用"判断, 不是"最佳"。

---

## 八、关停

- 关 AgentDemo: 跟你平常一样 (IDEA 停止/`Ctrl+C`)
- 关 Prometheus: 在 cmd 窗口 `Ctrl+C`
- 关 Grafana: 在 cmd 窗口 `Ctrl+C`

不要直接关 cmd 窗口 (×), 那等于 `kill -9`, Prometheus 可能丢最近 15 秒的数据
(Grafana 没有此问题)。

---

## 附录: 演示日工作流

面试演示当天:
1. 启动 Prometheus 和 Grafana (各一个 cmd, 不需要关)
2. 启动 AgentDemo
3. 跑几轮对话 (覆盖 chitchat / knowledge / ticket / admin 四类意图各 1 次)
4. Grafana 大屏 (B5 完成后) / 任意 Dashboard (B3 完成后) 直接看
5. 想看原始 PromQL 调试: `http://localhost:9090` → 顶部 Graph

每次都跑这个流程不到 5 分钟。
