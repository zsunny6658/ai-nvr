package com.ainvr.web.service;

import com.ainvr.cloud.CloudMissOpener;
import com.ainvr.hub.CameraConnector;
import com.ainvr.hub.MissOpener;
import com.ainvr.miss.DualLens;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.SessionStore;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 所有下行消费者（RTSP / HLS / 录像）共用的摄像头连接器：
 * 按 DID 解析设备、并为设备构造汇入全局 StreamHub 的上游开启器。
 */
@Component
public class DefaultCameraConnector implements CameraConnector {

    private final SessionStore sessionStore;
    private final SessionHolder sessionHolder;
    private final DeviceCatalog catalog;
    private final AppSettingsStore settingsStore;

    public DefaultCameraConnector(SessionStore sessionStore, SessionHolder sessionHolder,
                                  DeviceCatalog catalog, AppSettingsStore settingsStore) {
        this.sessionStore = sessionStore;
        this.sessionHolder = sessionHolder;
        this.catalog = catalog;
        this.settingsStore = settingsStore;
    }

    @Override
    public CameraDevice findDevice(String did) {
        return catalog.findOrRefresh(did);
    }

    @Override
    public MissOpener openerFor(CameraDevice device) {
        // 双摄第二镜头：虚拟 DID 后缀 #2 → VideoStart 用 videoquality2 载荷取第二镜头
        String baseDid = DualLens.baseDid(device.did());
        String channel = DualLens.isSecondary(device.did()) ? "2" : "";
        // 每次重连都从设备目录取最新对象：设备重新上线后云端 localip 可能变化，
        // 若沿用订阅建立时的旧 CameraDevice，会把取流打到旧 IP 上永远连不通。
        // 注意必须解析回真实设备（CloudMissOpener 用其 did/localip 建立云端会话）。
        Supplier<CameraDevice> freshDevice = () ->
                catalog.find(baseDid).orElseGet(() -> withBaseDid(device));
        // 档位从 Web 设置动态读取，保存后立即重连即可生效
        Supplier<String> quality = () -> settingsStore.qualityFor(baseDid);
        return new CloudMissOpener(sessionStore, sessionHolder.ref(), freshDevice, channel, quality, "1");
    }

    /** 目录里暂时找不到真实设备时（刚登录 / 列表未拉取），就地剥离 #2 后缀兜底。 */
    private static CameraDevice withBaseDid(CameraDevice c) {
        if (!DualLens.isSecondary(c.did())) {
            return c;
        }
        return new CameraDevice(DualLens.baseDid(c.did()), c.name(), c.model(),
                c.mac(), c.localIp(), c.online());
    }
}
