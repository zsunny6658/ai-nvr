/* iNvr 共享大屏页：多宫格渲染、多实例 HLS 播放、事件 SSE 与无人值守重连 */
(function () {
    'use strict';

    var share = window.__SHARE;
    var ROOT = share.root;
    var TOKEN = share.token;

    /* 轮询与兜底周期（秒）：内置默认值，拉取看板配置后用服务端生效值覆盖（设置页可配） */
    var CONFIG_POLL_SEC = 60;
    var PAGE_RELOAD_SEC = 3600;

    var board = null;
    /* slotKey -> 播放器记录 {slot, did, cell, video, hls, hlsUrl, online} */
    var players = new Map();
    /* 当前事件态 {did, expireAt, timer, swappedSlot} */
    var eventState = null;
    /* 全屏覆盖层播放器记录 */
    var fullscreenRec = null;
    var fullscreenFirstFrameTimer = null;
    var evtSource = null;

    function full(path) { return ROOT + path; }

    /* ===== 移动端 / 飞牛 App WebView：HEVC 兼容画面 =====
       共享页外网打开时若 H.265 解不了（或分片拉不下来），改走免鉴权的
       /share/{token}/frame/{did} JPEG 连续帧，<img> 即可出画面。
       v1.2.4：能力探测只作 Chromium+MSE 的快速预降级，原生 HLS 设备先试 HLS。 */
    function hevcSupported() {
        try {
            var codecs = ['hvc1.1.6.L93.B0', 'hev1.1.6.L93.B0', 'hvc1.1.6.L153.B0'];
            var probe = document.createElement('video');
            for (var i = 0; i < codecs.length; i++) {
                var mime = 'video/mp4; codecs="' + codecs[i] + '"';
                if (window.MediaSource && window.MediaSource.isTypeSupported && window.MediaSource.isTypeSupported(mime)) { return true; }
                if (probe.canPlayType(mime)) { return true; }
            }
            return false;
        } catch (e) { return false; }
    }

    /* 原生 HLS（iOS Safari / 飞牛 App WKWebView）。实测局域网内 iPhone 用飞牛 App
       能看到 H.265 画面，且 WKWebView 对 MP4/hvc1 的 canPlayType 探测可能返回空，
       因此不能用该探测结果把它预降级成低帧率 JPEG。 */
    function nativeHlsSupported() {
        try {
            var probe = document.createElement('video');
            return !!probe.canPlayType('application/vnd.apple.mpegurl');
        } catch (e) { return false; }
    }

    function wantsCompat() {
        if (nativeHlsSupported()) { return false; }
        return !hevcSupported();
    }
    function inlineVideo(v) {
        v.muted = true; v.autoplay = true; v.playsInline = true;
        v.setAttribute('playsinline', '');
        v.setAttribute('webkit-playsinline', '');
        v.setAttribute('x5-playsinline', '');
        v.setAttribute('x5-video-player-type', 'h5');
        v.setAttribute('x5-video-player-fullscreen', 'true');
    }
    function findFrameUrl(did) {
        for (var i = 0; i < board.slots.length; i++) {
            if (board.slots[i].did === did) { return board.slots[i].frameUrl || null; }
        }
        return null;
    }
    function startCompatImage(container, frameUrl) {
        var img = document.createElement('img');
        img.className = 'cell-compat';
        img.alt = '实时画面（兼容模式）';
        container.appendChild(img);
        var tag = document.createElement('div');
        tag.className = 'compat-tag';
        tag.textContent = '兼容画面 · 低帧率 / 无声音';
        container.appendChild(tag);
        var n = 0, stopped = false;
        img.onload = function () { /* 重置失败计数由服务端的下一帧自然恢复 */ };
        function tick() {
            if (stopped) { return; }
            img.src = full(frameUrl) + '?_=' + Date.now() + '_' + (++n);
        }
        tick();
        var timer = setInterval(tick, 450);
        return {
            stop: function () {
                stopped = true;
                clearInterval(timer);
                img.onload = img.onerror = null;
                img.remove();
                tag.remove();
            }
        };
    }

    /* ===================== 配置拉取 ===================== */

    async function fetchBoard() {
        var res = await fetch(full('share/' + TOKEN + '/board'), { cache: 'no-store' });
        if (res.status === 410) {
            fatal('共享看板不可用、已过期或已停用');
            return null;
        }
        if (!res.ok) {
            throw new Error('HTTP ' + res.status);
        }
        return res.json();
    }

    /* ===================== 渲染 ===================== */

    function render(b) {
        var boardEl = document.getElementById('board');
        boardEl.className = 'board'
            + (b.layout === 'grid4' ? ' layout-grid4' : '')
            + (b.layout === 'single' ? ' layout-single' : '');
        boardEl.innerHTML = '';
        b.slots.forEach(function (s) {
            var cell = document.createElement('div');
            cell.className = 'cell';
            cell.dataset.slot = s.slot;
            cell.style.gridColumn = s.colStart + ' / span ' + s.colSpan;
            cell.style.gridRow = s.rowStart + ' / span ' + s.rowSpan;
            boardEl.appendChild(cell);

            cell.appendChild(buildBar(s));
            startPlayer(cell, s);
        });
        boardEl.hidden = false;
        document.getElementById('boot').hidden = true;
    }

    function buildBar(s) {
        var bar = document.createElement('div');
        bar.className = 'cell-bar';
        var tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = s.slot === 'BIG' ? '主' : s.slot;
        var name = document.createElement('span');
        name.textContent = s.cameraName;
        bar.appendChild(tag);
        bar.appendChild(name);
        return bar;
    }

    /* ===================== 网格播放器 ===================== */

    function startPlayer(cell, s) {
        /* 空置槽位：不创建播放器，只显示空置占位，并保留记录以便交换/轮询处理 */
        if (!s.did) {
            showPlaceholder(cell, '空置', 'empty');
            players.set(s.slot, { slot: s.slot, did: null, cell: cell, video: null,
                hls: null, hlsUrl: null, online: false, empty: true });
            return;
        }

        showPlaceholder(cell, '正在连接…', 'loading');

        var video = document.createElement('video');
        inlineVideo(video);
        cell.appendChild(video);

        var rec = { slot: s.slot, did: s.did, cell: cell, video: video, hls: null,
            hlsUrl: s.hlsUrl, frameUrl: s.frameUrl, online: s.online,
            compat: null, empty: false };
        players.set(s.slot, rec);

        if (!s.online) {
            showPlaceholder(cell, '设备离线', 'offline');
            return;
        }
        if (wantsCompat() && s.frameUrl) {
            enterCompat(rec);
            return;
        }
        attachHls(rec);
    }

    function clearFirstFrame(rec) {
        if (rec && rec.firstFrameTimer) {
            clearTimeout(rec.firstFrameTimer);
            rec.firstFrameTimer = null;
        }
    }

    /* 统一切到兼容帧：能力预降级与「HLS 实际播不出来」降级共用，先彻底释放 HLS 会话。 */
    function enterCompat(rec) {
        if (!rec || !rec.frameUrl || rec.compat) { return false; }
        stopWatchdog(rec);
        clearFirstFrame(rec);
        if (rec.hls) { try { rec.hls.destroy(); } catch (e) {} rec.hls = null; }
        if (rec.video) {
            try { rec.video.pause(); rec.video.removeAttribute('src'); rec.video.load(); } catch (e) {}
            rec.video.hidden = true;
        }
        clearPlaceholder(rec.cell);
        rec.compat = startCompatImage(rec.cell, rec.frameUrl);
        return true;
    }

    function attachHls(rec) {
        stopWatchdog(rec);
        clearFirstFrame(rec);
        if (rec.compat) { return; }
        rec.hls = mountHls(rec.video, full(rec.hlsUrl), function () {
            scheduleRetry(rec);
        });
        if (rec.hls === undefined) {
            if (enterCompat(rec)) { return; }
            showPlaceholder(rec.cell, '当前浏览器不支持 HLS 播放', 'error');
            return;
        }
        rec.video.addEventListener('playing', function () {
            clearFirstFrame(rec);
            if (rec.online) { clearPlaceholder(rec.cell); }
        });
        rec.video.addEventListener('loadeddata', function () { clearFirstFrame(rec); }, { once: true });
        // 看门狗：停滞/错误/画面冻结/墙钟延迟过大时自动处理（video 元素不变，仅重建播放会话）。
        // getLatency/seekLive 闭包引用 rec，remount 后 rec.hls 更新为新实例，回调始终读到当前会话。
        rec.wd = Watchdog.start(rec.video, function () { remountRec(rec); }, {
            getLatency: function () {
                return rec.hls ? rec.hls.latency : null;
            },
            seekLive: function () {
                if (rec.hls && isFinite(rec.hls.liveSyncPosition)) {
                    rec.video.currentTime = rec.hls.liveSyncPosition;
                }
            }
        });
        rec.video.getWatchdogState = rec.wd.getState; // 可观测性：便于 CDP 验收与运维排查
        /* v1.2.4：外网（域名/中继）访问时，原生 HLS 播放器可能一直拉不到 fMP4 分片，
           界面会永久停在「正在连接…」。20 秒仍无首帧就切兼容帧，保证 iPhone 外网也有画面。 */
        rec.firstFrameTimer = setTimeout(function () {
            rec.firstFrameTimer = null;
            if (rec.compat) { return; }
            if (!rec.video.videoWidth || rec.video.readyState < 2) { enterCompat(rec); }
        }, 20000);
    }

    function stopWatchdog(rec) {
        if (rec.wd) { rec.wd.stop(); rec.wd = null; }
    }

    /* 看门狗回调：销毁旧播放会话并清理 video，再重新挂载（实测可 100% 恢复 err=4 卡死） */
    function remountRec(rec) {
        if (rec.hls) {
            try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
            rec.hls = null;
        }
        if (rec.video) {
            rec.video.pause();
            rec.video.removeAttribute('src');
            rec.video.load();
        }
        attachHls(rec);
    }

    function destroyPlayer(rec) {
        stopWatchdog(rec);
        clearFirstFrame(rec);
        if (rec.compat) { rec.compat.stop(); rec.compat = null; }
        if (rec.hls) {
            try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
        }
        if (rec.video) {
            rec.video.pause();
            rec.video.removeAttribute('src');
            rec.video.load();
        }
        players.delete(rec.slot);
    }

    function scheduleRetry(rec) {
        rec.retries = (rec.retries || 0) + 1;
        // MSE 反复 fatal 时不再无限重试，直接落到兼容帧（外网分片异常的主要表现）
        if (rec.retries >= 3 && enterCompat(rec)) { return; }
        showPlaceholder(rec.cell, '取流失败，重试中…', 'error');
        setTimeout(function () {
            if (rec.hls) {
                try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
                rec.hls = null;
            }
            attachHls(rec);
        }, 5000);
    }

    /* ===================== 通用 HLS 挂载（网格/全屏复用） ===================== */

    /* Chromium 内核（Edge/Chrome/内置浏览器）：其原生 MediaFoundation HLS 在长期运行遇停滞时
       会产生缓冲空洞且无法自愈，故优先 hls.js(MSE)；Safari 等保留原生优先。 */
    function isChromium() {
        return /Chrome\//.test(navigator.userAgent);
    }

    function mountMse(video, src, onFatal) {
        // 低延迟直播调优：同步点 2 分片、超 4 分片即 seek 回 live edge、轻微落后时加速追赶；
        // 冷启动若因 3.5K 软解慢错过 live edge 锚定，最大延迟追帧会把播放位置拉回，避免固定 50s+ 延迟。
        var hls = new Hls({
            liveSyncDurationCount: 2,
            liveMaxLatencyDurationCount: 4,
            maxLiveSyncPlaybackRate: 1.3,
            lowLatencyMode: true,
            startPosition: -1,
            backBufferLength: 30,
            maxBufferLength: 10,
            maxMaxBufferLength: 30,
            enableWorker: true
        });
        hls.loadSource(src);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, function () {
            video.play().catch(function () {});
        });
        hls.on(Hls.Events.ERROR, function (evt, data) {
            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                hls.startLoad();
            } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                try { hls.recoverMediaError(); }
                catch (e) { if (onFatal) { onFatal(); } }
            }
        });
        return hls;
    }

    function mountHls(video, src, onFatal) {
        // Chromium 且支持 MSE：优先 hls.js，从根上规避原生 MediaFoundation 卡死
        if (window.Hls && Hls.isSupported() && isChromium()) {
            return mountMse(video, src, onFatal);
        }
        // 原生路径（Safari 等的常规选择）
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = src;
            video.play().catch(function () {});
            return null;
        }
        // 不支持原生但支持 MSE：仍用 hls.js 兜底
        if (window.Hls && Hls.isSupported()) {
            return mountMse(video, src, onFatal);
        }
        return undefined;
    }

    function findHlsUrl(did) {
        for (var i = 0; i < board.slots.length; i++) {
            if (board.slots[i].did === did) {
                return full(board.slots[i].hlsUrl);
            }
        }
        return null;
    }

    /* ===================== 占位 ===================== */

    function showPlaceholder(cell, text, kind) {
        clearPlaceholder(cell);
        var p = document.createElement('div');
        p.className = 'cell-placeholder ' + (kind || '');
        var ico = kind === 'offline' ? 'OFF' : kind === 'error' ? '!' : '…';
        p.innerHTML = '<span class="ico">' + ico + '</span><span>' + text + '</span>';
        cell.appendChild(p);
    }

    function clearPlaceholder(cell) {
        var p = cell.querySelector('.cell-placeholder');
        if (p) { p.remove(); }
    }

    /* ===================== 事件 SSE ===================== */

    function connectSSE() {
        if (!board.eventsUrl) {
            return;
        }
        evtSource = new EventSource(full(board.eventsUrl));
        evtSource.onmessage = function (e) {
            try {
                handleMessage(JSON.parse(e.data));
            } catch (err) { /* 忽略坏消息 */ }
        };
        evtSource.onopen = function () { hideNotice(); };
        evtSource.onerror = function () {
            showNotice('事件连接中断，正在自动重连…', 'info');
        };
    }

    function handleMessage(msg) {
        if (msg.type === 'snapshot') {
            if (msg.active) {
                applySwitch(msg.did, msg.expireAt);
            } else {
                clearEvent();
            }
            hideNotice();
        } else if (msg.type === 'switch') {
            applySwitch(msg.did, msg.expireAt);
        } else if (msg.type === 'end') {
            clearEvent();
        }
    }

    /* ===================== 事件切换（基于默认布局） ===================== */

    function applySwitch(did, expireAt) {
        /* 先还原旧事件，保证永远基于默认布局单次操作 */
        revertEvent();

        var swappedSlot = null;
        if (board.layout === 'grid6') {
            swappedSlot = swapForGrid6(did);
        } else {
            showFullscreen(did);
        }

        var delay = Math.max(1000, expireAt - Date.now());
        var timer = setTimeout(localEnd, delay);
        eventState = { did: did, expireAt: expireAt, timer: timer, swappedSlot: swappedSlot };
    }

    /* 还原当前事件：grid6 归位、其余销毁全屏，并清计时器 */
    function revertEvent() {
        if (!eventState) {
            return;
        }
        clearTimeout(eventState.timer);
        if (board.layout === 'grid6') {
            if (eventState.swappedSlot) {
                swapCells('BIG', eventState.swappedSlot);
            }
        } else {
            destroyFullscreen();
        }
        eventState = null;
    }

    function clearEvent() {
        revertEvent();
    }

    function localEnd() {
        clearEvent();
    }

    /* ===================== grid6 单次交换 ===================== */

    function swapForGrid6(did) {
        var targetSlot = null;
        players.forEach(function (rec, key) {
            if (rec.did === did) {
                targetSlot = key;
            }
        });
        if (targetSlot === null || targetSlot === 'BIG') {
            /* 找不到或已在大格：无需交换 */
            return null;
        }
        swapCells('BIG', targetSlot);
        return targetSlot;
    }

    function swapCells(keyA, keyB) {
        var recA = players.get(keyA);
        var recB = players.get(keyB);
        var cellA = recA.cell;
        var cellB = recB.cell;

        var nodesA = Array.prototype.slice.call(cellA.childNodes);
        var nodesB = Array.prototype.slice.call(cellB.childNodes);
        nodesA.forEach(function (n) { cellB.appendChild(n); });
        nodesB.forEach(function (n) { cellA.appendChild(n); });

        /* 播放器随 video 移动，更新容器引用 */
        recA.cell = cellB;
        recB.cell = cellA;
    }

    /* ===================== grid4/9 全屏覆盖层 ===================== */

    function clearFullscreenFirstFrame() {
        if (fullscreenFirstFrameTimer) {
            clearTimeout(fullscreenFirstFrameTimer);
            fullscreenFirstFrameTimer = null;
        }
    }

    function fullscreenToCompat() {
        var rec = fullscreenRec;
        if (!rec || !rec.frameUrl || rec.compat) { return; }
        clearFullscreenFirstFrame();
        if (rec.wd) { rec.wd.stop(); rec.wd = null; }
        if (rec.hls) { try { rec.hls.destroy(); } catch (e) {} rec.hls = null; }
        rec.video.pause();
        rec.video.removeAttribute('src');
        rec.video.load();
        rec.video.hidden = true;
        rec.compat = startCompatImage(document.getElementById('fullscreenLayer'), rec.frameUrl);
    }

    function showFullscreen(did) {
        var layer = document.getElementById('fullscreenLayer');
        clearFullscreenFirstFrame();
        layer.innerHTML = '';

        var video = document.createElement('video');
        inlineVideo(video);
        layer.appendChild(video);

        var bar = document.createElement('div');
        bar.className = 'cell-bar';
        var tag = document.createElement('span');
        tag.className = 'tag event';
        tag.textContent = '事件';
        var name = document.createElement('span');
        name.textContent = cameraName(did);
        bar.appendChild(tag);
        bar.appendChild(name);
        layer.appendChild(bar);

        var frameUrl = findFrameUrl(did);
        if (wantsCompat() && frameUrl) {
            video.hidden = true;
            var compat = startCompatImage(layer, frameUrl);
            fullscreenRec = { did: did, video: video, hls: null, wd: null, compat: compat };
            layer.hidden = false;
            return;
        }
        var src = findHlsUrl(did);
        var hls = null;
        var wd = null;
        if (src) {
            hls = mountHls(video, src, null);
            if (hls !== undefined) {
                var holder = { hls: hls };
                wd = Watchdog.start(video, function () { remountFullscreen(did); }, {
                    getLatency: function () { return holder.hls ? holder.hls.latency : null; },
                    seekLive: function () {
                        if (holder.hls && isFinite(holder.hls.liveSyncPosition)) {
                            video.currentTime = holder.hls.liveSyncPosition;
                        }
                    }
                });
                video.getWatchdogState = wd.getState;
            }
        }
        fullscreenRec = { did: did, video: video, hls: hls, wd: wd, frameUrl: frameUrl };
        layer.hidden = false;
        video.addEventListener('playing', clearFullscreenFirstFrame);
        video.addEventListener('loadeddata', clearFullscreenFirstFrame, { once: true });
        if (hls === undefined && frameUrl) { fullscreenToCompat(); return; }
        if (src && hls !== undefined) {
            /* v1.2.4：外网拉不到分片时原生 HLS 会一直卡住，20s 无首帧则切兼容帧 */
            fullscreenFirstFrameTimer = setTimeout(function () {
                fullscreenFirstFrameTimer = null;
                if (!video.videoWidth || video.readyState < 2) { fullscreenToCompat(); }
            }, 20000);
        }
    }

    /* 全屏看门狗回调：销毁旧会话后重新挂载，video 元素保持不变 */
    function remountFullscreen(did) {
        var rec = fullscreenRec;
        if (!rec) { return; }
        if (rec.wd) { rec.wd.stop(); rec.wd = null; }
        if (rec.hls) {
            try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
            rec.hls = null;
        }
        rec.video.pause();
        rec.video.removeAttribute('src');
        rec.video.load();
        var src = findHlsUrl(did);
        if (src) {
            rec.hls = mountHls(rec.video, src, null);
            if (rec.hls !== undefined) {
                rec.wd = Watchdog.start(rec.video, function () { remountFullscreen(did); });
                rec.video.getWatchdogState = rec.wd.getState;
            }
        }
    }

    function destroyFullscreen() {
        clearFullscreenFirstFrame();
        if (fullscreenRec) {
            if (fullscreenRec.compat) { fullscreenRec.compat.stop(); }
            if (fullscreenRec.wd) { fullscreenRec.wd.stop(); }
            if (fullscreenRec.hls) {
                try { fullscreenRec.hls.destroy(); } catch (e) { /* 忽略 */ }
            }
        }
        var layer = document.getElementById('fullscreenLayer');
        layer.hidden = true;
        layer.innerHTML = '';
        fullscreenRec = null;
    }

    function cameraName(did) {
        for (var i = 0; i < board.slots.length; i++) {
            if (board.slots[i].did === did) {
                return board.slots[i].cameraName;
            }
        }
        return did;
    }

    /* ===================== 状态条 ===================== */

    function showNotice(text, kind) {
        var n = document.getElementById('notice');
        n.textContent = text;
        n.className = kind === 'info' ? 'notice info' : 'notice';
        n.hidden = false;
    }

    function hideNotice() {
        document.getElementById('notice').hidden = true;
    }

    /* ===================== 轮询 reconcile ===================== */

    async function reconcile() {
        try {
            var b = await fetchBoard();
            if (!b) {
                return;
            }
            board = b;
            b.slots.forEach(function (s) {
                var rec = players.get(s.slot);
                if (!rec) {
                    var cell = document.querySelector('.cell[data-slot="' + CSS.escape(s.slot) + '"]');
                    if (cell) { startPlayer(cell, s); }
                    return;
                }
                if (rec.online !== s.online) {
                    rec.online = s.online;
                    if (s.online) {
                        rec.hlsUrl = s.hlsUrl;
                        attachHls(rec);
                    } else {
                        stopWatchdog(rec);
                        if (rec.hls) {
                            try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
                            rec.hls = null;
                        }
                        rec.video.pause();
                        showPlaceholder(rec.cell, '设备离线', 'offline');
                    }
                }
                /* 事件进行中跳过 did 替换，避免破坏交换，待 end 后下个周期处理 */
                if (!eventState && rec.did !== s.did) {
                    destroyPlayer(rec);
                    var target = rec.cell;
                    target.querySelector('.cell-bar').remove();
                    target.appendChild(buildBar(s));
                    startPlayer(target, s);
                }
            });
        } catch (e) {
            /* 轮询失败保留现状，下个周期再试 */
        }
    }

    /* ===================== 通用 ===================== */

    function fatal(msg) {
        var boot = document.getElementById('boot');
        boot.textContent = msg;
        boot.hidden = false;
        document.getElementById('board').hidden = true;
    }

    /* ===================== 启动 ===================== */

    async function init() {
        try {
            var b = await fetchBoard();
            if (!b) {
                return;
            }
            board = b;
            // 用服务端生效的周期覆盖内置默认（pageReloadSec=0 表示关闭兜底重载）
            if (b.configPollSec > 0) { CONFIG_POLL_SEC = b.configPollSec; }
            PAGE_RELOAD_SEC = b.pageReloadSec > 0 ? b.pageReloadSec : 0;
            render(b);
            connectSSE();

            setInterval(reconcile, CONFIG_POLL_SEC * 1000);
            /* 兜底重载：防止长期运行内存膨胀/假死；周期为 0 时不设定时器 */
            if (PAGE_RELOAD_SEC > 0) {
                setTimeout(function () { location.reload(); }, PAGE_RELOAD_SEC * 1000);
            }
        } catch (e) {
            document.getElementById('boot').textContent = '加载失败，自动重试…';
            setTimeout(function () { location.reload(); }, 5000);
        }
    }

    init();
})();
