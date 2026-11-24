# 把本地手册接成可搜索的资料库

**结果**　导入两份 Markdown，定位一段更换滤芯的操作，再修改源文件，验证新内容和版本可见。以下命令在项目或演示包根目录的 PowerShell 中运行，三个服务按[安装与启动](../setup.md)就绪。

## 导入，打开第一段原文

```powershell
.\scripts\import-demo.ps1 -SourceId manuals
(Get-Content -Raw -Encoding UTF8 .local\credentials.json | ConvertFrom-Json).queryToken | Set-Clipboard
```

1. 打开 [本机演示页](http://127.0.0.1:18080/demo)，把剪贴板贴入「查询令牌」。在「来源」填 `manuals`，「需求或关键词」填 **更换滤芯**，点「检索」。
2. 找到设备 A 的手册，点「查看当前源对象与位置」。记下版本号，找到“将备件标签上的演示批号记入工单”这句。卡片显示片段，来源窗口可以继续读同一版本的上下文。

## 更改手册，检查新版本

初始化已把原创手册复制到本部署的工作目录。下面保存原字节后，给工作副本追加一行。

```powershell
$guide = (Get-ChildItem -LiteralPath .local\demo-input\manuals -Filter '*A*.md').FullName
$original = [IO.File]::ReadAllBytes($guide)
[IO.File]::AppendAllText($guide, "`r`n交接补充：交班时核对青色演示标记。`r`n", [Text.UTF8Encoding]::new($false))
.\scripts\import-demo.ps1 -SourceId manuals
```

在同一页面检索 **青色演示标记**，打开这份手册，检查版本增加，并读到追加的句子。旧卡与源版本发生变化时，页面提示重新检索。

## 换成自己的资料

在这次试用目录放入一份允许普通维护人员读取的 UTF-8 Markdown，再对 `manuals` 执行同一导入命令。正式配置写在私有的 `.local/directory-sources.json`，每条来源指定稳定的 `sourceId`、批准目录、解析身份和访问组。需要不同可见范围时配置独立来源。其他人要接入自己的文档，只需替换资料目录并按实际授权设置这些值。

导入报错时，在「管理员运行状态」输入本机管理员令牌，点击「读取已配置目录」，选中来源后查看导入进度及逐项错误。检查搜索前可刷新运行状态，分别看关键词与向量索引。索引仍在构建时，导入结束与语义检索就绪会有时间差。

在上面同一 PowerShell 会话中恢复手册。实验中加入的自有资料可移出批准目录后重新扫描，是否同步删除由来源的 `deleteMissing` 决定。

```powershell
[IO.File]::WriteAllBytes($guide, $original)
.\scripts\import-demo.ps1 -SourceId manuals
```
