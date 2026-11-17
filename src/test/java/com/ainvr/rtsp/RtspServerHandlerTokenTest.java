package com.ainvr.rtsp;

import com.ainvr.hub.CameraConnector;
import com.ainvr.hub.MissOpener;
import com.ainvr.hub.StreamHub;
import com.ainvr.model.CameraDevice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.2.22 RTSP 访问令牌鉴权：{@code ?t=} 匹配、恒定时间比较与运行时换令牌（supplier）语义。
 * 直接反射调用 {@code tokenAllowed}，无需起 Netty 服务。
 */
class RtspServerHandlerTokenTest {

    private static Method tokenMethod(RtspServerHandler h) throws Exception {
        Method m = RtspServerHandler.class.getDeclaredMethod("tokenAllowed", RtspRequest.class);
        m.setAccessible(true);
        return m;
    }

    /** 反射走 supplier 构造器（package-private 语义一致），hub/connector 传 null 即可。 */
    private static RtspServerHandler newHandler(java.util.function.Supplier<String> supplier) throws Exception {
        Constructor<RtspServerHandler> c = RtspServerHandler.class.getDeclaredConstructor(
                StreamHub.class, CameraConnector.class, java.util.function.Supplier.class);
        c.setAccessible(true);
        return c.newInstance(null, null, supplier);
    }

    @Test
    void noTokenMeansAllowed() throws Exception {
        RtspServerHandler h = newHandler(() -> "");
        assertTrue((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1")));
    }

    @Test
    void tokenRequiredAndMatched() throws Exception {
        RtspServerHandler h = newHandler(() -> "secret");
        assertFalse((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1")));
        assertFalse((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1?t=wrong")));
        assertTrue((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1?t=secret")));
        // 带其他参数也不影响
        assertTrue((boolean) tokenMethod(h).invoke(h,
                new RtspRequest("DESCRIBE", "rtsp://host:8554/d1?x=1&t=secret")));
    }

    /** 运行时换令牌（设置页保存）：supplier 语义下新值立即生效，旧令牌随即失效。 */
    @Test
    void tokenHotSwapViaSupplier() throws Exception {
        AtomicReference<String> tok = new AtomicReference<>("v1");
        RtspServerHandler h = newHandler(tok::get);
        assertTrue((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1?t=v1")));
        tok.set("v2");
        assertFalse((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1?t=v1")));
        assertTrue((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1?t=v2")));
        // 清除令牌后恢复不鉴权
        tok.set(null);
        assertTrue((boolean) tokenMethod(h).invoke(h, new RtspRequest("DESCRIBE", "rtsp://host:8554/d1")));
    }

    /** RtspServer.setToken/getToken：空串 / 空白归一为 null（不鉴权）。 */
    @Test
    void rtspServerTokenNormalization() {
        CameraConnector noop = new CameraConnector() {
            @Override public CameraDevice findDevice(String did) { return null; }
            @Override public MissOpener openerFor(CameraDevice device) { return null; }
        };
        RtspServer server = new RtspServer(8554, new StreamHub(), noop, "abc");
        assertEquals("abc", server.getToken());
        server.setToken("  ");
        assertNull(server.getToken());
        server.setToken("xyz");
        assertEquals("xyz", server.getToken());
        server.setToken(null);
        assertNull(server.getToken());
    }
}
