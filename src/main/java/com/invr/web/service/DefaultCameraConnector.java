package com.invr.web.service;

import com.invr.cloud.CloudMissOpener;
import com.invr.hub.CameraConnector;
import com.invr.hub.MissOpener;
import com.invr.model.CameraDevice;
import com.invr.store.AppSettingsStore;
import com.invr.store.SessionStore;
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
        // 每次重连都从设备目录取最新对象：设备重新上线后云端 localip 可能变化，
        // 若沿用订阅建立时的旧 CameraDevice，会把取流打到旧 IP 上永远连不通。
        Supplier<CameraDevice> freshDevice = () -> catalog.find(device.did()).orElse(device);
        // 档位从 Web 设置动态读取，保存后立即重连即可生效
        Supplier<String> quality = () -> settingsStore.qualityFor(device.did());
        return new CloudMissOpener(sessionStore, sessionHolder.ref(), freshDevice, "", quality, "1");
    }
}
