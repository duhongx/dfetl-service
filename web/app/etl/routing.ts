import type { AppPage } from "./model";

export const pagePaths: Record<Exclude<AppPage, "taskDetail" | "precheckDetail">, string> = {
  dashboard: "/dashboard",
  institutions: "/access-resources/institutions",
  businessCatalogs: "/access-resources/business-catalogs",
  sourceDatasources: "/access-resources/source-datasources",
  targetDatasources: "/access-resources/target-datasources",
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
  externalApi: "/settings/external-api",
  mappingRules: "/settings/type-mapping",
  accounts: "/settings/accounts",
  docs: "/docs",
};

export function parseLocation(pathname: string): { page: AppPage; id?: string } {
  const clean = pathname.replace(/\/$/, "") || "/dashboard";
  const task = clean.match(/^\/tasks\/sync\/([^/]+)$/);
  if (task) return { page: "taskDetail", id: decodeURIComponent(task[1]) };
  const precheck = clean.match(/^\/tasks\/precheck\/([^/]+)$/);
  if (precheck) return { page: "precheckDetail", id: decodeURIComponent(precheck[1]) };
  const match = (Object.entries(pagePaths) as Array<[AppPage, string]>).find(([, path]) => path === clean);
  return { page: match?.[0] ?? "dashboard" };
}

export function pathFor(page: AppPage, id?: string): string {
  if (page === "taskDetail") return `/tasks/sync/${encodeURIComponent(id ?? "")}`;
  if (page === "precheckDetail") return `/tasks/precheck/${encodeURIComponent(id ?? "")}`;
  return pagePaths[page];
}
