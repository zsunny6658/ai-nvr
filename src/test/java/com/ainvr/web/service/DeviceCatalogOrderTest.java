package com.ainvr.web.service;

import com.ainvr.cloud.DeviceDiscovery;
import com.ainvr.cloud.MiCloudApi;
import com.ainvr.cloud.MiCloudUnauthorizedException;
import com.ainvr.hub.StreamHub;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.EventStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.2.21 摄像头列表顺序稳定性：云端返回顺序在两次刷新间不保证一致，
 * all() 必须按名称（中文 Collator）+ did 固定排序，否则预览 / 回放 tab 顺序漂移易点错。
 */
class DeviceCatalogOrderTest {

    private DeviceCatalog newCatalog() {
        Path dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("invr-catalog-test");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        EventStore events = new EventStore(dir);
        return new DeviceCatalog(Mockito.mock(SessionHolder.class),
                Mockito.mock(StreamHub.class), events,
                new com.ainvr.store.AppSettingsStore(dir));
    }

    private void inject(DeviceCatalog catalog, List<CameraDevice> list) {
        DeviceDiscovery discovery = Mockito.mock(DeviceDiscovery.class);
        Mockito.when(discovery.listCameras()).thenReturn(list);
        // 通过反射把 mock 的 discovery 交给 refresh 不可行（内部 new），
        // 改用直测 all() 排序逻辑：devices 字段经 refresh 赋值太重，直接构造后
        // 用 setter 等价路径——这里用反射写 devices 字段（测试专用）。
        try {
            var f = DeviceCatalog.class.getDeclaredField("devices");
            f.setAccessible(true);
            f.set(catalog, list);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void allSortsByNameThenDid() {
        DeviceCatalog catalog = newCatalog();
        inject(catalog, List.of(
                new CameraDevice("d3", "书房", "m", "", "", true),
                new CameraDevice("d2", "客厅", "m", "", "", true),
                new CameraDevice("d1", "客厅", "m", "", "", true),
                new CameraDevice("d0", "阳台", "m", "", "", true)));
        List<CameraDevice> sorted = catalog.all();
        // 中文按拼音 Collator：客(k) < 书(s) < 阳(y)
        assertEquals(List.of("客厅", "客厅", "书房", "阳台"),
                sorted.stream().map(CameraDevice::name).toList());
        assertEquals("d1", sorted.get(0).did()); // 同名按 did 升序
        assertEquals("d2", sorted.get(1).did());
    }

    @Test
    void allIsStableAcrossCloudOrderShuffles() {
        DeviceCatalog catalog = newCatalog();
        List<CameraDevice> a = List.of(
                new CameraDevice("d1", "客厅", "m", "", "", true),
                new CameraDevice("d2", "书房", "m", "", "", true));
        List<CameraDevice> b = List.of(
                new CameraDevice("d2", "书房", "m", "", "", true),
                new CameraDevice("d1", "客厅", "m", "", "", true));
        inject(catalog, a);
        List<String> first = catalog.all().stream().map(CameraDevice::did).toList();
        inject(catalog, b);
        List<String> second = catalog.all().stream().map(CameraDevice::did).toList();
        assertEquals(first, second);
        assertEquals(List.of("d1", "d2"), first); // 客厅 < 书房（拼音 ke < shu）
    }

    @Test
    void allKeepsRawListForEmptyAndSingle() {
        DeviceCatalog catalog = newCatalog();
        inject(catalog, List.of());
        assertTrue(catalog.all().isEmpty());
        inject(catalog, List.of(new CameraDevice("d1", "客厅", "m", "", "", true)));
        assertEquals(1, catalog.all().size());
    }
}
