package com.ainvr.web.service;

import com.ainvr.miss.MissCommands;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预置位 fav-area value 解析 + 对讲上行编码选择（v2.3.1）。
 */
class PresetAndSpeakerTest {

    @Test
    void parsePresetListStandard() {
        String value = "[{\"idx\":0,\"location\":0,\"name\":\" doorway\"},"
                + "{\"location\":2,\"name\":\"客厅\"}]";
        List<MiotPresetService.Preset> list = MiotPresetService.parsePresetList(value);
        assertEquals(2, list.size());
        assertEquals(0, list.get(0).location());
        assertEquals(2, list.get(1).location());
    }

    @Test
    void parsePresetListBase64Name() {
        String b64 = Base64.getEncoder().encodeToString("门口".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String value = "[{\"location\":1,\"name\":\"" + b64 + "\"}]";
        List<MiotPresetService.Preset> list = MiotPresetService.parsePresetList(value);
        assertEquals(1, list.size());
        assertEquals("门口", list.get(0).name());
    }

    @Test
    void parsePresetListPlainNameKeptAsIs() {
        // "Home" 恰好满足 Base64 字符集但解码结果不可读：保留原文
        List<MiotPresetService.Preset> list = MiotPresetService.parsePresetList(
                "[{\"location\":3,\"name\":\"Home\"}]");
        assertEquals("Home", list.get(0).name());
    }

    @Test
    void parsePresetListInvalidAndEmpty() {
        assertTrue(MiotPresetService.parsePresetList("").isEmpty());
        assertTrue(MiotPresetService.parsePresetList("not-json").isEmpty());
        assertTrue(MiotPresetService.parsePresetList("{\"a\":1}").isEmpty());
        // 越界 location 被过滤
        assertTrue(MiotPresetService.parsePresetList("[{\"location\":65,\"name\":\"x\"}]").isEmpty());
    }

    @Test
    void speakerCodecSelection() {
        assertEquals(MissCommands.CODEC_PCMA, MissCommands.speakerCodecFor("chuangmi.camera.029a02"));
        assertEquals(MissCommands.CODEC_PCMA, MissCommands.speakerCodecFor("isa.camera.hlmax"));
        assertEquals(MissCommands.CODEC_PCM, MissCommands.speakerCodecFor("isa.camera.hlc6"));
        assertTrue(MissCommands.speakerLinearPcm("isa.camera.hlc6"));
        assertFalse(MissCommands.speakerLinearPcm("chuangmi.camera.029a02"));
        // v2.3.1 第十六轮：72ac1 走 OPUS（浏览器 WebCodecs 编码），不再整体拒绝
        assertEquals(MissCommands.CODEC_OPUS, MissCommands.speakerCodecFor("chuangmi.camera.72ac1"));
        // v2.3.1 第二十七轮：hlc8（CW400）/ 039a01 上行音频实为 OPUS（go2rtc DEVICES.md +
        // lalmax 文档 + 真机 HlsSink 实收 CODEC_OPUS），PCMA 8k 上行 → 变声/杂音/无声
        assertEquals(MissCommands.CODEC_OPUS, MissCommands.speakerCodecFor("isa.camera.hlc8"));
        assertEquals(MissCommands.CODEC_OPUS, MissCommands.speakerCodecFor("chuangmi.camera.039a01"));
        assertFalse(MissCommands.speakerLinearPcm("isa.camera.hlc8"));
    }

    @Test
    void speakerCodecPerDidOverride() {
        String did = "test-did-override";
        System.setProperty("invr.talkcodec." + did, "pcma");
        try {
            assertEquals(MissCommands.CODEC_PCMA,
                    MissCommands.speakerCodecFor("isa.camera.hlc8", did));
            System.setProperty("invr.talkcodec." + did, "pcm");
            assertTrue(MissCommands.speakerLinearPcm("isa.camera.hlc8", did));
        } finally {
            System.clearProperty("invr.talkcodec." + did);
        }
    }
}
