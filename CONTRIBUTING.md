# 参与开发

通过 Issue 提交使用问题，请写明版本、操作系统、最小输入、预期与实际结果，并剔除令牌和业务数据。涉及访问控制或凭据的问题按 [安全反馈](SECURITY.md) 私下报告。

源码构建使用 Java 17 与 Maven。运行 `scripts/build.ps1`，再使用 Python 3.11 执行 `python -m unittest discover -s model-service` 检查推理队列。修改 HTTP 行为时同步更新接口契约、示例和对应回归检查。提交的代码或文档须有权按项目许可分发。每个改动请说明使用场景、重现步骤和验证结果。
