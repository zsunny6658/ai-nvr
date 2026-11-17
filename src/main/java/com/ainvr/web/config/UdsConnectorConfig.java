package com.ainvr.web.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 双访问监听：TCP 9099（局域网直连）+ fnOS 统一网关 Unix Domain Socket。
 *
 * <p>socket 路径判定优先级：</p>
 * <ol>
 *   <li>Spring 属性 {@code invr.uds-socket}（fnOS 打包时由 install_callback 把
 *       {@code ${TRIM_APPDEST}/app.sock} 固化写入 etc/application-fnos.yml）；</li>
 *   <li>环境变量 {@code TRIM_APPDEST}（兜底拼接 {@code $TRIM_APPDEST/app.sock}）。</li>
 * </ol>
 * <p>两者都不存在时（本地开发），只监听 TCP，行为与原来完全一致。</p>
 *
 * <h2>关键约束（基于 Tomcat 10.1.31 实测）</h2>
 * <ul>
 *   <li>Unix Domain Socket <b>仅 NIO 连接器（Http11NioProtocol → NioEndpoint）支持</b>；
 *       NIO2（Http11Nio2Protocol → Nio2Endpoint）完全没有 unixDomainSocketPath 实现，
 *       用它会导致连接器退化成「端口 -1 的 TCP」被 Tomcat 判非法、启动失败、网关 502。</li>
 *   <li>{@code Connector.setProperty("unixDomainSocketPath", ...)} 不会转发到 Endpoint，
 *       Connector / AbstractProtocol 自身都没有该 setter；必须使用 NIO 协议，
 *       并通过 protocolHandler 的 endpoint 实例直接设置（见下方反射调用）。</li>
 * </ul>
 */
@Configuration
public class UdsConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> udsConnectorCustomizer(
            @Value("${invr.uds-socket:}") String configuredSocket) {
        return factory -> {
            String sock = configuredSocket == null ? "" : configuredSocket.trim();
            if (sock.isEmpty()) {
                // 兜底：尝试 fnOS 注入的环境变量
                String appDest = System.getenv("TRIM_APPDEST");
                if (appDest == null || appDest.isBlank()) {
                    return; // 本地开发 / 非 fnOS 环境：纯 TCP 直连
                }
                sock = appDest.trim() + "/app.sock";
            }

            try {
                // 必须用 NIO 协议，NIO2 不支持 UDS
                Connector uds = new Connector("org.apache.coyote.http11.Http11NioProtocol");

                // 反射取出 Endpoint（AbstractProtocol.endpoint 字段），直设 UDS 路径。
                // 这是 Tomcat 10.1 让 UDS 生效的唯一可靠途径（setProperty 不转发到 Endpoint）。
                Object protocolHandler = uds.getProtocolHandler();
                Field endpointField = null;
                Class<?> clazz = protocolHandler.getClass();
                while (clazz != null && endpointField == null) {
                    try {
                        endpointField = clazz.getDeclaredField("endpoint");
                    } catch (NoSuchFieldException e) {
                        clazz = clazz.getSuperclass();
                    }
                }
                if (endpointField == null) {
                    throw new IllegalStateException("无法定位 Tomcat Endpoint 字段，UDS 连接器配置失败");
                }
                endpointField.setAccessible(true);
                Object endpoint = endpointField.get(protocolHandler);

                Method setUdsPath = endpoint.getClass().getMethod("setUnixDomainSocketPath", String.class);
                setUdsPath.invoke(endpoint, sock);

                // socket 权限：rw-rw-rw-，保证 fnOS 网关（nginx）能访问
                try {
                    Method setUdsPerm = endpoint.getClass().getMethod("setUnixDomainSocketPathPermissions", String.class);
                    setUdsPerm.invoke(endpoint, "rw-rw-rw-");
                } catch (NoSuchMethodException ignored) {
                    // 旧版 Tomcat 无此 setter，依赖网关侧 chmod 兜底
                }

                factory.addAdditionalTomcatConnectors(uds);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("配置 fnOS Unix Domain Socket 连接器失败: " + sock, ex);
            }
        };
    }
}
