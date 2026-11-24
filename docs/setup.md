# 安装与启动

支持 Windows x64、Java 17、CPython 3.11 x64、Elasticsearch 8.19.0 和固定版本的 BGE-M3。建议 16 GB 内存，预留模型、搜索引擎与依赖缓存空间。下载、初次向量索引和日常查询耗时分别计算。所有命令在解压目录或仓库根目录的 PowerShell 运行。

## 取得程序

- **使用 Windows 核心包**。将 ZIP 解压到新目录，确认存在 `lib/engine.jar`。用 `Get-FileHash -Algorithm SHA256 <下载的ZIP>` 与发行清单比对。
- **从源码构建**。安装 [JDK 17](https://adoptium.net/temurin/releases/?version=17) 和 [Maven](https://maven.apache.org/download.cgi)，设置 `JAVA_HOME` 并把 Maven 的 bin 加入 PATH，运行 `.\scripts\build.ps1`。测试通过后使用 `engine/target/engine.jar`。已有完整 Maven 缓存可加 `-Offline`。

## 准备依赖

在每次部署所用终端设置 `JAVA_HOME`。安装 [Python 3.11 x64](https://www.python.org/downloads/windows/)，包括 py 启动器与 venv。在联网机准备固定版本的 Elasticsearch、Python 环境和模型。

```powershell
.\scripts\prepare-es.ps1
.\scripts\prepare-model.ps1
.\scripts\init-demo.ps1
```

首次准备会访问 Elastic、PyTorch、Python 包索引与 Hugging Face。脚本校验 ES 官方 SHA-512 及固定模型文件 SHA-256。内网复用时复制准备好的 `runtime/cache` 到新的解压目录，在有同版 Python 和 JDK 的目标机运行两条准备命令并分别加 `-Offline`，再初始化。已安装依赖的开发者也可在对应终端设置 `ENGINE_ES_HOME`、`ENGINE_MODEL_PYTHON`、`MODEL_PATH` 指向独立 ES 分发、已装锁定依赖的 Python 和已校验的模型目录。数据目录由本次部署创建。

## 启动与第一次使用

打开三个 PowerShell 窗口，分别在同一根目录依次运行以下命令，每个服务保持前台运行。

```powershell
.\scripts\start-es.ps1
```
```powershell
.\scripts\start-model.ps1
```
```powershell
.\scripts\start.ps1
```

在第四个窗口导入示例并复制普通维护人员的查询令牌。

```powershell
.\scripts\import-demo.ps1
(Get-Content -Raw -Encoding utf8 .local\credentials.json | ConvertFrom-Json).queryToken | Set-Clipboard
```

看到 `Ready` 后打开 [本机演示页](http://127.0.0.1:18080/demo)。将令牌粘贴到查询身份，输入“为设备 A 查找更换滤芯的步骤及对应工单”，点击“个性化推荐”。点“查看当前源对象与位置”核对原文。另两个身份为主管查询令牌 managerToken、管理令牌 adminKey。用完清空剪贴板和页面身份。管理身份只用于可信后端或管理员。

## 故障定位、停止和回退

| 现象 | 检查 |
|---|---|
| 端口占用 | 首次初始化可指定 -EnginePort、-ModelPort、-EsPort、-TransportPort（默认 18080、18081、19200、19300）；引擎允许的 ES HTTP 端口为 19200–19299。修改后相应替换演示 URL。 |
| 搜索结果为空 | 在“管理员运行状态”检查导入、关键词和向量就绪数量，核对来源、身份和当前查询过滤条件。 |
| 模型加载失败 | 核对 Python 3.11 x64、CPU Torch、锁定依赖及模型清单；重新打开启动窗口，检查模型日志。 |
| 提示无权或找不到来源 | 确认使用当前部署的查询令牌及组，并重新查询当前版本。权限收紧或原文删除后旧链接可能失效。 |

分别在引擎、模型、ES 的启动终端按 Ctrl+C 停止。备份时先停服务，在受控位置保存资料和本部署的 .local。升级时用新的独立目录，保留原程序及匹配的数据库副本。任务卡选段可在启动引擎的终端设置 `$env:ENGINE_TASK_CARD_STRATEGY='legacy'` 后重启，恢复当前策略时设为 `task-bm25`。完整操作见[功能导览](features.md)。
