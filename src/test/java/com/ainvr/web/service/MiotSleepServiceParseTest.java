package com.ainvr.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 真机回归：米家云 /miotspec/prop/get 单设备返回<b>顶层数组</b>，
 * 批量返回 {result:[...]}——此前只解析 result 包裹形态，单设备探测永远
 * code=-1、休眠从未自动探明（真机日志 resp=[{"did":..,"value":false,"code":0}] 实锤）。
 */
class MiotSleepServiceParseTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void flatArrayResponseIsAccepted() throws Exception {
        // 真机实测形态（2026-11-23 日志原文）
        var resp = M.readTree(
                "[{\"did\":\"1072813513\",\"iid\":\"0.2.1\",\"siid\":2,\"piid\":1,"
                        + "\"value\":false,\"code\":0,\"updateTime\":1795424018,\"exe_time\":0}]");
        var item = MiotSleepService.extractPropItem(resp);
        assertEquals(0, item.path("code").asInt(-1));
        assertTrue(item.path("value").isBoolean());
        assertEquals(false, item.path("value").asBoolean());
    }

    @Test
    void resultWrappedResponseIsAccepted() throws Exception {
        var resp = M.readTree("{\"result\":[{\"did\":\"d1\",\"code\":0,\"value\":true}]}");
        var item = MiotSleepService.extractPropItem(resp);
        assertEquals(0, item.path("code").asInt(-1));
        assertEquals(true, item.path("value").asBoolean());
    }

    @Test
    void emptyOrMissingFallsBackToMissingNode() throws Exception {
        assertEquals(-1, MiotSleepService.extractPropItem(M.readTree("[]")).path("code").asInt(-1));
        assertEquals(-1, MiotSleepService.extractPropItem(M.readTree("{}")).path("code").asInt(-1));
        assertEquals(-1, MiotSleepService.extractPropItem(M.readTree(
                "{\"message\":\"error\",\"code\":-1}")).path("code").asInt(-1));
        assertEquals(-1, MiotSleepService.extractPropItem(null).path("code").asInt(-1));
    }
}
