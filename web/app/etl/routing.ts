import type { AppPage } from "./model";

export const pagePaths: Record<AppPage, string> = {
  dashboard: "/dashboard", institutions: "/access-resources/institutions", datasources: "/access-resources/data-sources", datasets: "/access-resources/datasets",
  tasks: "/tasks/sync", taskDetail: "/tasks/sync/detail", precheck: "/tasks/precheck", precheckDetail: "/tasks/precheck/detail", monitor: "/tasks/monitor",
  validationOverview: "/validation/overview", validationWorkbench: "/validation/workbench", alerts: "/operations/alerts", logs: "/operations/logs", audit: "/operations/audit",
  globalSettings: "/settings/global", registrySettings: "/settings/medical-registry", validationSettings: "/settings/validation", dorisSettings: "/settings/doris-auto-create",
  externalApi: "/settings/external-api", mappingRules: "/settings/type-mapping", docs: "/docs",
};

export function parseLocation(pathname: string): { page: AppPage; id?: string } {
  const clean = pathname.replace(/\/$/, "") || "/";
  const taskMatch = clean.match(/^\/tasks\/sync\/([^/]+)$/);
  if (taskMatch && taskMatch[1] !== "detail") return { page: "taskDetail", id: decodeURIComponent(taskMatch[1]) };
  const precheckMatch = clean.match(/^\/tasks\/precheck\/([^/]+)$/);
  if (precheckMatch && precheckMatch[1] !== "detail") return { page: "precheckDetail", id: decodeURIComponent(precheckMatch[1]) };
  const match = (Object.entries(pagePaths) as Array<[AppPage, string]>).find(([, path]) => path === clean);
  return { page: match?.[0] ?? "dashboard" };
}

export function pathFor(page: AppPage, id?: string) {
  if (page === "taskDetail" && id) return `/tasks/sync/${encodeURIComponent(id)}`;
  if (page === "precheckDetail" && id) return `/tasks/precheck/${encodeURIComponent(id)}`;
  return pagePaths[page];
}
