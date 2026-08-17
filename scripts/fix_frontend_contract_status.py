from pathlib import Path

path = Path("spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md")
text = path.read_text(encoding="utf-8")
marker = "## 25. 本工作包完成状态和下一步\n"
if text.count(marker) != 1:
    raise RuntimeError(f"expected one section 25 marker, found {text.count(marker)}")
prefix, _ = text.split(marker, 1)
section = '''## 25. 本工作包完成状态和下一步

| 工作项 | 状态 | 结果 |
| --- | --- | --- |
| A1 数据预检问题明细页面合同 | `CONFIRMED + IMPLEMENTED` | Route/Run 层级、问题汇总与明细、筛选、脱敏、原值查看、导出和到期状态已在前端 Mock 实现 |
| A2 无主键机构范围替换前端语义 | `CONFIRMED + IMPLEMENTED` | “每次全量 · 替换当前机构范围”的只读合同、运行确认、状态反馈和审计已实现 |
| A3 页面—操作—权限—审计矩阵 | `CONFIRMED + IMPLEMENTED` | 页面操作、`domain.action` 权限、C1/C2/S1 确认、审计、分页、详情和主要恢复路径已实现 |
| 前端代码实施 | `IMPLEMENTED` | 已通过 ESLint 与 Next.js 生产构建；当前仍使用 Mock 数据和前端状态 |
| REST API 合同 | `FROZEN_FOR_IMPLEMENTATION` | `FRONTEND_API_CONTRACT_V1.md` 已覆盖统一响应、分页、Revision、幂等、权限、审计、错误码、Export Job 和长任务状态 |
| Java 后端与服务端鉴权/审计 | `NOT_IMPLEMENTED` | 尚未依据 API 合同创建 Controller、DTO、Service、鉴权和服务端审计实现 |
| PostgreSQL / Doris / RabbitMQ 物理实现 | `IN_REVIEW` | 继续受目标元数据模型、预检明细介质和机构范围原子替换方案约束 |
| Flyway V1 | `NOT_AUTHORIZED` | 目标模型最终签字前不得创建或固化 |

下一工作包：

```text
C1：确认预检问题明细的物理存储、保留、查询和导出方案
C2：验证并冻结 Doris REPLACE_INSTITUTION_SCOPE 的机构范围原子替换方案
C3：完成账号权限、告警、外部 API、Quartz 等 P0 支撑对象及物理表字典 Review
C4：目标模型签字并取得实施授权后，依据 FRONTEND_API_CONTRACT_V1.md 生成 OpenAPI 和后端接口实现
```

状态边界：前端产品行为已经稳定，API 合同已经形成，但端到端系统仍未完成，不能标记为 `VERIFIED`。
'''
path.write_text(prefix + marker + section.split(marker, 1)[1], encoding="utf-8")
