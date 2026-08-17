import type { AccountRow, AppPage, RoleRow } from "./model";

export const pagePermission: Record<AppPage, string> = {
  dashboard: "dashboard.view",
  institutions: "institution.view",
  systemInstances: "system_instance.view",
  datasources: "datasource.view",
  datasets: "dataset.view",
  routes: "route.view",
  tasks: "sync_task.view",
  taskDetail: "sync_task.view",
  precheck: "precheck.view",
  precheckRouteDetail: "precheck.view",
  precheckRunDetail: "precheck.view",
  monitor: "sync_execution.view",
  executionDetail: "sync_execution.view",
  validationOverview: "validation.view",
  validationWorkbench: "validation.view",
  alerts: "alert.view",
  logs: "log.view",
  audit: "audit.view",
  globalSettings: "setting.view",
  registrySettings: "registry.view",
  validationPolicy: "validation_policy.view",
  dorisTables: "doris_table.view",
  externalApi: "external_client.view",
  mappingRules: "type_mapping.view",
  security: "security.account.view",
  docs: "dashboard.view",
};

export function resolvePermissions(account: AccountRow | undefined, roles: RoleRow[]): Set<string> {
  if (!account?.enabled) return new Set<string>();
  const permissions = new Set<string>();
  for (const roleId of account.roleIds) {
    const role = roles.find((item) => item.id === roleId);
    if (!role) continue;
    for (const permission of role.permissions) permissions.add(permission);
  }
  return permissions;
}

export function hasPermission(permissions: Set<string>, permission: string): boolean {
  return permissions.has("*") || permissions.has(permission);
}

export const permissionGroups: Array<{ domain: string; permissions: string[] }> = [
  { domain: "接入资源", permissions: [
    "institution.view", "institution.create", "institution.update", "institution.status", "institution.delete",
    "system_instance.view", "system_instance.create", "system_instance.update", "system_instance.bind_institution",
    "system_instance.bind_datasource", "system_instance.status", "system_instance.delete",
    "datasource.view", "datasource.source.create", "datasource.source.update", "datasource.target.create",
    "datasource.target.update", "datasource.test", "datasource.status", "datasource.credential.rotate", "datasource.delete",
  ] },
  { domain: "数据集与链路", permissions: [
    "dataset.view", "dataset.sync_definition", "dataset.policy.sync.update", "dataset.policy.validation.update",
    "dataset.policy.message.update", "route.view", "route.create", "route.version.create", "route.delete",
  ] },
  { domain: "任务与执行", permissions: [
    "sync_task.view", "sync_task.create", "sync_task.version.create", "sync_task.schedule", "sync_task.run",
    "sync_task.recollect", "sync_task.backfill", "sync_task.watermark.reset", "sync_task.delete",
    "sync_execution.view", "sync_execution.cancel", "sync_execution.export", "message_outbox.view", "message_outbox.retry",
  ] },
  { domain: "预检与校验", permissions: [
    "precheck.view", "precheck.run", "precheck.run_batch", "precheck.cancel", "precheck.summary.view",
    "precheck.summary.export", "precheck.detail.view", "precheck.detail.reveal", "precheck.detail.export",
    "precheck.detail.export_sensitive", "validation.view", "validation.run", "validation.recheck", "validation.export",
    "validation.delete_reconciliation.run", "validation.delete_apply.dry_run", "validation.delete_apply.execute",
  ] },
  { domain: "告警、日志与审计", permissions: [
    "alert.view", "alert.delivery.retry", "alert.rule.manage", "alert.rule.status", "alert.rule.delete",
    "alert.channel.manage", "alert.channel.test", "alert.channel.status", "alert.channel.delete",
    "log.view", "log.sensitive.view", "log.export", "audit.view", "audit.export",
  ] },
  { domain: "系统设置与安全", permissions: [
    "setting.view", "setting.global.update", "registry.view", "registry.update", "registry.test",
    "validation_policy.view", "validation_policy.update", "doris_table.view", "doris_table.ddl.preview",
    "doris_table.create", "doris_table.rebuild", "external_client.view", "external_client.create",
    "external_client.update", "external_client.status", "external_client.secret.reset", "external_client.delete",
    "type_mapping.view", "type_mapping.generic.create", "type_mapping.generic.update", "type_mapping.generic.delete",
    "type_mapping.contract.publish", "security.account.view", "security.account.create", "security.account.update",
    "security.account.status", "security.account.password.reset", "security.permission.assign",
    "security.role.manage", "security.role.delete",
  ] },
];
