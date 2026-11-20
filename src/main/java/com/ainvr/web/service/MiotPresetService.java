package com.ainvr.web.service;

import com.ainvr.cloud.LoginSession;
import com.ainvr.cloud.MiCloudApi;
import com.ainvr.cloud.MiCloudUnauthorizedException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 米家云台「常看位置」预置位（MIoT fav-area）读写。
 *
 * <p>协议来源：xiaomi-onvif（SincereXIA）实测验证 + 小米 IoT 开发者平台摄像机品类模板：</p>
 * <ul>
 *   <li>读预置位列表：{@code POST /miotspec/prop/get}，
 *       {@code {"did":"...","siid":10,"piid":1}}，返回 value 为 JSON 字符串
 *       {@code [{"idx":0,"location":0,"name":"..."}]}（name 可能经 Base64 编码）；</li>
 *   <li>跳转预置位：{@code POST /miotspec/prop/set}，
 *       {@code {"did":"...","siid":10,"piid":2,"value":<location>}}（0~64）。</li>
 * </ul>
 *
 * <p>siid/piid 已验证机型 chuangmi.camera.079ac1 / 81ac1；不同固件的 siid 可能不同，
 * 可用系统属性覆盖：{@code -Dainvr.preset.siid=}、{@code -Dainvr.preset.get-piid=}、
 * {@code -Dainvr.preset.goto-piid=}。新增保存预置位涉及坐标格式（随固件变化），
 * v1 不支持——预置位请在米家 App 中设置，aiNVR 负责展示与跳转。</p>
 */
@Component
public class MiotPresetService {

    private static final Logger log = LoggerFactory.getLogger(MiotPresetService.class);

    private final SessionHolder sessionHolder;

    public MiotPresetService(SessionHolder sessionHolder) {
        this.sessionHolder = sessionHolder;
    }

    /** 预置位条目：location 为调用索引（0~64），name 为用户在米家 App 中命名的位置名。 */
    public record Preset(int location, String name) {
    }

    /** 读取该设备的预置位列表；设备无预置位/固件不支持时返回空列表。 */
    public List<Preset> list(String did) {
        JsonNode result = call("/miotspec/prop/get",
                Map.of("params", List.of(Map.of(
                        "did", did,
                        "siid", propInt("ainvr.preset.siid", 10),
                        "piid", propInt("ainvr.preset.get-piid", 1)))));
        JsonNode items = result.path("result");
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }
        JsonNode item = items.get(0);
        int code = item.path("code").asInt(-1);
        if (code != 0) {
            // 固件不支持 fav-area 时通常返回 -4006 等业务错误码：降级为空列表而非抛错
            log.info("[{}] fav-area 读取失败 code={}（机型可能不支持预置位）", did, code);
            return List.of();
        }
        String value = item.path("value").asText("");
        return parsePresetList(value);
    }

    /** 跳转到指定预置位（location 为读列表返回的索引值）。 */
    public void gotoPreset(String did, int location) {
        call("/miotspec/prop/set",
                Map.of("params", List.of(Map.of(
                        "did", did,
                        "siid", propInt("ainvr.preset.siid", 10),
                        "piid", propInt("ainvr.preset.goto-piid", 2),
                        "value", location))));
        log.info("[{}] 已调用预置位 location={}", did, location);
    }

    private JsonNode call(String api, Object params) {
        LoginSession session = sessionHolder.require();
        try {
            return new MiCloudApi(session).call(api, params);
        } catch (MiCloudUnauthorizedException e) {
            log.info("预置位米家会话过期，自动续期后重试");
            return new MiCloudApi(sessionHolder.renew()).call(api, params);
        }
    }

    /**
     * 解析 fav-area value 字符串：{@code [{"idx":0,"location":0,"name":"..."}]}。
     * name 可能为 Base64（米家云对中文位置名的编码习惯），解码成功且可打印则用解码值。
     */
    static List<Preset> parsePresetList(String value) {
        List<Preset> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode areas;
        try {
            areas = mapper.readTree(value);
        } catch (Exception e) {
            log.debug("fav-area value 非 JSON 数组（忽略）：{}", e.toString());
            return out;
        }
        if (!areas.isArray()) {
            return out;
        }
        for (JsonNode a : areas) {
            int location = a.hasNonNull("location") ? a.path("location").asInt(-1)
                    : a.path("idx").asInt(-1);
            if (location < 0 || location > 64) {
                continue;
            }
            String rawName = a.path("name").asText("");
            out.add(new Preset(location, decodeName(rawName)));
        }
        return out;
    }

    /** 位置名解码：Base64 可解且为可打印 UTF-8 时用解码值，否则保留原文。 */
    static String decodeName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            String text = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            boolean printable = !text.isEmpty() && text.chars().allMatch(c ->
                    !Character.isISOControl(c));
            if (printable) {
                return text;
            }
        } catch (IllegalArgumentException ignored) {
            // 非合法 Base64：保留原文（如 "Home" 这类恰好满足 Base64 字符集的普通名字）
        }
        return raw;
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
