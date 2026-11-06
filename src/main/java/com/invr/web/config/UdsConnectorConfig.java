package com.invr.web.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 双访问监听：TCP 9080（局域网直连）+ fnOS 统一网关 Unix Socket。
 *
 * <p>socket 路径判定优先级：</p>
 * <ol>
 *   <li>Spring 属性 {@code invr.uds-socket}（fnOS 打包时由 install_callback 把
 *       {@code ${TRIM_APPDEST}/app.sock} 固化写入 etc/application-fnos.yml，
 *       不依赖服务进程的运行时环境）；</li>
 *   <li>环境变量 {@code TRIM_APPDEST}（fnOS 注入，兜底拼接 {@code $TRIM_APPDEST/app.sock}）。</li>
 * </ol>
 * <p>两者都不存在时（本地开发），只监听 TCP 9080，行为与原来完全一致。</p>
 *
 * <p>Tomcat 10.1 NIO 原生支持 Unix Domain Socket，无需 APR/native 库。</p>
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
            Connector uds = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            uds.setProperty("unixDomainSocketPath", sock);
            uds.setProperty("unixDomainSocketPathPermissions", "rw-rw-rw-");
            factory.addAdditionalTomcatConnectors(uds);
        };
    }
}
