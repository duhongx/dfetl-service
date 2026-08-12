package com.dfygt.dfetl.server.common;

import com.dfygt.dfetl.server.config.WebhookSecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Webhook 出站 URL 的 SSRF 校验器。
 * <p>
 * 告警渠道 webhook URL 由用户配置，服务端发起出站 POST。若不校验，可被用于：
 * <ul>
 *   <li>探测/攻击内网服务（{@code http://10.0.0.x:port/...}）</li>
 *   <li>读取云厂商元数据（{@code http://169.254.169.254/...}）凭据</li>
 *   <li>回环打到本机管理端点</li>
 *   <li>用内嵌凭据 {@code http://user:pass@host} 伪装</li>
 * </ul>
 *
 * <p>校验在两个时机执行：保存渠道时（{@code AlertController.create/update}）
 * 和发送前（{@code AlertEvaluatorService}、测试连通性）。后者防止配置在保存后被
 * 直接改库绕过，以及 DNS 重绑定（每次发送都重新解析）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookUrlValidator {

    private final WebhookSecurityProperties properties;

    /**
     * 校验 webhook URL 是否允许出站；不合规抛 {@link IllegalArgumentException}。
     *
     * @param rawUrl 用户配置的 webhook URL
     */
    public void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("webhook URL 不能为空");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法的 webhook URL：" + rawUrl);
        }

        // 1. 协议白名单：仅 http/https
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("webhook URL 仅支持 http/https 协议：" + rawUrl);
        }

        // 2. 拒绝内嵌凭据（http://user:pass@host），避免伪装与凭据泄露
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("webhook URL 不允许内嵌用户名/密码");
        }

        // 3. 主机名必须存在
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("webhook URL 缺少主机名：" + rawUrl);
        }
        String normalizedHost = normalizeHost(host);

        // 4. 端口黑名单
        int port = uri.getPort();
        if (port != -1 && properties.getBlockedPorts().contains(port)) {
            throw new IllegalArgumentException("webhook URL 端口被禁止：" + port);
        }

        // 5. 主机白名单：非空时只放行白名单内主机（跳过私有网段限制，但仍校验危险地址）
        boolean whitelisted = false;
        if (properties.getAllowedHosts() != null && !properties.getAllowedHosts().isEmpty()) {
            whitelisted = properties.getAllowedHosts().stream()
                    .anyMatch(h -> h != null && normalizeHost(h.trim()).equalsIgnoreCase(normalizedHost));
            if (!whitelisted) {
                throw new IllegalArgumentException("webhook 主机不在白名单内：" + normalizedHost);
            }
        }

        // 6. 解析所有 A/AAAA 记录，逐个校验（防 DNS 多记录绕过）
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalizedHost);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("webhook 主机无法解析：" + normalizedHost);
        }
        for (InetAddress addr : addresses) {
            assertAddressAllowed(addr, normalizedHost, whitelisted);
        }
    }

    /** IDN（含中文域名）统一转 ASCII，避免同形域名绕过白名单。 */
    private String normalizeHost(String host) {
        // 去掉 IPv6 字面量的方括号
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        try {
            return IDN.toASCII(h).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return h.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 校验解析出的 IP 是否允许出站。
     * 始终拒绝：回环、链路本地（含云元数据 169.254.169.254）、通配（0.0.0.0）、组播。
     * 私有网段：白名单主机或 allowPrivateNetwork=true 时放行。
     */
    private void assertAddressAllowed(InetAddress addr, String host, boolean whitelisted) {
        if (addr.isLoopbackAddress()) {
            throw new IllegalArgumentException("webhook 目标解析到回环地址，已拒绝：" + host);
        }
        if (addr.isLinkLocalAddress()) {
            // 含 169.254.169.254 云元数据端点
            throw new IllegalArgumentException("webhook 目标解析到链路本地地址（含云元数据），已拒绝：" + host);
        }
        if (addr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("webhook 目标解析到通配地址，已拒绝：" + host);
        }
        if (addr.isMulticastAddress()) {
            throw new IllegalArgumentException("webhook 目标解析到组播地址，已拒绝：" + host);
        }
        boolean privateNetwork = addr.isSiteLocalAddress() || isUniqueLocalIpv6(addr);
        if (privateNetwork && !whitelisted && !properties.isAllowPrivateNetwork()) {
            throw new IllegalArgumentException(
                    "webhook 目标解析到私有网段，当前配置不允许（dfetl.security.webhook.allow-private-network=false）：" + host);
        }
    }

    /** IPv6 ULA（fc00::/7），Java 没有内置判定，手动识别。 */
    private boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
