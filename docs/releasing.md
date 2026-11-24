# 发布与第三方依赖

在干净副本使用 Java 17 和 Maven 执行 clean verify，核对 92 项 Java 测试与 Python 队列回归。完成桌面演示、接口与版本回读验收后，运行以下命令创建源码 ZIP、Windows 核心 ZIP 和 SHA256SUMS。

```powershell
python tools/package.py --output dist
```

程序从当前提交导出仓库文件，核对运行 JAR 内的项目版本。核心包附加 lib/engine.jar，模型、运行时、MySQL 驱动与运行数据由各部署自行配置。依赖更新时要根据实际 Maven Shade 打包清单重新核对第三方告知、坐标、来源 URL 与原 JAR 哈希。

发布到 GitHub 时上传两个 ZIP 和校验和，Release 对应同一源码标签。启用 Security 中的私下漏洞报告、Issues 和默认分支检查。最后从未登录浏览器核对 README、媒体、许可、下载和示例。
