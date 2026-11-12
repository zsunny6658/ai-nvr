package com.invr.web.api;

import com.invr.model.ShareBoard;
import com.invr.web.service.LiveFrameService;
import com.invr.web.service.SessionHolder;
import com.invr.web.service.ShareBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.2.3 兼容画面帧端点的 HTTP 层验证：登录态端点、免鉴权共享端点、令牌/did 双重授权、
 * 无缓存响应头与原始字节透传。不启动 Spring 上下文、不接触 native 解码器（帧服务用 mock）。
 */
class LiveFrameWebTest {

    private static final String DID = "1072813513";
    private static final byte[] JPEG = "FAKE-JPEG-BYTES".getBytes(StandardCharsets.UTF_8);

    private MockMvc mvc;
    private SessionHolder session;

    @BeforeEach
    void setUp() {
        LiveFrameService frames = Mockito.mock(LiveFrameService.class);
        Mockito.when(frames.frame(DID)).thenReturn(JPEG);
        session = Mockito.mock(SessionHolder.class);
        Mockito.when(session.isLoggedIn()).thenReturn(true);

        ShareBoardService boards = Mockito.mock(ShareBoardService.class);
        ShareBoard b = new ShareBoard();
        b.cameras = List.of(DID);
        Mockito.when(boards.resolveByToken("good-token")).thenReturn(b);
        Mockito.when(boards.resolveByToken("bad-token")).thenReturn(null);

        mvc = MockMvcBuilders.standaloneSetup(new LiveFrameController(frames, session, boards))
                .build();
    }

    @Test
    void loggedInFrameEndpointReturnsJpegWithNoStore() throws Exception {
        byte[] body = mvc.perform(get("/api/frame/" + DID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(JPEG, body);
    }

    @Test
    void loggedOutFrameEndpointIsGone() throws Exception {
        Mockito.when(session.isLoggedIn()).thenReturn(false);
        mvc.perform(get("/api/frame/" + DID)).andExpect(status().isGone());
    }

    @Test
    void sharedFrameEndpointNeedsNoLoginButValidToken() throws Exception {
        byte[] body = mvc.perform(get("/share/good-token/frame/" + DID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(JPEG, body);
    }

    @Test
    void sharedFrameRejectsInvalidToken() throws Exception {
        mvc.perform(get("/share/bad-token/frame/" + DID)).andExpect(status().isGone());
    }

    @Test
    void sharedFrameRejectsDidOutsideBoard() throws Exception {
        mvc.perform(get("/share/good-token/frame/999999999")).andExpect(status().isGone());
    }
}
