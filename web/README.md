# 东防数据采集系统前端

基于 Next.js App Router 的前端交互原型，覆盖机构、数据源、数据集、数据同步、数据预检、任务监控、数据校验、运维和系统设置等业务页面。当前数据均为前端 Mock 数据。

## 环境要求

- Node.js 20.9 或更高版本
- npm 10 或更高版本

## 本地运行

```bash
npm ci
npm run dev
```

打开 `http://localhost:3000`。

## 生产构建

```bash
npm run lint
npm run build
npm run start
```

## 目录

- `app/page.tsx`：应用外壳与主要交互
- `app/etl/`：业务模型、Mock 数据、路由和拆分页面
- `app/[...slug]/page.tsx`：支持刷新、前进后退和深链访问
- `public/`：静态资源

后续接入服务端时，可将 `app/etl/mock-data.ts` 替换为接口层，并把本地状态操作迁移到真实 API。
