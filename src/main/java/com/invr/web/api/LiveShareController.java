package com.invr.web.api;

import com.invr.model.CameraDevice;
import com.invr.model.ShareBoard;
import com.invr.web.service.DeviceCatalog;
import com.invr.web.service.ShareBoardService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「外网可播链接」：为单个摄像头创建/复用一张临时单画面共享看板。
 *
 * <p>背景：预览弹窗原来只给出 {@code rtsp://<host>:9554/<did>}。RTSP 是应用自行监听的
 * 独立 TCP 端口，fnOS 统一网关只反代 HTTP/WS；一旦从公网域名（如 fnos.zsunny.top）访问，
 * 未做 9554 端口映射就完全连不上。这里改为复用现成的免鉴权共享链路：
 * {@code /share/{token}} → {@code /share/{token}/hls/...}，全部走同一 HTTPS 端口与网关前缀，
 * 无需路由器端口映射，也无需在客户端安装播放器。</p>
 *
 * <p>链接默认 24 小时有效，可随时通过 {@code DELETE /api/live-share/{id}} 撤销；同一摄像头
 * 在有效期内重复点击会复用同一张看板，不会不断堆积。看板会出现在「共享看板」列表中，
 * 也可在那里删除或停用。本接口位于 {@code /api/**}，受常规门禁保护。</p>
 */
@RestController
@RequestMapping("/api/live-share")
public class LiveShareController {

    /** 临时链接有效期：24 小时。 */
    public static final long TTL_MS = 24L * 60 * 60 * 1000;
    /** 剩余有效期短于该值时不再复用，避免刚复制就过期。 */
    private static final long MIN_REUSE_MS = 60_000L;
    private static final String NAME_PREFIX = "临时预览 · ";

    private final ShareBoardService boards;
    private final DeviceCatalog catalog;

    public LiveShareController(ShareBoardService boards, DeviceCatalog catalog) {
        this.boards = boards;
        this.catalog = catalog;
    }

    /** 取（或创建）指定摄像头的外网可播链接；返回相对站点的路径，由前端按 baseURI 拼绝对地址。 */
    @PostMapping("/{did}")
    public Map<String, Object> create(@PathVariable String did) {
        CameraDevice cam = catalog.find(did)
                .orElseThrow(() -> ApiException.notFound("设备列表中未找到 did=" + did));

        long now = System.currentTimeMillis();
        for (ShareBoard b : boards.list()) {
            if (!ShareBoard.LAYOUT_SINGLE.equals(b.layout)
                    || b.name == null || !b.name.startsWith(NAME_PREFIX)
                    || b.cameras == null || b.cameras.size() != 1
                    || !did.equals(b.cameras.get(0))
                    || b.expireAt == null || b.expireAt - now < MIN_REUSE_MS
                    || !boards.isAvailable(b)) {
                continue;
            }
            return describe(b, true);
        }

        ShareBoard b = boards.create(NAME_PREFIX + cam.name(), ShareBoard.LAYOUT_SINGLE,
                List.of(did), now + TTL_MS);
        return describe(b, false);
    }

    /** 列出该摄像头当前有效的临时链接（供界面显示状态）。 */
    @GetMapping("/{did}")
    public Map<String, Object> status(@PathVariable String did) {
        long now = System.currentTimeMillis();
        for (ShareBoard b : boards.list()) {
            if (ShareBoard.LAYOUT_SINGLE.equals(b.layout)
                    && b.cameras != null && b.cameras.size() == 1
                    && did.equals(b.cameras.get(0))
                    && b.expireAt != null && b.expireAt - now >= MIN_REUSE_MS
                    && boards.isAvailable(b)) {
                return describe(b, true);
            }
        }
        return Map.of("active", false);
    }

    /** 立即撤销链接（删除临时看板）。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@PathVariable String id) {
        ShareBoard b = boards.get(id);
        if (!ShareBoard.LAYOUT_SINGLE.equals(b.layout)) {
            throw ApiException.badRequest("只能撤销临时预览链接");
        }
        boards.delete(id);
        return Map.of("revoked", id);
    }

    private Map<String, Object> describe(ShareBoard b, boolean reused) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", true);
        m.put("id", b.id);
        m.put("token", b.shareToken);
        // 相对路径：配合 <base href="./">，直连根路径与 fnOS 网关 /app/{appname}/ 下都正确
        m.put("path", "share/" + b.shareToken);
        m.put("name", b.name);
        m.put("expireAt", b.expireAt);
        m.put("reused", reused);
        return m;
    }
}
