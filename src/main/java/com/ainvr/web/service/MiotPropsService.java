package com.ainvr.web.service;

import com.ainvr.cloud.LoginSession;
import com.ainvr.cloud.MiCloudApi;
import com.ainvr.cloud.MiCloudUnauthorizedException;
import com.ainvr.miss.DualLens;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 米家摄像头通用属性控制（v2.3.1）：把米家 App 里的常用设备设置接进本应用。
 *
 * <p>能力来自 MIoT Spec 云端读写（{@code /miotspec/prop/get|set}，复用米家登录会话签名层），
 * 与休眠探测（{@link MiotSleepService}）、预置位（{@link MiotPresetService}）同一通道。
 * 支持的键位（siid/piid 来自小米摄像机品类模板与各型号 spec 的公共部分，
 * 可用系统属性 {@code -Dainvr.props.<key>.siid/piid=} 按机型覆盖）：</p>
 *
 * <ul>
 *   <li>{@code nightShot} 夜视模式（siid2/piid3，uint8：0 关闭 / 1 打开 / 2 自动，
 *       部分机型 0/1 语义相反，界面按「关闭/打开/自动」呈现并允许覆盖）；</li>
 *   <li>{@code imageRollover} 画面翻转（siid2/piid4，uint16：0 或 180，部分型号支持 90/270）；</li>
 *   <li>{@code timeWatermark} 时间水印（siid2/piid5，bool）；</li>
 *   <li>{@code humanTracking} 人形追踪（siid2/piid9，bool，云台款有效）；</li>
 *   <li>{@code recordingMode} 存储卡录制模式（siid2/piid11，uint8：0 不录制 / 1 录制移动画面 /
 *       2 始终录制）；</li>
 *   <li>{@code guardSwitch} 看家助手（siid5/piid1，bool）；</li>
 *   <li>{@code guardSensitivity} 移动侦测灵敏度（siid5/piid3，uint8：0 低 / 1 中 / 2 高）；</li>
 *   <li>{@code sdStatus} 存储卡状态（siid3/piid1，只读）；{@code sdFree} 剩余空间
 *       （siid3/piid3，只读字节）；{@code sdTotal} 总空间（siid3/piid2，只读字节）。</li>
 * </ul>
 *
 * <p>读取失败（code != 0，典型 -4006 = 该固件无此属性）时按「不支持」返回，不抛错——
 * 各机型属性差异大，界面按实际支持情况渲染。写操作单键单请求，失败抛业务异常。</p>
 */
@Service
public class MiotPropsService {

    private static final Logger log = LoggerFactory.getLogger(MiotPropsService.class);

    /** 可控属性键位定义（默认 siid/piid，类型）。 */
    private enum Key {
        NIGHT_SHOT("nightShot", 2, 3, "夜视模式"),
        IMAGE_ROLLOVER("imageRollover", 2, 4, "画面翻转"),
        TIME_WATERMARK("timeWatermark", 2, 5, "时间水印"),
        HUMAN_TRACKING("humanTracking", 2, 9, "人形追踪"),
        RECORDING_MODE("recordingMode", 2, 11, "存储卡录制模式"),
        GUARD_SWITCH("guardSwitch", 5, 1, "看家助手"),
        GUARD_SENSITIVITY("guardSensitivity", 5, 3, "移动侦测灵敏度"),
        SD_STATUS("sdStatus", 3, 1, "存储卡状态"),
        SD_TOTAL("sdTotal", 3, 2, "存储卡总空间"),
        SD_FREE("sdFree", 3, 3, "存储卡剩余空间");

        final String key;
        final int defSiid;
        final int defPiid;
        final String label;

        Key(String key, int defSiid, int defPiid, String label) {
            this.key = key;
            this.defSiid = defSiid;
            this.defPiid = defPiid;
            this.label = label;
        }

        static Key of(String key) {
            for (Key k : values()) {
                if (k.key.equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    private final SessionHolder sessionHolder;

    public MiotPropsService(SessionHolder sessionHolder) {
        this.sessionHolder = sessionHolder;
    }

    /**
     * 读取全部可控属性：逐键单请求（米家云对「同请求跨设备」的批量兼容性不可靠，
     * 逐项最稳；设置页打开时才调用，开销可忽略）。返回 {key: {code, value(可空), label}}。
     */
    public Map<String, Object> readAll(String did) {
        String base = DualLens.baseDid(did);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Key k : Key.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", k.label);
            try {
                JsonNode res = call("/miotspec/prop/get", Map.of("params", List.of(Map.of(
                        "did", base,
                        "siid", siid(k),
                        "piid", piid(k)))));
                JsonNode first = res.path("result").path(0);
                int code = first.path("code").asInt(-1);
                item.put("code", code);
                if (code == 0 && first.hasNonNull("value")) {
                    item.put("value", normalize(first.get("value")));
                } else {
                    item.put("value", null);
                }
            } catch (MiCloudUnauthorizedException e) {
                throw e;
            } catch (Exception e) {
                log.debug("[{}] 属性 {} 读取异常：{}", base, k.key, e.toString());
                item.put("code", -1);
                item.put("value", null);
            }
            out.put(k.key, item);
        }
        return out;
    }

    /** 写单个属性。value 类型按键位定义（uint8/uint16 用 Number，bool 用 Boolean）。 */
    public void write(String did, String key, Object value) {
        Key k = Key.of(key);
        if (k == null) {
            throw new IllegalArgumentException("不支持的属性：" + key);
        }
        if (k == Key.SD_STATUS || k == Key.SD_TOTAL || k == Key.SD_FREE) {
            throw new IllegalArgumentException("只读属性不可写：" + key);
        }
        String base = DualLens.baseDid(did);
        Map<String, Object> param = Map.of(
                "did", base,
                "siid", siid(k),
                "piid", piid(k),
                "value", value);
        try {
            call("/miotspec/prop/set", Map.of("params", List.of(param)));
        } catch (MiCloudUnauthorizedException e) {
            log.info("[{}] 属性 {} 写入米家会话过期，续期后重试", base, key);
            LoginSession session = sessionHolder.renew();
            new MiCloudApi(session).call("/miotspec/prop/set", Map.of("params", List.of(param)));
        }
        log.info("[{}] 已设置 {} = {}（MIoT siid{}.{}）", base, k.label, value, siid(k), piid(k));
    }

    /** 重启设备（camera-control 的 restart-device 动作，siid2/aiid2；机型差异可用系统属性覆盖）。 */
    public void reboot(String did) {
        String base = DualLens.baseDid(did);
        Map<String, Object> param = Map.of(
                "did", base,
                "siid", propInt("ainvr.props.reboot.siid", 2),
                "aiid", propInt("ainvr.props.reboot.aiid", 2));
        try {
            call("/action", Map.of("params", param));
        } catch (MiCloudUnauthorizedException e) {
            log.info("[{}] 重启设备米家会话过期，续期后重试", base);
            LoginSession session = sessionHolder.renew();
            new MiCloudApi(session).call("/action", Map.of("params", param));
        }
        log.info("[{}] 已下发设备重启", base);
    }

    /** value 规整为 Boolean / Number，前端按类型渲染。 */
    private static Object normalize(JsonNode v) {
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.numberValue();
        }
        return v.asText();
    }

    private int siid(Key k) {
        return propInt("ainvr.props." + k.key + ".siid", k.defSiid);
    }

    private int piid(Key k) {
        return propInt("ainvr.props." + k.key + ".piid", k.defPiid);
    }

    private JsonNode call(String api, Object params) {
        return new MiCloudApi(sessionHolder.require()).call(api, params);
    }

    private static int propInt(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
