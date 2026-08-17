import type { AppPage } from "./model";

export type AppLocation = { page: AppPage; id?: string; subId?: string };

export const pagePaths: Record<
  Exclude<AppPage, "taskDetail" | "precheckRouteDetail" | "precheckRunDetail" | "executionDetail">,
  string
> = {
  dashboard: "/dashboard",
  institutions: "/access-resources/institutions",
  systemInstances: "/access-resources/system-instances",
  datasources: "/access-resources/datasources",
  datasets: "/access-resources/datasets",
  routes: "/routes",
  tasks: "/tasks/sync",
  precheck: "/tasks/precheck",
  monitor: "/tasks/executions",
  validationOverview: "/validation/overview",
  validationWorkbench: "/validation/workbench",
  alerts: "/operations/alerts",
  logs: "/operations/logs",
  audit: "/operations/audit",
  globalSettings: "/settings/global",
  registrySettings: "/settings/medical-registry",
  validationPolicy: "/settings/validation-policy",
  dorisTables: "/settings/doris-tables",
  externalApi: "/settings/external-api",
  mappingRules: "/settings/type-mapping",
  security: "/settings/security",
  docs: "/docs",
};

export function parseLocation(pathname: string): AppLocation {
  const clean = pathname.replace(/\/$/, "") || "/dashboard";

  const task = clean.match(/^\/tasks\/sync\/([^/]+)$/);
  if (task) return { page: "taskDetail", id: decodeURIComponent(task[1]) };

  const precheckRun = clean.match(/^\/tasks\/precheck\/([^/]+)\/runs\/([^/]+)$/);
  if (precheckRun) {
    return {
      page: "precheckRunDetail",
      id: decodeURIComponent(precheckRun[1]),
      subId: decodeURIComponent(precheckRun[2]),
    };
  }

  const precheckRoute = clean.match(/^\/tasks\/precheck\/([^/]+)$/);
  if (precheckRoute) return { page: "precheckRouteDetail", id: decodeURIComponent(precheckRoute[1]) };

  const execution = clean.match(/^\/tasks\/executions\/([^/]+)$/);
  if (execution) return { page: "executionDetail", id: decodeURIComponent(execution[1]) };

  const match = (Object.entries(pagePaths) as Array<[AppPage, string]>).find(([, path]) => path === clean);
  return { page: match?.[0] ?? "dashboard" };
}

export function pathFor(page: AppPage, id?: string, subId?: string): string {
  if (page === "taskDetail") return `/tasks/sync/${encodeURIComponent(id ?? "")}`;
  if (page === "precheckRouteDetail") return `/tasks/precheck/${encodeURIComponent(id ?? "")}`;
  if (page === "precheckRunDetail") {
    return `/tasks/precheck/${encodeURIComponent(id ?? "")}/runs/${encodeURIComponent(subId ?? "")}`;
  }
  if (page === "executionDetail") return `/tasks/executions/${encodeURIComponent(id ?? "")}`;
  return pagePaths[page];
}
