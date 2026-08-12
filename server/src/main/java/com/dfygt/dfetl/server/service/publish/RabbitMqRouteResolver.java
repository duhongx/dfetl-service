package com.dfygt.dfetl.server.service.publish;

/**
 * RabbitMQ exchange/routing-key 派生规则的单一事实源。
 */
public final class RabbitMqRouteResolver {

    private RabbitMqRouteResolver() {
    }

    public static String exchangeName(String routeKey) {
        if (routeKey == null || routeKey.isEmpty()) {
            return "default";
        }
        int idx = routeKey.indexOf('_');
        return idx > 0 ? routeKey.substring(0, idx) : routeKey;
    }
}
