package com.ainvr.web.api;

import com.ainvr.model.ShareBoard;
import com.ainvr.web.service.LiveFrameService;
import com.ainvr.web.service.SessionHolder;
import com.ainvr.web.service.ShareBoardService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兼容画面帧端点：以 {@code image/jpeg} 返回当前一帧，供前端按间隔轮询组成动态画面。
 *
 * <ul>
 *   <li>{@code GET /api/frame/{did}}：登录态页面使用，受 {@code /api/**} 门禁保护；</li>
 *   <li>{@code GET /share/{token}/frame/{did}}：免鉴权共享页使用，令牌有效且 did 属于该看板
 *       才放行，与 {@link ShareHlsController} 同一授权口径。</li>
 * </ul>
 *
 * <p>两者共用 {@link LiveFrameService} 的常驻帧源，不会因轮询反复重连摄像头。响应头显式
 * 禁用缓存（部分移动 WebView 会对同 URL 图片强缓存，导致画面停在第一帧）。</p>
 */
@RestController
public class LiveFrameController {

    private final LiveFrameService frames;
    private final SessionHolder session;
    private final ShareBoardService boards;

    public LiveFrameController(LiveFrameService frames, SessionHolder session,
                               ShareBoardService boards) {
        this.frames = frames;
        this.session = session;
        this.boards = boards;
    }

    @GetMapping("/api/frame/{did}")
    public void frame(@PathVariable String did, HttpServletResponse resp) throws Exception {
        if (!session.isLoggedIn()) {
            resp.sendError(HttpServletResponse.SC_GONE, "米家账号未登录");
            return;
        }
        writeJpeg(frames.frame(did), resp);
    }

    @GetMapping("/share/{token}/frame/{did}")
    public void sharedFrame(@PathVariable String token, @PathVariable String did,
                            HttpServletResponse resp) throws Exception {
        ShareBoard b = boards.resolveByToken(token);
        if (b == null || b.cameras == null || !b.cameras.contains(did)) {
            resp.sendError(HttpServletResponse.SC_GONE);
            return;
        }
        writeJpeg(frames.frame(did), resp);
    }

    private void writeJpeg(byte[] jpeg, HttpServletResponse resp) throws Exception {
        resp.setContentType("image/jpeg");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setContentLength(jpeg.length);
        resp.getOutputStream().write(jpeg);
    }
}
