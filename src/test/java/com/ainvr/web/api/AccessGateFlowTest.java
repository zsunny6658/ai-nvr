package com.ainvr.web.api;

import com.ainvr.store.AccessKeyStore;
import com.ainvr.web.config.TokenSessionFilter;
import com.ainvr.web.service.SessionHolder;
import com.ainvr.web.service.TokenSessionManager;
import com.ainvr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门禁令牌会话全流程（不启动 Spring 容器）：status 签发令牌 → unlock 写入解锁态 →
 * 错误密码保持锁定。
 */
class AccessGateFlowTest {

    private AccessKeyStore keyStoreConfigured(boolean passwordMatches) {
        AccessKeyStore keys = mock(AccessKeyStore.class);
        when(keys.isConfigured()).thenReturn(true);
        when(keys.verify(argThat((char[] pw) -> pw != null && "pw123456".equals(new String(pw)))))
                .thenReturn(passwordMatches);
        return keys;
    }

    private HttpServletRequest requestCarrying(TokenSession session) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(TokenSessionFilter.SESSION_ATTRIBUTE)).thenReturn(session);
        return req;
    }

    @Test
    void statusWithoutTokenIssuesNewToken() {
        AccessKeyStore keys = keyStoreConfigured(false);
        SessionHolder holder = mock(SessionHolder.class);
        when(holder.isLoggedIn()).thenReturn(false);
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, holder, tokens);

        Map<String, Object> m = controller.status(mock(HttpServletRequest.class));
        String token = (String) m.get("token");
        assertNotNull(token, "无令牌的 status 请求应自动签发新令牌");
        assertEquals(Boolean.FALSE, m.get("unlocked"));
        assertEquals(Boolean.TRUE, m.get("configured"));
        assertNotNull(tokens.resolve(token), "签发的令牌应立即可解析");
    }

    @Test
    void unlockSuccessMarksSessionUnlockedAndReturnsSameToken() {
        AccessKeyStore keys = keyStoreConfigured(true);
        SessionHolder holder = mock(SessionHolder.class);
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, holder, tokens);

        TokenSession session = tokens.create(); // 前端先经 status 拿到的令牌会话
        Map<String, Object> m = controller.unlock(
                requestCarrying(session), new AccessController.PasswordRequest("pw123456"));

        assertEquals(Boolean.TRUE, m.get("unlocked"));
        assertEquals(session.getToken(), m.get("token"));
        assertTrue(tokens.resolve(session.getToken()).isUnlocked(), "同令牌后续请求应处于解锁态");
        assertTrue(AccessController.isUnlocked(tokens.resolve(session.getToken())));
    }

    @Test
    void wrongPasswordKeepsSessionLockedAndReturns401() {
        AccessKeyStore keys = keyStoreConfigured(false);
        SessionHolder holder = mock(SessionHolder.class);
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, holder, tokens);

        TokenSession session = tokens.create();
        ApiException ex = assertThrows(ApiException.class, () -> controller.unlock(
                requestCarrying(session), new AccessController.PasswordRequest("bad-pw")));
        assertEquals(401, ex.getStatus());
        assertFalse(session.isUnlocked(), "密码错误时会话不得解锁");
    }

    @Test
    void unlockReturnsRememberCredentialForSilentReauth() {
        AccessKeyStore keys = keyStoreConfigured(true);
        when(keys.regenerateUnlockToken(any())).thenReturn("remember-token-123");
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, mock(SessionHolder.class), tokens);

        TokenSession session = tokens.create();
        Map<String, Object> m = controller.unlock(requestCarrying(session),
                new AccessController.PasswordRequest("pw123456"));
        assertEquals("remember-token-123", m.get("remember"));
    }

    @Test
    void reauthRestoresUnlockedSessionWithRememberCredential() {
        AccessKeyStore keys = keyStoreConfigured(true);
        byte[] key = new byte[32];
        when(keys.verifyUnlockTokenAndGetKey("valid-remember")).thenReturn(java.util.Optional.of(key));
        SessionHolder holder = mock(SessionHolder.class);
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, holder, tokens);

        TokenSession session = tokens.create(); // 模拟页面重载后服务端新签发的未解锁会话
        assertFalse(session.isUnlocked());
        Map<String, Object> m = controller.reauth(requestCarrying(session),
                new AccessController.ReauthRequest("valid-remember"));

        assertEquals(Boolean.TRUE, m.get("unlocked"));
        assertEquals(session.getToken(), m.get("token"));
        assertTrue(tokens.resolve(session.getToken()).isUnlocked());
        org.mockito.Mockito.verify(holder).restoreWithUnlockTokenKey(key);
    }

    @Test
    void reauthRejectsMissingOrInvalidCredential() {
        AccessKeyStore keys = keyStoreConfigured(true);
        when(keys.verifyUnlockTokenAndGetKey("bad")).thenReturn(java.util.Optional.empty());
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, mock(SessionHolder.class), tokens);
        TokenSession session = tokens.create();

        ApiException e1 = assertThrows(ApiException.class, () -> controller.reauth(
                requestCarrying(session), new AccessController.ReauthRequest(null)));
        assertEquals(401, e1.getStatus());
        ApiException e2 = assertThrows(ApiException.class, () -> controller.reauth(
                requestCarrying(session), new AccessController.ReauthRequest("bad")));
        assertEquals(401, e2.getStatus());
        assertFalse(session.isUnlocked(), "凭证无效时不得解锁");
    }

    @Test
    void unlockPassesTrimmedPasswordCharsToKeyStore() {
        AccessKeyStore keys = mock(AccessKeyStore.class);
        when(keys.isConfigured()).thenReturn(true);
        when(keys.verify(any())).thenReturn(true);
        TokenSessionManager tokens = new TokenSessionManager();
        AccessController controller = new AccessController(keys, mock(SessionHolder.class), tokens);

        TokenSession session = tokens.create();
        controller.unlock(requestCarrying(session), new AccessController.PasswordRequest("pw123456"));

        ArgumentCaptor<char[]> captor = ArgumentCaptor.forClass(char[].class);
        org.mockito.Mockito.verify(keys).verify(captor.capture());
        assertEquals("pw123456", new String(captor.getValue()));
    }
}
