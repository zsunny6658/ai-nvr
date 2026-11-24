package com.ainvr.hls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 第二十五轮：live.mp4 滚动拼接的分片发现逻辑。
 * m3u8 播放列表是「已完成分片」的唯一事实来源（muxer 关片后才列出），
 * 解析必须对重写中的半截内容、重复条目、乱序保持稳健。
 */
class HlsSinkLiveSegmentsTest {

    @TempDir
    File tmp;

    private HlsSink sink() {
        return new HlsSink("1149849923", tmp, 6, 6, false);
    }

    @Test
    void parsesCompletedSegmentsInOrder() throws Exception {
        Files.writeString(tmp.toPath().resolve("index.m3u8"),
                "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MEDIA-SEQUENCE:5\n"
                        + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.004000,\nseg-5.m4s\n"
                        + "#EXTINF:6.027000,\nseg-6.m4s\n#EXTINF:5.983000,\nseg-7.m4s\n",
                StandardCharsets.UTF_8);
        assertEquals(List.of(5, 6, 7), sink().playlistSegmentNumbers());
    }

    @Test
    void toleratesGarbageAndDuplicates() throws Exception {
        Files.writeString(tmp.toPath().resolve("index.m3u8"),
                "#EXTM3U\nseg-3.m4s\nseg-3.m4s\nbroken line\nseg-10.m4s\n",
                StandardCharsets.UTF_8);
        assertEquals(List.of(3, 10), sink().playlistSegmentNumbers());
    }

    @Test
    void emptyWhenNoPlaylistOrNoSegments() throws Exception {
        assertTrue(sink().playlistSegmentNumbers().isEmpty());
        Files.writeString(tmp.toPath().resolve("index.m3u8"),
                "#EXTM3U\n#EXT-X-ENDLIST\n", StandardCharsets.UTF_8);
        assertTrue(sink().playlistSegmentNumbers().isEmpty());
    }

    @Test
    void mediaFileWhitelistStaysTight() {
        // live.mp4 由 HlsService 拼接产出，不落在 sink 目录、也不走 mediaFile 白名单
        assertNull(sink().mediaFile("live.mp4"));
        assertNull(sink().mediaFile("../index.m3u8"));
        assertNull(sink().mediaFile("seg-abc.m4s"));
    }
}
