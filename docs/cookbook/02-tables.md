# 用一份 CSV 接入工单系统

**结果**　保留工单号 `00017` 的前导零，同时支持字段精确查询和正文检索。准备好[本地部署](../setup.md)，在项目或演示包根目录执行命令。

## 看一行怎样变成可查询的工单

示例 [work-orders.csv](../../examples/maintenance/orders/work-orders.csv) 有两行。来源 `orders` 将 `workOrderId` 作为稳定主键，`title` 作为标题，`action` 作为可供检索的正文信息。映射中单独声明为 `string` 的工单号支持精确比较。设备 A 的工单号是 `00017`，设备编号是 `A-01`。

```powershell
$cfg = Get-Content -Raw -Encoding UTF8 .local\directory-sources.json | ConvertFrom-Json
($cfg.sources | Where-Object sourceId -eq 'orders').tableMapping | ConvertTo-Json -Depth 6
.\scripts\import-demo.ps1 -SourceId orders
```

如需在写入前检查另一张表，打开 [/demo](http://127.0.0.1:18080/demo)，在管理员区读入配置来源，选 `orders`，输入相对文件名 `work-orders.csv`，点「预览表格映射」。这里可看到前几行及错误数。管理员令牌来自本机私有配置的 `adminKey`。

## 验证前导零

以下请求只发送普通查询令牌。按原值查询应有一条，再用 `17` 查询应为零条。

```powershell
$cred = Get-Content -Raw -Encoding UTF8 .local\credentials.json | ConvertFrom-Json
$headers = @{ Authorization = 'Bearer ' + $cred.queryToken }
$endpoint = 'http://127.0.0.1:18080/api/engine/v1/search'
foreach ($number in '00017','17') {
    $body = @{
        schemaVersion = '1.1'
        mode = 'keyword'
        query = ''
        pageSize = 10
        filters = @{ sources = @('orders'); typed = @(@{field='workOrderId'; type='string'; op='eq'; value=$number}) }
    } | ConvertTo-Json -Depth 8
    $reply = Invoke-RestMethod $endpoint -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    Write-Output "$number -> $(@($reply.items).Count)"
    $reply.items | ForEach-Object { $_.item.title }
}
```

页面上也可重放。将来源设为 `orders`，清空关键词，展开「精确字段条件」，填 `workOrderId`、字符串、等于、`00017`，点「检索」。打开该记录的来源，核对行号与行键。

## 换成自己的列名

在独立试用目录为第二个系统复制一条 `orders` 来源配置，换成唯一的 `sourceId` 和批准目录。把 `idColumn` 设为稳定的业务主键，`titleColumn` 设为用户能识别的名称。把每个返回字段的 `column` 改为你的表头。金额可映射为 `decimal`、日期为 `date`，原样保留的编号映射为 `string`。仅对允许进入检索正文的列启用 `semantic`。保存后用管理员界面选择新来源、预览、启动导入。

遇到重复主键或类型错误，先在预览中核对错误行并更正源表，再导入。出现 `UNKNOWN_TYPED_FIELD` 时检查字段名、来源、身份和导入完成状态。多次导入同一份示例时，行键稳定，不会为同一行新建多个工单。
