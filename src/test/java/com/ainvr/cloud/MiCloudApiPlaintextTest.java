package com.ainvr.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 第二十二轮：米家云明文 JSON 应答判定。
 *
 * <p>背景：会话失效时米家云直接返回明文 JSON（{"code":401,...}），此前无脑 Base64
 * 解码抛 IllegalArgumentException: Illegal base64 character 7b（7b='{'}），
 * code=401 永远到不了续期分支，会话死后设备列表/云中继/预置位全链路无法自愈。</p>
 */
class MiCloudApiPlaintextTest {

    @Test
    void plaintextJsonDetected() {
        assertTrue(MiCloudApi.looksLikePlaintextJson("{\"code\":401,\"message\":\"session invalid\"}"));
        assertTrue(MiCloudApi.looksLikePlaintextJson("{\"code\":0,\"result\":{}}"));
        assertTrue(MiCloudApi.looksLikePlaintextJson("  []  "));
    }

    @Test
    void encryptedBase64NotMistaken() {
        assertFalse(MiCloudApi.looksLikePlaintextJson("aGVsbG8gd29ybGQ="));
        assertFalse(MiCloudApi.looksLikePlaintextJson(""));
        assertFalse(MiCloudApi.looksLikePlaintextJson(null));
        assertFalse(MiCloudApi.looksLikePlaintextJson("RC4 加密应答恒为 Base64 字符集开头"));
    }
}
