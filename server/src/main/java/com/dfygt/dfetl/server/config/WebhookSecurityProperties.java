package com.dfygt.dfetl.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Webhook 出站请求的 SSRF 防护配置。
 * <p>
 * 配置前缀 {@code dfetl.security.webhook}。告警渠道（钉钉/企业微信/内部 IM 网关）
 * 的 webhook URL 由用户配置，属于"用户可控 + 服务端发起出站请求"场景，
 * 必须做 SSRF 校验，避免被用来探测/攻击内网或云元数据端点。
 *
 * <p>无论如何都会被拒绝（与本配置无关）：回环地址、链路本地地址（含
 * {@code 169.254.169.254} 云元数据）、通配地址、组播地址、非 http(s) 协议、
 * URL 内嵌凭据。本配置仅控制"私有网段"是否放行。
 */
@Data
@ConfigurationProperties(prefix = "dfetl.security.webhook")
public class WebhookSecurityProperties {

    /**
     * 是否允许私有网段（10/8、172.16/12、192.168/16 等 site-local）。
     * <p>本产品多为客户现场私有化部署，webhook 目标常是内网企业 IM 网关，
     * 因此默认放行私有网段。若部署在更敏感环境，建议置为 false 并配置
     * {@link #allowedHosts} 白名单。
     */
    private boolean allowPrivateNetwork = true;

    /**
     * 主机白名单（精确匹配，不区分大小写）。非空时仅允许列表内的主机；
     * 白名单内的主机会跳过私有网段限制（管理员已显式信任），
     * 但回环/链路本地/通配/组播仍然始终拒绝。
     */
    private List<String> allowedHosts = new ArrayList<>();

    /**
     * 端口黑名单。命中即拒绝（如不希望 webhook 打到内部管理端口）。空表示不限制。
     */
    private List<Integer> blockedPorts = new ArrayList<>();
}
