package com.ainvr.web.service;

import com.ainvr.cloud.LoginSession;
import com.ainvr.cloud.MiCloudClientImpl;
import com.ainvr.store.AccessKeyStore;
import com.ainvr.store.SessionStore;
import com.ainvr.web.api.ApiException;
import com.ainvr.web.config.NvrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前米家登录会话的进程内持有者（Web 单例）。
 *
 * <p>所有下游（RTSP / HLS / 录像 / 设备发现）共享同一个 {@link AtomicReference}，
 * 云端续期后全局即时生效；会话同时加密落盘，重启可免密恢复。</p>
 *
 * <p>opt-in 的 {@code invr.session-key-from-password=true} 模式下，会话加密密钥由“访问密码”
 * 经 PBKDF2 派生（见 {@link AccessKeyStore}），不再生成与密文同目录的 {@code master.key}：
 * 启动时不自动恢复（需先用访问密码解锁），既避免无口令即可拿到米家会话，也消除
 * “密钥与密文同目录”的风险。</p>
 */
@Component
public class SessionHolder {

    private static final Logger log = LoggerFactory.getLogger(SessionHolder.class);

    private final SessionStore store;
    private final AccessKeyStore keyStore;
    private final NvrProperties props;
    private final AtomicReference<LoginSession> ref = new AtomicReference<>();

    public SessionHolder(SessionStore store, AccessKeyStore keyStore, NvrProperties props) {
        this.store = store;
        this.keyStore = keyStore;
        this.props = props;
        // 非 opt-in 模式无需在密码校验时派生会话密钥——省一次 12 万迭代 PBKDF2（解锁提速）
        keyStore.setSessionKeyCacheEnabled(props.isSessionKeyFromPassword());
        // opt-in 模式：启动时不自动恢复，需先经访问密码解锁（见 restoreWithKey）。
        if (!props.isSessionKeyFromPassword()) {
            store.load().ifPresent(s -> {
                ref.set(s);
                log.info("已从本地加密存储恢复米家会话 userId={} region={}", s.userId(), s.region());
            });
        }
    }

    public AtomicReference<LoginSession> ref() {
        return ref;
    }

    public Optional<LoginSession> get() {
        return Optional.ofNullable(ref.get());
    }

    public LoginSession require() {
        LoginSession s = ref.get();
        if (s == null) {
            throw ApiException.unauthorized("尚未登录米家账号");
        }
        return s;
    }

    public boolean isLoggedIn() {
        return ref.get() != null;
    }

    /**
     * 登录成功后调用：更新内存并加密落盘。opt-in 模式下若已设置访问密码（存在派生密钥）则用其保存；
     * 否则仅驻留内存，待设置访问密码时再落盘（避免生成 {@code master.key}）。
     */
    public void accept(LoginSession session) {
        ref.set(session);
        if (props.isSessionKeyFromPassword()) {
            byte[] k = keyStore.isConfigured() ? keyStore.cachedSessionKey() : null;
            if (k != null) {
                store.saveWithKey(session, k);
            }
            return;
        }
        store.save(session);
    }

    /**
     * 用访问密码派生密钥（opt-in 模式）解密并恢复此前落盘的米家会话。
     * 未设置访问密码 / 此前无会话 / 密钥不符均返回 false。
     */
    public boolean restoreWithKey(byte[] key) {
        if (key == null) {
            return false;
        }
        return store.loadWithKey(key).map(s -> {
            ref.set(s);
            log.info("已用访问密码派生的密钥恢复米家会话 userId={} region={}", s.userId(), s.region());
            return true;
        }).orElse(false);
    }

    /** 解锁成功后尝试用当前缓存的访问密码派生密钥恢复米家会话（仅 opt-in 模式生效）。 */
    public boolean restoreAfterUnlock() {
        if (!props.isSessionKeyFromPassword()) {
            return false;
        }
        return restoreWithKey(keyStore.cachedSessionKey());
    }

    /** 用一键解锁令牌解码出的密钥恢复米家会话（仅 opt-in 模式生效）。 */
    public boolean restoreWithUnlockTokenKey(byte[] key) {
        if (!props.isSessionKeyFromPassword()) {
            return false;
        }
        return restoreWithKey(key);
    }

    /** 若内存中已有米家会话，则按当前模式落盘（opt-in 用派生密钥，否则 master.key）。 */
    public void persistCurrentIfAny() {
        LoginSession s = ref.get();
        if (s != null) {
            accept(s);
        }
    }

    /** 用 passToken 续期会话（401 时），更新内存与落盘。 */
    public LoginSession renew() {
        LoginSession old = require();
        LoginSession renewed = new MiCloudClientImpl()
                .loginWithToken(old.userId(), old.passToken(), old.region());
        accept(renewed);
        log.info("米家会话已用 passToken 续期");
        return renewed;
    }

    public void clear() {
        ref.set(null);
        store.clear();
    }
}
