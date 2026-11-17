package com.ainvr.web.api;

import com.ainvr.store.AccessKeyStore;
import com.ainvr.web.service.SessionHolder;
import com.ainvr.web.service.TokenSessionManager;
import com.ainvr.web.service.TokenSessionManager.TokenSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 浏览器“一键解锁链接”入口：{@code GET /unlock?key=<令牌>}。
 *
 * <p>令牌由访问密码经 {@link AccessKeyStore#buildUnlockToken(char[])} 派生（见锁页
 * “生成一键解锁链接”）。校验通过即签发一条已解锁的<strong>令牌会话</strong>，并 302 跳回
 * {@code /?access_token=<token>}：前端启动时读取该参数、写入 localStorage 后清理地址栏，
 * 之后所有请求以 Authorization 头携带令牌——整个流程不依赖 Cookie，因此在跨站 iframe 中
 * 同样有效。校验失败跳回首页并带 {@code unlock_error=1}，由锁页提示。</p>
 *
 * <p>该路径为顶层页面跳转，不在 {@code AccessInterceptor} 的 /api、/hls 拦截范围内。</p>
 */
@Controller
public class UnlockController {

    private final AccessKeyStore keyStore;
    private final TokenSessionManager tokenSessions;
    private final SessionHolder sessionHolder;

    public UnlockController(AccessKeyStore keyStore, TokenSessionManager tokenSessions,
                           SessionHolder sessionHolder) {
        this.keyStore = keyStore;
        this.tokenSessions = tokenSessions;
        this.sessionHolder = sessionHolder;
    }

    @GetMapping("/unlock")
    public String unlock(@RequestParam(name = "key", required = false) String key) {
        if (key != null && keyStore.verifyUnlockToken(key)) {
            TokenSession session = tokenSessions.create();
            session.setUnlocked(true);
            // opt-in 模式下用令牌解码出的派生密钥恢复米家会话（默认模式为空操作）
            keyStore.verifyUnlockTokenAndGetKey(key).ifPresent(sessionHolder::restoreWithUnlockTokenKey);
            // v1.2.16：顺带把「记住解锁」凭证交给前端落盘，一键解锁后页面重新加载也无需再输密码
            return "redirect:/?access_token=" + session.getToken() + "&remember=" + key;
        }
        return "redirect:/?unlock_error=1";
    }
}
