/* aiNVR Web 单页逻辑：登录分步交互、设备、HLS 预览、录像管理 */
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    var state = {
        errLog: [], // v2.3.1 前端错误环形记录（上报 Issue 时随诊断报告带上）
        deviceFilter: 'all', // v2.3.1 摄像头筛选：all / online / sleeping / offline
        loggedIn: false,
        userId: null,
        region: null,
        accessConfigured: false,
        unlocked: false,
        loginToken: null,
        devices: [],
        boards: [],
        boardEditId: null,
        hls: null,
        previewDid: null,
        pollTimer: null,
        // v1.2.7：首页概览 30 秒刷新
        dashTimer: null,
        gateTimer: null,
        recoveredOnce: false,
        // v1.2.1：延迟徽标定时器 / 云台点击序号（避免旧的异步应答覆盖新状态）
        liveBadgeTimer: null,
        ptzSeq: 0,
        // v1.2.3：兼容画面轮询 / 自动降级 / 外网临时链接
        compatTimer: null, compatSeq: 0, compatFails: 0, forceCompat: false,
        liveShare: null,
        // v1.2.2：录像文件页批量选择（name 集合 + 当前列表 name→size，用于显示选中容量）
        recSelected: new Set(),
        recRows: new Map(),
        // v1.2.13：按摄像头分组展示（折叠状态 / 各设备文件数）
        // v2.3.1：每组增量渲染（首次 50 行，点「加载更多」续 200 行），避免上千行 DOM 一次成型卡死主线程
        recCollapsed: new Set(), recExpanded: new Set(), recCounts: {}, recShown: new Map(), recListCurrent: [], recGroupCount: 1,
        lastLoads: { devicesFull: 0, dashboard: 0, boards: 0, about: 0 },
        filesLockedDid: '', // v2.3.1 文件弹层锁定摄像头（''=全部视图，仅设置页入口）
        // v1.2.8：事件告警页（选中集 / 已加载行 / 分页游标）
        evSelected: new Set(), evRows: [], evOffset: 0, evTotal: 0, evBusy: false,
        // v1.2.15：全量事件数 / 删除或清空操作的重入保护
        evAllTotal: 0, evDeleting: false,
        // v1.2.13：事件按摄像头分组（折叠状态 / 各设备事件数，来自 /api/events/stats）
        evCollapsed: new Set(), evCounts: {},
        // v2.3.1：全部设备视图下重置加载时默认折叠除第一组外的所有分组（降低长页滚动）；
        // 设置页 30 秒内重复进入不重新拉全量设置（降低切页卡顿）
        evCollapsePending: false, settingsLoadedAt: 0,
        // v1.2.14：首页摄像头卡片的最近画面变动快照（did -> 事件数组 / id -> 事件 / 数据签名 / 跳转筛选）
        camRecent: {}, camRecentIndex: {}, camRecentSig: null, evPendingDid: '',
        // v1.2.8：PTZ 操作时临时切 JPEG 兼容帧，操作停止后自动切回 HLS
        ptzCompatActive: false, ptzCompatTimer: null,
        // 门禁令牌（内存兜底；主存 localStorage，跨站 iframe 中为分区存储仍可读写）
        memToken: '',
        // 时间轴回放状态
        pb: {
            inited: false, did: null, dateStr: null,
            zoom: 24, winStart: 0, dayStart: 0, winDur: 86400000,
            segs: [], idx: -1, curStart: 0, pendingOffset: null,
            swapping: false, // v2.3.1 换段交接进行中（防 ended/提前交接重入）
            motionMs: [],
            liveTimer: null, liveBusy: false,
            loop: 'off', // v2.1.0 循环播放：off | seg（单段）| day（当天）
            // v2.2.5 双 video 无缝接续：A(pbVideo)/B(pbVideoB) 叠放交替，activeB 标记当前活动元素
            activeB: false
        }
    };

    /* ---------- 通用 ---------- */
    function toast(msg, kind) {
        var box = $('toast');
        var el = document.createElement('div');
        el.className = 'toast-item' + (kind ? ' ' + kind : '');
        el.textContent = msg;
        box.appendChild(el);
        setTimeout(function () { el.remove(); }, 2800);
    }

    function isGatePath(p) {
        return p.indexOf('api/access') === 0 || p.indexOf('api/login') === 0;
    }

    /* ---------- 门禁令牌（不依赖 Cookie，兼容跨站 iframe） ---------- */
    var TOKEN_KEY = 'ainvr_access_token';
    function getToken() {
        try {
            var v = localStorage.getItem(TOKEN_KEY);
            if (v) { return v; }
        } catch (e) { /* 落到内存兜底 */ }
        return state.memToken || '';
    }
    function setToken(t) {
        state.memToken = t || '';
        try {
            if (t) { localStorage.setItem(TOKEN_KEY, t); }
            else { localStorage.removeItem(TOKEN_KEY); }
        } catch (e) { /* 存储被禁用时仅用内存兜底 */ }
    }

    /* v1.2.16「记住解锁」：服务端令牌会话只在内存，进程重启 / 会话过期 / WebView 重新加载
       都会掉解锁态。解锁成功时保存由访问密码派生的长期凭证，页面重新探测到未解锁时静默恢复，
       避免预览页按返回后又要重新输入访问密码。主动「锁定」即清除，等价于用户主动退出。
       （localStorage 与 Cookie 双通道互为兜底。） */
    var REMEMBER_KEY = 'ainvr_access_remember';
    function getRemember() {
        try { var v = localStorage.getItem(REMEMBER_KEY); if (v) return v; }
        catch (e) { /* 分区存储不可读时继续试 Cookie */ }
        // 兜底：部分 WebView / 跨站 iframe 会重建 localStorage 分区，而同源 Cookie 仍在
        try {
            var m = document.cookie.match(/(?:^|;\s*)ainvr_access_remember=([^;]*)/);
            if (m) return decodeURIComponent(m[1]);
        } catch (e) { return ''; }
        return '';
    }
    function setRemember(t) {
        try {
            if (t) { localStorage.setItem(REMEMBER_KEY, t); }
            else { localStorage.removeItem(REMEMBER_KEY); }
        } catch (e) { /* 存储被禁用时退化为每次输入密码 */ }
        try {
            document.cookie = REMEMBER_KEY + '=' + (t ? encodeURIComponent(t) : '')
                + '; path=/; max-age=' + (t ? 2592000 : 0) + '; SameSite=Lax';
        } catch (e) { /* Cookie 不可用不影响主路径 */ }
    }
    function clearRemember() { setRemember(''); }
    /** 给无法加自定义头的 URL（<video> 媒体、<a download> 下载）追加令牌查询参数。 */
    function withToken(path) {
        var t = getToken();
        if (!t) { return path; }
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + '__ainvr_t=' + encodeURIComponent(t);
    }

    /** 把后端返回的相对应用根的绝对 API 路径（如 /api/events/101/snapshot）拼上 fnOS 网关
     *  context-path，生成可直用于 <img>/<video> 的完整 URL。fetch 用相对路径会自动基于当前页面
     *  解析到 /app/{appname}/ 下；但 <img> 传入前导 / 的绝对路径会落到域名根而绕开 context-path，
     *  故在此补齐前缀（与 snapshotApiUrl 的计算方式一致）。 */
    function snapFullUrl(u) {
        if (!u) return u;
        var p = location.pathname;
        var dir = p.substring(0, p.lastIndexOf('/') + 1);
        return dir + (u.charAt(0) === '/' ? u.substring(1) : u);
    }

    async function api(path, opts) {
        opts = opts || {};
        opts.headers = Object.assign({}, opts.headers);
        // v2.2.7 兜底：字符串 body 未显式指定 Content-Type 时自动补 application/json。
        // 背景：saveAi（api/settings/ai）漏带头触发 415；同类遗漏从此在出口处统一兜住。
        // 注意：FormData 等 blob 类 body 不能设（multipart 边界由浏览器生成），仅处理字符串。
        if (typeof opts.body === 'string' && !opts.headers['Content-Type']) {
            opts.headers['Content-Type'] = 'application/json';
        }
        var tk = getToken();
        if (tk && !opts.headers['Authorization']) {
            opts.headers['Authorization'] = 'Bearer ' + tk;
        }
        var res;
        try {
            res = await fetch(path, opts);
        } catch (netErr) {
            // v2.3.1 网络类失败也进错误记录（诊断报告可见）
            noteErr('网络异常 ' + path + '：' + netErr.message);
            throw netErr;
        }
        var ct = res.headers.get('content-type') || '';
        if (ct.indexOf('application/json') >= 0) {
            var data = await res.json();
            if (data && typeof data === 'object' && data.token) { setToken(data.token); }
            if (!res.ok) {
                var msg = data.error || ('请求失败 HTTP ' + res.status);
                var err = new Error(msg);
                err.status = res.status;
                err.code = data.code;
                // 门禁失效（服务重启 / 会话过期 / 未解锁）：自动回到设置或解锁页（门禁/登录接口除外）
                // noRelock：探测类请求（版本、运行时长、自动解锁）失败不应把用户踢回锁屏页
                if (res.status === 401 && data.code === 'ACCESS_LOCKED' && !opts.noRelock) { scheduleGateRelock(); }
                noteErr(path + ' → HTTP ' + res.status + ' ' + msg);
                throw err;
            }
            return data;
        }
        if (!res.ok) {
            if (res.status === 401 && !opts.noRelock) { scheduleGateRelock(); }
            noteErr(path + ' → HTTP ' + res.status);
            throw new Error('请求失败 HTTP ' + res.status);
        }
        return res;
    }

    function fmtSize(n) {
        if (n == null) return '-';
        if (n < 1024) return n + ' B';
        if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
        if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
        return (n / 1073741824).toFixed(2) + ' GB';
    }
    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function fmtTime(ms) {
        var d = new Date(ms);
        function p(x) { return String(x).padStart(2, '0'); }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
            ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    function fmtDuration(sec) {
        sec = Math.max(0, Math.floor(sec));
        var d = Math.floor(sec / 86400);
        var h = Math.floor((sec % 86400) / 3600);
        var m = Math.floor((sec % 3600) / 60);
        var s = sec % 60;
        if (d > 0) return d + '天' + h + '时';
        if (h > 0) return h + '时' + m + '分';
        if (m > 0) return m + '分' + s + '秒';
        return s + '秒';
    }
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    /* ---------- v1.2.17 弹层历史栈（iPhone 飞牛 App / WKWebView 返回键） ----------
       飞牛 App 左上角的返回键属于宿主 WebView：只有 canGoBack=true 时才会回退网页，
       否则直接关闭整个应用容器（用户看到「退出 aiNVR」）。预览/回放原本只是同页弹层，
       不产生历史条目，用户一点返回就退出了应用；再次进入时 WebView 重建、内存会话
       丢失，于是又要重新输入访问密码（v1.2.16 的记住解锁只是补了后半段）。
       这里在打开弹层时压入一条历史记录，接管 popstate / pageshow 把宿主返回动作
       收敛为「关闭当前弹层」，并在弹层头部提供应用内返回按钮作为宿主不支持时的兜底。 */
    var OVERLAY_HASH = '#view';
    var overlayHistoryDepth = 0;       // 0 = 首页条目；1 = 已压入弹层条目
    var overlayHistoryBacking = false; // 区分「应用内关闭触发的 back」与「宿主返回」
    var overlayKind = '';              // 'preview' | 'player'

    function overlayBaseUrl() { return location.pathname + location.search; }
    function clearOverlayHash() {
        if (location.hash.indexOf(OVERLAY_HASH) !== 0) return;
        try { history.replaceState(null, '', overlayBaseUrl()); } catch (e) { /* WebView 禁用历史 API */ }
    }
    function enterOverlayHistory(kind) {
        if (overlayHistoryDepth) return;
        var url = overlayBaseUrl() + OVERLAY_HASH + '=' + kind;
        try {
            if (location.hash.indexOf(OVERLAY_HASH) === 0 && history.state && history.state.ainvrOverlay) {
                // 异常路径遗留的同名条目：直接覆盖，避免历史栈无限增长
                history.replaceState({ ainvrOverlay: kind }, '', url);
            } else {
                history.pushState({ ainvrOverlay: kind }, '', url);
            }
            overlayHistoryDepth = 1;
            overlayKind = kind;
        } catch (e) { overlayHistoryDepth = 0; overlayKind = ''; }
    }
    /** 应用内关闭弹层：主动退掉那条历史记录，避免残留成「按返回没反应」。 */
    function leaveOverlayHistory() {
        overlayKind = '';
        if (!overlayHistoryDepth) { clearOverlayHash(); return; }
        overlayHistoryDepth = 0;
        overlayHistoryBacking = true;
        try { history.back(); }
        catch (e) { overlayHistoryBacking = false; clearOverlayHash(); }
        // 少数 WebView 对 pushState 条目不派发 popstate：超时后自行清理地址栏
        setTimeout(function () {
            if (overlayHistoryBacking) { overlayHistoryBacking = false; clearOverlayHash(); }
        }, 500);
    }
    /** 已在弹层历史里时切换记录类型（宫格 → 单路预览），不叠加新条目。 */
    function switchOverlayKind(kind) {
        if (!overlayHistoryDepth) return;
        overlayKind = kind;
        try {
            history.replaceState({ ainvrOverlay: kind }, '', overlayBaseUrl() + OVERLAY_HASH + '=' + kind);
        } catch (e) { /* 地址栏更新失败不影响功能 */ }
    }
    /** 门禁回锁等异常路径：不做异步回退，只静默复位状态。 */
    function dropOverlayHistoryQuiet() {
        overlayHistoryDepth = 0;
        overlayHistoryBacking = false;
        overlayKind = '';
        clearOverlayHash();
    }
    function onOverlayHistoryBack() {
        if (overlayHistoryBacking) {
            // 由应用内 × / 返回按钮触发的 back：界面已关闭，只清理地址栏
            overlayHistoryBacking = false;
            clearOverlayHash();
            return;
        }
        if (!overlayHistoryDepth) return;
        var kind = overlayKind;
        overlayHistoryDepth = 0;
        overlayKind = '';
        clearOverlayHash();
        if (kind === 'grid' && !$('gridModal').hidden) {
            stopGridOnly();
            loadDevices(false);
        } else if (kind === 'preview' && !$('previewModal').hidden) {
            stopPreviewOnly();
            loadDevices(false);
        } else if (kind === 'player' && !$('playerModal').hidden) {
            stopPlayerOnly();
        } else if (kind === 'files' && !$('filesModal').hidden) {
            closeFilesModalQuiet();
        }
    }
    window.addEventListener('popstate', onOverlayHistoryBack);
    // v2.3.1 问题反馈：全局错误捕获进环形记录（最多 50 条），随诊断报告上报
    window.addEventListener('error', function (e) {
        if (e && (e.message || e.filename)) {
            noteErr((e.filename ? e.filename.split('/').pop() + ':' : '')
                + (e.lineno || '') + ' ' + (e.message || '')); 
        }
    });
    window.addEventListener('unhandledrejection', function (e) {
        var r = e && e.reason;
        noteErr('Promise 未处理拒绝：' + (r && r.message ? r.message : String(r)));
    });
    window.addEventListener('pageshow', function () {
        // 部分壳 App 从 bfcache 恢复文档：避免残留半开的弹层历史状态
        if (overlayHistoryDepth && $('previewModal').hidden && $('playerModal').hidden && $('gridModal').hidden) {
            dropOverlayHistoryQuiet();
        }
    });

    /* ---------- 视图切换 ---------- */
    function stopPoll() {
        if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
        if (state.dashTimer) { clearInterval(state.dashTimer); state.dashTimer = null; }
    }
    function hideAll() {
        $('loginView').hidden = true;
        $('setupView').hidden = true;
        $('lockView').hidden = true;
        $('appView').hidden = true;
        $('topRight').hidden = true;
        document.body.classList.remove('authed');
        stopPoll();
        if (!$('gridModal').hidden) stopGridOnly();
        if (!$('previewModal').hidden) stopPreviewOnly();
        if (!$('playerModal').hidden) stopPlayerOnly();
        if (!$('filesModal').hidden) closeFilesModalQuiet();
        // 门禁回锁 / 切换视图时同步复位弹层历史，避免留下「按返回没反应」的死条目
        if (overlayHistoryDepth) dropOverlayHistoryQuiet();
        pausePb();
    }
    function showLogin() {
        hideAll();
        $('loginView').hidden = false;
        resetLoginPanels();
    }
    function showSetup() {
        hideAll();
        $('setupView').hidden = false;
        $('setupPw1').value = '';
        $('setupPw2').value = '';
        $('setupError').hidden = true;
        $('setupError').textContent = '';
        setTimeout(function () { $('setupPw1').focus(); }, 0);
    }
    function showLock() {
        stopAppTimers(); // 锁定即停主界面轮询（解锁后 showApp 会按新鲜度重启）
        hideAll();
        $('lockView').hidden = false;
        $('lockPw').value = '';
        $('lockError').hidden = true;
        $('lockError').textContent = '';
        var qs = new URLSearchParams(location.search);
        if (qs.get('unlock_error') === '1') {
            showLockError('解锁链接无效或已失效，请输入访问密码。');
        }
        if (qs.has('unlock_error') || qs.has('unlocked')) {
            history.replaceState(null, '', location.pathname);
        }
        setTimeout(function () { $('lockPw').focus(); }, 0);
    }
    function showSetupError(m) {
        var b = $('setupError'); b.hidden = false; b.textContent = m;
    }
    function showLockError(m) {
        var b = $('lockError'); b.hidden = false; b.textContent = m;
    }
    function showApp(userId, region) {
        hideAll();
        $('appView').hidden = false;
        $('topRight').hidden = false;
        document.body.classList.add('authed');
        $('userChip').textContent = '已登录 · ' + userId + '（' + region + '）';
        resetLoginPanels();
        // v2.3.1 解锁秒开：锁定期间 DOM 与数据都还在内存里，解锁后先原样展示缓存画面，
        // 再按「新鲜度」决定要不要重拉——原实现每次解锁都 loadDevices(true)（服务端走
        // 米家云端全量刷新，数秒）+ 强制整表重建摄像头卡（快照闪断重载）+ 概览扫录像目录，
        // 用户刚锁完马上解锁也要等一轮全量加载，体感“进页面特别慢”。
        // 设备全量刷新 60s 内复用；概览 30s 内复用（dashTimer 也会按时刷）；其余会话内一次。
        var now = Date.now();
        stopAppTimers();
        state.pollTimer = setInterval(function () { loadDevices(false); }, 5000);
        // 概览含录像目录扫描，30 秒一次即可，不跟着 5 秒设备轮询走
        state.dashTimer = setInterval(loadDashboard, 30000);
        if (!state.devices.length) {
            // 进程重启后首访：本机无缓存可用，必须拉一次云端。后端已在启动 3s 后预热
            // 过设备列表（DeviceCatalog 预热），此时命中后端内存缓存，通常只需亚秒级。
            loadDevices(true);
        } else if (now - state.lastLoads.devicesFull > 60000) {
            // 超过新鲜度：先轻量同步让徽标/卡片立即呈现（不重建、不等云端），
            // 全量刷新转后台异步执行，完成后下一次轮询自动把变化刷上来。
            loadDevices(false);
            setTimeout(function () { loadDevices(true); }, 50);
        } else {
            loadDevices(false); // 轻量状态同步（online/recording 徽标），不重建卡片
        }
        if (now - state.lastLoads.dashboard > 30000) { loadDashboard(); }
        if (!state.lastLoads.boards) { loadBoards(); }
        if (!state.lastLoads.about) { loadAbout(); }
    }

    /** 停掉主界面轮询：锁定/登出后不再空转——原实现锁定后 5s 设备轮询与 30s 概览扫描
     *  继续打门禁接口，全部 401 触发回锁链路，白白消耗请求还拖慢解锁后的首个加载。 */
    function stopAppTimers() {
        if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
        if (state.dashTimer) { clearInterval(state.dashTimer); state.dashTimer = null; }
    }

    /* ---------- 访问密码门禁路由 ---------- */
    function route() {
        if (!state.accessConfigured) {
            // 尚未设置访问密码：米家已登录则强制设置，否则先登录米家
            if (state.loggedIn) { showSetup(); } else { showLogin(); }
            return;
        }
        if (!state.unlocked) { showLock(); return; }
        if (state.loggedIn) { showApp(state.userId, state.region); } else { showLogin(); }
    }

    async function refreshGate() {
        try {
            var r = await Promise.all([api('api/session'), api('api/access/status', { noRelock: true })]);
            var s = r[0], a = r[1];
            state.loggedIn = !!s.loggedIn;
            state.userId = s.userId || a.userId || null;
            state.region = s.region || a.region || null;
            state.accessConfigured = !!a.configured;
            state.unlocked = !!a.unlocked;
            // v1.2.16：服务端令牌会话失效（应用重启 / 会话过期 / 页面在 WebView 里被重新加载）
            // 时，用本地「记住解锁」凭证静默恢复，不打断用户直接回主界面。凭证无效才回锁屏页。
            if (!state.unlocked && state.accessConfigured && getRemember()) {
                try {
                    var rr = await api('api/access/reauth', {
                        method: 'POST', headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ remember: getRemember() }), noRelock: true
                    });
                    state.unlocked = !!rr.unlocked;
                    if (!state.unlocked) { clearRemember(); }
                } catch (e2) { clearRemember(); }
            }
        } catch (e) {
            state.loggedIn = false;
            state.unlocked = false;
        }
        route();
    }

    function scheduleGateRelock() {
        if (state.gateTimer) clearTimeout(state.gateTimer);
        state.gateTimer = setTimeout(function () { state.gateTimer = null; refreshGate(); }, 300);
    }

    /* 米家登录分步走到 DONE 后调用。DONE 响应本身就是登录成功的权威结果（带 userId/region），
       先据此置登录态；门禁配置再查 /api/access/status，但不让其瞬时 loggedIn 把刚成功的登录
       覆盖回未登录（后端已保证 DONE 前会话落盘，这里是前端再兜底）。最终交给 route() 决策。 */
    async function enterAfterLogin(s) {
        state.loggedIn = true;
        if (s.userId) state.userId = s.userId;
        if (s.region) state.region = s.region;
        try {
            var a = await api('api/access/status');
            state.accessConfigured = !!a.configured;
            state.unlocked = !!a.unlocked;
            if (a.userId) { state.userId = a.userId; state.region = a.region; }
        } catch (e) { /* 门禁状态查询失败也先按“已登录”路由，setup/后续接口会再校验 */ }
        route();
    }
    function resetLoginPanels() {
        $('loginForm').hidden = false;
        $('captchaPanel').hidden = true;
        $('twofaPanel').hidden = true;
        $('loginError').hidden = true;
        $('loginError').textContent = '';
        $('captchaCode').value = '';
        $('verifyCode').value = '';
        state.loginToken = null;
    }
    function showLoginError(msg) {
        var box = $('loginError');
        box.hidden = false;
        box.textContent = msg;
    }

    /* ---------- 登录状态机 ---------- */
    function handleStage(s) {
        state.loginToken = s.token || state.loginToken;
        if (s.loggedIn || s.stage === 'DONE') {
            toast('米家账号登录成功', 'ok');
            enterAfterLogin(s);
            return;
        }
        if (s.stage === 'NEED_CAPTCHA') {
            $('loginForm').hidden = true;
            $('twofaPanel').hidden = true;
            $('captchaPanel').hidden = false;
            $('captchaImg').src = 'api/login/' + state.loginToken + '/captcha?t=' + Date.now();
            $('captchaCode').focus();
        } else if (s.stage === 'NEED_2FA') {
            $('loginForm').hidden = true;
            $('captchaPanel').hidden = true;
            $('twofaPanel').hidden = false;
            var kind = s.verifyKind === 'Email' ? '邮箱' : '手机';
            $('twofaTip').textContent = '验证码已发送至' + kind + (s.verifyTarget ? '（' + s.verifyTarget + '）' : '') + '，请输入：';
            $('verifyCode').focus();
        } else if (s.stage === 'ERROR') {
            showLoginError(s.error || '登录失败，请重试');
            resetPanelsKeepTokenFail();
        } else {
            showLoginError('登录未完成（' + s.stage + '），请重试');
            resetPanelsKeepTokenFail();
        }
    }
    function resetPanelsKeepTokenFail() {
        $('loginForm').hidden = false;
        $('captchaPanel').hidden = true;
        $('twofaPanel').hidden = true;
    }

    async function startLogin() {
        var username = $('username').value.trim();
        var password = $('password').value;
        if (!username || !password) { showLoginError('请输入账号和密码'); return; }
        $('btnLogin').disabled = true;
        try {
            var s = await api('api/login/start', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username, password: password, region: 'cn' })
            });
            handleStage(s);
        } catch (e) { showLoginError(e.message); }
        finally { $('btnLogin').disabled = false; }
    }

    async function submitCaptcha() {
        var code = $('captchaCode').value.trim();
        if (!code) { toast('请输入图形验证码', 'err'); return; }
        try {
            var s = await api('api/login/' + state.loginToken + '/captcha', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code: code })
            });
            handleStage(s);
        } catch (e) { showLoginError(e.message); }
    }

    async function submitVerify() {
        var code = $('verifyCode').value.trim();
        if (!code) { toast('请输入验证码', 'err'); return; }
        try {
            var s = await api('api/login/' + state.loginToken + '/verify', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code: code })
            });
            handleStage(s);
        } catch (e) { showLoginError(e.message); }
    }

    /* ---------- 访问密码：设置 / 解锁 / 锁定 ---------- */
    async function submitSetup() {
        var p1 = $('setupPw1').value, p2 = $('setupPw2').value;
        if (!p1 || !p2) { showSetupError('请输入并再次确认访问密码'); return; }
        if (p1.length < 6) { showSetupError('访问密码至少 6 位'); return; }
        if (p1 !== p2) { showSetupError('两次输入的密码不一致'); return; }
        $('btnSetup').disabled = true;
        try {
            var d = await api('api/access/setup', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p1 })
            });
            if (d && d.remember) { setRemember(d.remember); }
            state.accessConfigured = true;
            state.unlocked = true;
            toast('访问密码已设置', 'ok');
            route();
        } catch (e) { showSetupError(e.message); }
        finally { $('btnSetup').disabled = false; }
    }

    async function submitUnlock() {
        var p = $('lockPw').value;
        if (!p) { showLockError('请输入访问密码'); return; }
        $('btnUnlock').disabled = true;
        var oldLabel = $('btnUnlock').textContent;
        $('btnUnlock').textContent = '校验中…';
        try {
            var d = await api('api/access/unlock', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p })
            });
            if (d && d.remember) { setRemember(d.remember); }
            state.unlocked = true;
            var s = await api('api/session');
            state.loggedIn = !!s.loggedIn;
            state.userId = s.userId || state.userId;
            state.region = s.region || state.region;
            toast('已解锁', 'ok');
            route();
        } catch (e) { showLockError(e.message); }
        finally { $('btnUnlock').disabled = false; $('btnUnlock').textContent = oldLabel; }
    }

    async function lockConsole() {
        try { await api('api/access/lock', { method: 'POST' }); } catch (e) {}
        // 主动锁定 = 主动退出：清掉本地凭证，下次进入必须重新输入访问密码
        clearRemember();
        state.unlocked = false;
        route();
    }

    /* 凭访问密码生成一键解锁链接（不改变当前会话状态），在设置页展示并供复制。 */
    async function genUnlockLink() {
        var p = $('setLinkPw').value;
        if (!p) { showPwError('请输入当前访问密码以生成一键解锁链接'); return; }
        $('btnGenLink').disabled = true;
        try {
            var data = await api('api/access/unlock-link', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p })
            });
            $('setLinkText').value = location.origin + data.path;
            $('setLinkBox').hidden = false;
            $('setLinkPw').value = '';
            $('setPwError').hidden = true;
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            showPwError(e.message || '生成失败');
        }
        finally { $('btnGenLink').disabled = false; }
    }

    function copyUnlockLink() {
        var v = $('setLinkText').value;
        if (v) { copyText(v); }
    }

    /* ---------- v1.2.7 首页概览（/api/dashboard，30 秒刷新） ---------- */
    function statSet(numId, subId, value, sub, cls) {
        var n = $(numId), s = $(subId);
        if (n) { n.textContent = value; }
        if (s) { s.textContent = sub || ''; }
        var card = n ? n.parentNode : null;
        if (card && card.classList) {
            card.classList.remove('ok', 'warn', 'danger');
            if (cls) { card.classList.add(cls); }
        }
    }

    async function loadDashboard() {
        try {
            var d = await api('api/dashboard');
            state.lastLoads.dashboard = Date.now(); // 新鲜度标记：解锁后 30s 内复用缓存画面
            var dev = d.devices || {}, ev = d.events || {}, rec = d.recordings || {},
                st = d.streams || {}, mo = d.motion || {};
            // v2.3.1 统计条压缩：休眠/离线/录像中并入摄像头副行；未处理并入事件副行
            var devSub = (dev.online == null ? '-' : dev.online) + ' 在线 · '
                + (dev.sleeping || 0) + ' 休眠 · ' + (dev.offline || 0) + ' 离线'
                + (dev.recording ? ' · ' + dev.recording + ' 路录像中' : '');
            statSet('dsDevTotal', 'dsDevSub', dev.total == null ? '-' : dev.total,
                devSub, dev.offline ? 'warn' : 'ok');
            statSet('dsToday', 'dsTodaySub', ev.today == null ? 0 : ev.today,
                '未处理 ' + (ev.unhandled == null ? 0 : ev.unhandled) + ' 条',
                ev.unhandled ? 'warn' : 'ok');
            updateEvBadge(ev.unhandled);
            statSet('dsRecFiles', 'dsRecSub', rec.files == null ? 0 : rec.files,
                fmtSize(rec.bytes));
            statSet('dsStreams', 'dsStreamsSub', st.activeUpstreams == null ? 0 : st.activeUpstreams,
                (st.activeFrameSources || 0) + ' 路兼容帧');
        } catch (e) {
            // 概览失败不打断主流程（门禁失效由 api() 统一处理）
        }
        loadCamEvents(); // 与概览同频（30 秒）刷新卡片快照，内部有签名判重
        refreshCardSnaps(); // v2.3.0 卡顶快照同频刷新
    }

    /** 页脚显示运行版本与已运行时长；失败静默（不影响主流程）。 */
    function loadAbout() {
        var el = $('appVersion');
        if (!el) return;
        // v1.2.16：以前用前导 / 的绝对路径，fetch 会打到网关域名根、绕开 fnOS 的
        // context-path（/app/ainvr），网关 401 后静默失败，锁屏页永远停在 “v?”。
        // 改为与其它 api() 一致的相对路径，并在 init() 全局调用（原来只在解锁后的 showApp 里调）。
        api('api/version', { noRelock: true }).then(function (d) {
            var v = d.version || '?';
            el.textContent = 'v' + v;
            state.lastLoads.about = Date.now();
            // 解锁后再补运行时长（需鉴权；未解锁时 401 属正常，静默忽略且不触发回锁）
            api('api/about', { noRelock: true }).then(function (a) {
                if (a && a.uptimeMs) {
                    el.textContent = 'v' + v + ' · 已运行 ' + fmtDuration(a.uptimeMs / 1000);
                }
            }).catch(function () {});
        }).catch(function () {});
    }

    /* ---------- v1.2.7 设备自检 ---------- */
    function probeRow(label, value) {
        return '<div><span>' + esc(label) + '</span><b>' + esc(value) + '</b></div>';
    }

    async function runProbe(did, deep) {
        var card = $('deviceGrid').querySelector('.cam-card[data-did="' + CSS.escape(did) + '"]');
        if (!card) { return; }
        var box = card.querySelector('.probe-box');
        if (!box) {
            box = document.createElement('div');
            box.className = 'probe-box';
            card.appendChild(box);
        }
        box.classList.remove('err');
        box.innerHTML = '<div class="probe-hint">正在自检' +
            (deep ? '（深度探测会短暂拉起取流，最多 8 秒）' : '') + '…</div>';
        var mainBtn = card.querySelector('.cam-actions button[data-act="probe"]');
        if (mainBtn) { mainBtn.disabled = true; }
        try {
            var p = await api('api/devices/' + encodeURIComponent(did) + '/probe' + (deep ? '?deep=true' : ''));
            var s = p.stream || {}, m = p.media || {}, mo = p.motion || {};
            var rows = '';
            rows += probeRow('设备状态', p.online ? '在线' : '离线');
            rows += probeRow('取流会话', s.active ? (s.healthy ? '健康' : '异常') : '未建立');
            rows += probeRow('当前订阅者', s.subscribers == null ? '-' : s.subscribers);
            rows += probeRow('最近一帧', s.lastFrameAgeMs < 0 ? '从未收到'
                : (s.lastFrameAgeMs / 1000).toFixed(1) + ' 秒前');
            rows += probeRow('清晰度设置', p.quality || 'auto');
            rows += probeRow('云台格式', p.ptzMode || 'direction');
            rows += probeRow('录像 / 预览', (p.recording ? '录像中' : '未录像') + ' / '
                + (p.previewing ? '预览中' : '未预览'));
            rows += probeRow('运动侦测', mo.enabled ? (mo.watched ? '已启用·含本设备' : '已启用·不含本设备') : '未启用');
            rows += probeRow('重连代数', s.generation == null ? '-' : s.generation);
            if (deep) {
                rows += probeRow('视频编码', m.codec || '未知');
                rows += probeRow('原始分辨率', m.width ? (m.width + ' × ' + m.height) : '未取得');
                rows += probeRow('帧率(估)', m.fpsEstimate ? (m.fpsEstimate + ' fps') : '未取得');
                rows += probeRow('视频包 / 首帧', (m.videoPackets || 0) + ' / '
                    + (m.firstFrameMs < 0 ? '-' : m.firstFrameMs + ' ms'));
            }
            var hint = deep
                ? '帧率按媒体包时间戳估算，仅供参考；深检结束已自动退订，不额外占用通道。'
                : '浅检只读内存状态，不取流、不影响画面。点「深度探测」查看编码 / 分辨率 / 帧率。';
            if (m.error) { hint = '探测异常：' + m.error + '。' + (m.hint || ''); }
            else if (m.hint) { hint = m.hint; }
            box.innerHTML = '<div class="probe-grid">' + rows + '</div>' +
                '<div class="probe-hint">' + esc(hint) + '</div>' +
                '<div class="probe-actions">' +
                '<button class="btn soft" data-act="' + (deep ? 'probe' : 'deepprobe') +
                '" data-did="' + esc(did) + '">' + (deep ? '重新浅检' : '深度探测') + '</button>' +
                '<button class="btn soft" data-act="probeclose" data-did="' + esc(did) + '">收起</button>' +
                '</div>';
            if (!p.online) { box.classList.add('err'); }
        } catch (e) {
            box.classList.add('err');
            box.innerHTML = '<div class="probe-hint">自检失败：' + esc(e.message || String(e)) + '</div>';
        } finally {
            var b2 = card.querySelector('.cam-actions button[data-act="probe"]');
            if (b2) { b2.disabled = false; }
        }
    }

    /* ---------- 设备 ---------- */
    async function loadDevices(forceRefresh) {
        try {
            var data = await api('api/devices?refresh=' + (forceRefresh ? 'true' : 'false'));
            if (forceRefresh) { state.lastLoads.devicesFull = Date.now(); }
            var list = data.devices || [];
            var structureChanged = list.length !== state.devices.length ||
                list.some(function (c) {
                    var old = state.devices.find(function (o) { return String(o.did) === String(c.did); });
                    return !old || old.name !== c.name || old.model !== c.model || old.localIp !== c.localIp;
                });
            state.devices = list;
            renderRecFilter();
            // v2.3.1 第二十一轮：设备到达后同步刷新米家设备控制下拉——此前只在
            // 进入设置分组时填一次，手机上先开设置后到设备时显示「尚未发现摄像头」
            refreshMiotDevSelect();
            if (forceRefresh || structureChanged) {
                renderDevices();
            } else {
                updateDeviceStates();
            }
        } catch (e) {
            if (e.status === 401) { return; } // 门禁失效由全局 scheduleGateRelock 切回门禁页
            if (forceRefresh) toast('加载设备失败：' + e.message, 'err');
        }
    }

    /* ---------- v2.3.1 设置页：米家设备控制（夜视/翻转/水印/追踪/录制模式/看家助手/存储卡/重启） ---------- */

    /** 米家属性读取结果缓存（did → {key:{code,value,label}}），渲染与写回用。 */
    var miotCache = {};

    /**
     * v2.3.1 第十六轮：控制项定义改为数据驱动，读取结果后整表 JS 渲染——
     * 每行「名称 + 说明 + 控件 + 设置」，不支持项灰显打标，替代旧版静态
     * label+select 逐项堆叠的排版。
     */
    var MIOT_CONTROLS = [
        { key: 'nightShot', label: '夜视模式', hint: '红外补光方式（关闭 / 打开 / 自动）', type: 'enum',
          opts: [['0', '关闭'], ['1', '打开'], ['2', '自动']] },
        { key: 'imageRollover', label: '画面翻转', hint: '倒装摄像头的 180° 画面矫正', type: 'enum',
          opts: [['0', '正常'], ['180', '翻转 180°']] },
        { key: 'timeWatermark', label: '时间水印', hint: '画面右上角时间戳', type: 'bool' },
        { key: 'humanTracking', label: '人形追踪', hint: '云台款：画面自动跟随人形', type: 'bool' },
        { key: 'recordingMode', label: '存储卡录制', hint: '摄像头本机 SD 卡的录制方式', type: 'enum',
          opts: [['0', '不录制'], ['1', '录制移动画面'], ['2', '始终录制']] },
        { key: 'guardSwitch', label: '看家助手', hint: '移动侦测告警推送开关', type: 'bool' },
        { key: 'guardSensitivity', label: '侦测灵敏度', hint: '看家助手的移动侦测灵敏度', type: 'enum',
          opts: [['0', '低'], ['1', '中'], ['2', '高']] }
    ];

    function refreshMiotDevSelect() {
        var sel = $('miotDevSelect');
        var prev = sel.value;
        var mains = state.devices.filter(function (d) { return String(d.did).indexOf('#') < 0; });
        sel.innerHTML = mains.map(function (d) {
            return '<option value="' + esc(d.did) + '">' + esc(d.name) + '（' + esc(d.did) + '）</option>';
        }).join('');
        if (prev && mains.some(function (d) { return String(d.did) === prev; })) {
            sel.value = prev;
        }
        if (!mains.length) {
            $('miotPropForm').hidden = true;
            $('miotMsg').textContent = '尚未发现摄像头';
            return;
        }
        // v2.3.1 第二十一轮：设备到达后清除此前留下的「尚未发现摄像头」过期提示
        if (!miotCache[sel.value]) { $('miotMsg').textContent = '设备已就绪，点「读取」获取当前属性'; }
        if (miotCache[sel.value]) { renderMiotProps(sel.value); }
    }

    async function loadMiotProps(did) {
        if (!did) return;
        $('miotMsg').textContent = '读取中…';
        try {
            var d = await api('api/devices/' + encodeURIComponent(did) + '/miot');
            miotCache[did] = d.props || {};
            renderMiotProps(did);
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            $('miotMsg').textContent = '读取失败：' + e.message;
        }
    }

    /** 按读取结果整表渲染控制项；返回该机支持/不支持摘要。 */
    function renderMiotProps(did) {
        var props = miotCache[did] || {};
        var box = $('miotRows');
        var supported = 0;
        box.innerHTML = MIOT_CONTROLS.map(function (def) {
            var item = props[def.key];
            var ok = !!(item && item.code === 0 && item.value !== null && item.value !== undefined);
            if (ok) { supported++; }
            var opts = (def.type === 'bool' ? [['true', '开启'], ['false', '关闭']] : def.opts)
                .map(function (o) {
                    var selAttr = (ok && String(item.value) === o[0]) ? ' selected' : '';
                    return '<option value="' + o[0] + '"' + selAttr + '>' + esc(o[1]) + '</option>';
                }).join('');
            var tag = ok ? '' : '<span class="miot-unsup-tag">不支持</span>';
            return '<div class="miot-row' + (ok ? '' : ' unsup') + '">'
                + '<span class="miot-label">' + esc(def.label) + tag
                + '<small>' + esc(def.hint) + '</small></span>'
                + '<select class="select" data-miotval="' + def.key + '"' + (ok ? '' : ' disabled') + '>' + opts + '</select>'
                + '<button class="btn tiny primary" data-miotset="' + def.key + '" type="button"'
                + (ok ? '' : ' disabled') + '>设置</button>'
                + '</div>';
        }).join('');
        $('miotPropForm').hidden = false;
        // 存储卡只读信息
        var sd = props.sdStatus, total = props.sdTotal, free = props.sdFree;
        var sdText = '—';
        if (sd && sd.code === 0 && sd.value !== null && sd.value !== undefined) {
            var stMap = ['正常', '未插卡', '卡已满', '卡损坏', '格式化中', '已弹出', '修复中', '弹出中', '未初始化', '空间不足', '文件异常', '不兼容'];
            sdText = stMap[Number(sd.value)] || ('状态 ' + sd.value);
            if (total && total.code === 0 && free && free.code === 0
                && total.value != null && free.value != null) {
                sdText += ' · 剩余 ' + fmtSize(Number(free.value)) + ' / ' + fmtSize(Number(total.value));
            }
        } else {
            sdText = '本机型不支持存储卡状态读取';
        }
        $('miotSdInfo').textContent = sdText;
        var unsupported = MIOT_CONTROLS.length - supported;
        $('miotMsg').textContent = unsupported
            ? '读取完成：' + supported + ' 项可控制，' + unsupported + ' 项本机型不支持（灰显）'
            : '读取完成：全部 ' + supported + ' 项均可控制';
    }

    async function setMiotProp(did, key) {
        var sel = document.querySelector('[data-miotval="' + key + '"]');
        if (!sel || sel.disabled) return;
        var def = null;
        MIOT_CONTROLS.forEach(function (d) { if (d.key === key) def = d; });
        if (!def) return;
        var raw = sel.value;
        var value = def.type === 'bool' ? raw === 'true' : Number(raw);
        $('miotMsg').textContent = '设置中…';
        try {
            await api('api/devices/' + encodeURIComponent(did) + '/miot/' + encodeURIComponent(key),
                { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value: value }) });
            toast('已下发', 'ok');
            $('miotMsg').textContent = '已设置';
            // 重新读取刷新该行显示值（云端写后读可能有短暂滞后，失败不影响设置结果）
            loadMiotProps(did);
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('设置失败：' + e.message, 'err');
            $('miotMsg').textContent = '设置失败：' + e.message;
        }
    }


    function statusBadges(c) {
        // v2.3.1 休眠中无帧可预览/录像：快照轮询与帧源已跳过，徽标如实显示休眠
        if (c.online && c.sleeping === true) { return '<span class="badge off">休眠中</span>'; }
        if (c.previewing) return '<span class="badge live">预览中</span>' +
            (c.recording ? '<span class="badge rec">录像中</span>' : '');
        if (c.recording) return '<span class="badge rec">录像中</span>';
        return '<span class="badge off">空闲</span>';
    }

    function onlineBadge(c) {
        // v2.3.1 休眠判定以云端 MIoT camera_control.on 属性为准（MiotSleepService）：
        // 休眠中 CS2 唤醒监听仍应答 LAN_SEARCH，可达性区分不出休眠。
        // sleeping=true 显示黄色「休眠」徽标，点击直接下发唤醒。
        if (c.online && c.sleeping === true) {
            return '<button type="button" class="net sleep" data-act="wake" data-did="' + esc(c.did)
                + '" title="休眠中 · 点击唤醒">休眠</button>';
        }
        if (c.online && c.lanReachable === false) {
            return '<span class="net sleep"><span class="dot"></span>不可达</span>';
        }
        if (c.online && c.sleeping == null && c.sleepProbeFail != null) {
            // 自证：休眠探测失败（如 -4006 固件不支持该属性），悬停可见，不再无声降级
            return '<span class="net online" title="休眠探测失败 code=' + esc(c.sleepProbeFail)
                + '（固件可能不支持该属性，按在线显示）"><span class="dot"></span>在线</span>';
        }
        return '<span class="net ' + (c.online ? 'online' : 'offline') + '"><span class="dot"></span>'
            + (c.online ? '在线' : '离线') + '</span>';
    }

    /** v2.3.1 唤醒 / 休眠摄像头（云端 MIoT 属性写，服务端有钉住窗口防读数滞后翻转）。 */
    async function toggleSleep(did, wake) {
        var label = wake ? '唤醒' : '休眠';
        try {
            var resp = await api('api/devices/' + encodeURIComponent(did) + '/sleep/' + (wake ? '1' : '0'),
                { method: 'POST' });
            toast(label + '指令已下发' + (wake ? '，摄像头启动约需几秒' : ''), 'ok');
            // 直接以 POST 返回的下发状态渲染（不要立刻 probeOne：云端读数滞后会返回
            // 旧值把刚设置的状态翻转回去；服务端已钉住，loadDevices 拉到的是权威值）
            var base = String(did).split('#')[0];
            state.devices.forEach(function (d) {
                if (d.did === did || String(d.did).split('#')[0] === base) d.sleeping = resp.sleeping;
            });
            renderDevices();
            loadDevices(true);
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast(label + '失败：' + e.message, 'err');
        }
    }

    function cardHtml(c) {
        var offlineDis = c.online ? '' : ' disabled title="设备离线，暂不可用"';
        // 录像中的“停止录像”始终可用（本地操作）；离线时仅禁用“开始录像”。
        var recBtn = c.recording
            ? '<button class="btn rec-on" data-act="recstop" data-did="' + esc(c.did) + '">停止录像</button>'
            : '<button class="btn" data-act="recstart" data-did="' + esc(c.did) + '"' + offlineDis + '>开始录像</button>';
        // v2.3.1 米家式休眠交互：休眠中卡顶快照区整体变为「点击唤醒」入口，
        // hover 显示播放覆盖层（唤醒后可再进预览；下方按钮行也有预览入口）
        var sleeping = c.online && c.sleeping === true && String(c.did).indexOf('#') < 0;
        return '<div class="cam-card' + (c.online ? '' : ' offline') + '" data-did="' + esc(c.did)
            + '" data-sleeping="' + (sleeping ? '1' : '') + '">' +
            // v2.3.0 视频优先卡：顶部最近快照，点击进入实时预览（休眠时点击唤醒）
            '<button type="button" class="cam-media' + (sleeping ? ' cam-media-sleep' : '') + '" data-act="'
            + (sleeping ? 'wake' : 'preview') + '" data-did="' + esc(c.did) + '"' +
            (c.online ? '' : ' disabled') + ' title="' + (sleeping ? '休眠中 · 点击唤醒' : '点击进入实时预览') + '">' +
            // v2.3.1 休眠卡不加载快照（休眠中无新画面，最近快照也已过时）：
            // 显示月亮占位图，点击或 hover 播放按钮均可唤醒
            (sleeping
                ? '<span class="cam-sleep-art" aria-hidden="true">'
                    + '<svg viewBox="0 0 64 64" width="54" height="54" fill="none">'
                    + '<circle cx="14" cy="14" r="1.6" fill="#e6edf3" opacity=".75"/>'
                    + '<circle cx="24" cy="7" r="1" fill="#e6edf3" opacity=".5"/>'
                    + '<circle cx="8" cy="26" r="1" fill="#e6edf3" opacity=".5"/>'
                    + '<path d="M43 13a19 19 0 1 0 10.5 24.5A16.5 16.5 0 0 1 43 13z" fill="#c9d3de" opacity=".9"/>'
                    + '</svg><span>休眠中 · 点击唤醒</span></span>'
                : '<img class="cam-media-img" data-snap-did="' + esc(c.did) + '" alt="最近画面" loading="lazy" hidden>' +
                  '<span class="cam-media-hint">' + (c.online ? '快照加载中…' : '') + '</span>') +
            (sleeping ? '<span class="cam-sleep-play" aria-hidden="true"><span class="tri"></span></span>' : '') +
            '</button>' +
            '<div class="cam-top"><div>' +
            '<div class="cam-name"><span class="nm">' + esc(c.name) + '</span>' + onlineBadge(c) + '</div>' +
            '<div class="cam-model">' + esc(c.model) + '</div>' +
            '<div class="cam-ip">' + esc(c.localIp || '未知IP') + '</div>' +
            '</div><div class="badges">' + statusBadges(c) + '</div></div>' +
            '<div class="cam-actions">' +
            '<button class="btn primary" data-act="preview" data-did="' + esc(c.did) + '"' + offlineDis + '>实时预览</button>' +
            // v2.3.1 入口收敛：录像文件以摄像头卡片为一级入口；回放入口保留在底部导航 tab
            '<button class="btn soft" data-act="files" data-did="' + esc(c.did) + '">录像文件</button>' +
            '</div>' +
            '<div class="cam-actions cam-actions-sub">' +
            recBtn +
            // v2.3.1 休眠/唤醒常驻切换：在线显示「休眠」，休眠中显示「唤醒」
            //（徽标点击同样可唤醒）；副镜头（did 含 #）跟随主镜头不单独下发
            (c.online && String(c.did).indexOf('#') < 0
                ? '<button class="btn soft" data-act="' + (sleeping ? 'wake' : 'sleep') + '" data-did="' + esc(c.did)
                    + '" title="' + (sleeping ? '唤醒摄像头（米家同款，云端下发）' : '让摄像头进入休眠（米家同款，云端下发）') + '">'
                    + (sleeping ? '唤醒' : '休眠') + '</button>' : '') +
            '<button class="btn soft probe-btn" data-act="probe" data-did="' + esc(c.did) + '">自检</button>' +
            '</div>' +
            // v1.2.14：米家式卡片底部 —— 最近几条画面变动事件快照（异步填充，不阻塞设备列表）
            '<div class="cam-events">' +
                '<div class="cam-events-head"><span>最近画面变动</span>' +
                '<button class="cam-events-more" type="button" data-act="evmore" data-did="' + esc(c.did) + '">查看全部 ›</button></div>' +
                '<div class="cam-events-body" data-camev="' + esc(c.did) + '">' +
                    '<div class="cam-events-empty">加载中…</div>' +
                '</div>' +
            '</div></div>';
    }

    /** v2.3.1 摄像头筛选：all / online / awake(未休眠) / sleeping / offline。 */
    function deviceMatchesFilter(c, f) {
        f = f || state.deviceFilter || 'all';
        if (f === 'online') { return !!c.online; }
        if (f === 'awake') { return !!c.online && c.sleeping !== true; }
        if (f === 'sleeping') { return !!c.online && c.sleeping === true; }
        if (f === 'offline') { return !c.online; }
        return true;
    }

    /** 刷新筛选 chips 的计数与选中态（renderDevices 同步调用）。 */
    function updateFilterChips() {
        var n = { all: state.devices.length, online: 0, awake: 0, sleeping: 0, offline: 0 };
        state.devices.forEach(function (c) {
            if (c.online) { n.online++; } else { n.offline++; }
            if (c.online && c.sleeping === true) { n.sleeping++; }
            if (c.online && c.sleeping !== true) { n.awake++; }
        });
        var labels = { all: '全部', online: '在线', awake: '未休眠', sleeping: '休眠中', offline: '离线' };
        document.querySelectorAll('#devFilter .chip').forEach(function (b) {
            var f = b.getAttribute('data-filter');
            b.textContent = labels[f] + ' ' + n[f];
            b.classList.toggle('active', (state.deviceFilter || 'all') === f);
        });
    }

    function renderDevices() {
        var grid = $('deviceGrid');
        var f = state.deviceFilter || 'all';
        var list = state.devices.filter(function (c) { return deviceMatchesFilter(c, f); });
        $('camCount').textContent = state.devices.length + ' 台摄像头'
            + (f !== 'all' ? ' · 筛选出 ' + list.length + ' 台' : '');
        grid.dataset.count = String(state.devices.length);
        if (!state.devices.length) {
            grid.innerHTML = '<div class="empty">账号下未发现摄像头</div>';
            updateFilterChips();
            return;
        }
        if (!list.length) {
            grid.innerHTML = '<div class="empty">没有符合筛选条件的摄像头</div>';
            updateFilterChips();
            return;
        }
        grid.innerHTML = list.map(cardHtml).join('');
        updateFilterChips();
        renderCamEvents(); // 卡片重建后用缓存快照重绘，不必重新请求
        refreshCardSnaps(); // v2.3.0 首屏拉起卡顶快照
    }

    /* ---------- v1.2.14 首页卡片：最近画面变动事件快照 ----------
       后端 /api/events/recent 一次返回每台设备最近 3 条有画面的事件（已剔除历史灰屏），
       前端只负责渲染缩略图条；30 秒概览轮询顺带刷新，数据签名不变时不重建 DOM。 */
    var CAM_SHOTS = 3;

    /** 缩略图时间：今天只显示 HH:mm，跨天补 M/D，避免小卡片里挤长串日期。 */
    function fmtShotTime(ms) {
        var d = new Date(ms), now = new Date();
        function p(x) { return String(x).padStart(2, '0'); }
        var hm = p(d.getHours()) + ':' + p(d.getMinutes());
        return d.toDateString() === now.toDateString()
            ? hm : (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hm;
    }

    function renderCamEvents() {
        var boxes = document.querySelectorAll('.cam-events-body[data-camev]');
        Array.prototype.forEach.call(boxes, function (box) {
            var did = box.getAttribute('data-camev');
            var list = (state.camRecent[did] || []).filter(function (ev) { return !!ev.snapshotUrl; });
            if (!list.length) {
                box.innerHTML = '<div class="cam-events-empty">暂无画面变动快照</div>';
                return;
            }
            box.innerHTML = list.map(function (ev) {
                return '<button type="button" class="cam-shot" data-act="evsnap" data-evid="' + ev.id +
                    '" title="' + esc(fmtTime(ev.createdAt) + ' · ' + (ev.typeName || ev.type)) + '">' +
                    '<img src="' + esc(withToken(snapFullUrl(ev.snapshotUrl))) + '" alt="画面变动快照" loading="lazy">' +
                    '<span class="cam-shot-time">' + esc(fmtShotTime(ev.createdAt)) + '</span>' +
                    '</button>';
            }).join('');
        });
    }

    async function loadCamEvents() {
        try {
            var data = await api('api/events/recent?perDevice=' + CAM_SHOTS);
            state.camRecent = data || {};
            var idx = {};
            Object.keys(state.camRecent).forEach(function (did) {
                (state.camRecent[did] || []).forEach(function (ev) { idx[ev.id] = ev; });
            });
            state.camRecentIndex = idx;
            var sig = Object.keys(state.camRecent).map(function (did) {
                return did + ':' + (state.camRecent[did] || []).map(function (ev) { return ev.id; }).join(',');
            }).join('|');
            if (sig === state.camRecentSig) return; // 无新事件，避免图片重加载闪烁
            state.camRecentSig = sig;
            renderCamEvents();
        } catch (e) {
            // 静默降级：仅把仍停在「加载中」的卡片置为空态，不打断设备列表
            Array.prototype.forEach.call(document.querySelectorAll('.cam-events-body[data-camev]'), function (box) {
                if (box.textContent.indexOf('加载中') >= 0) {
                    box.innerHTML = '<div class="cam-events-empty">暂无画面变动快照</div>';
                }
            });
        }
    }

    /** v2.3.0 摄像头卡快照：随概览 30 秒刷新；偏好存本浏览器，设置里可关。 */
    var CARD_SNAP_KEY = 'ainvr_card_snap';
    function cardSnapOn() {
        try { return localStorage.getItem(CARD_SNAP_KEY) !== 'off'; } catch (e) { return true; }
    }
    function refreshCardSnaps() {
        if (!cardSnapOn() || $('tab-cameras').hidden) { return; }
        Array.prototype.forEach.call(document.querySelectorAll('.cam-media-img[data-snap-did]'), function (img) {
            var did = img.getAttribute('data-snap-did');
            var dev = null;
            state.devices.forEach(function (c) { if (String(c.did) === String(did)) { dev = c; } });
            if (!dev || !dev.online) { img.hidden = true; return; }
            // v2.3.1 休眠中不再轮询快照（无新帧且避免为休眠摄像头反复建流订阅），保留最近画面
            if (dev.sleeping === true) { img.hidden = false; return; }
            img.hidden = false;
            img.onerror = function () { img.hidden = true; };
            img.src = withToken('api/snapshot/' + encodeURIComponent(did) + '?t=' + Date.now());
        });
    }
    function applyCardSnapPref() {
        var on = cardSnapOn();
        Array.prototype.forEach.call(document.querySelectorAll('.cam-media-img[data-snap-did]'), function (img) {
            img.hidden = !on;
        });
        if (on) { refreshCardSnaps(); }
    }
    /** v2.3.1 导航未处理事件角标（侧栏 + 底部栏同步）。 */
    var lastUnhandledCount = 0;
    function updateEvBadge(n) {
        lastUnhandledCount = n || 0;
        ['navEvBadge', 'navEvBadgeM'].forEach(function (id) {
            var b = $(id);
            if (!b) { return; }
            b.hidden = !n;
            b.textContent = n > 99 ? '99+' : String(n || '');
        });
        trimApplyTitle(lastUnhandledCount);
    }
    /* ---------- v2.3.0 主题：v2.3.1 起三态（深色 / 浅色 / 跟随系统），默认跟随系统 ---------- */
    /** 用户选择的主题（原始值；auto = 跟随系统）。未选择过时默认 auto。 */
    function storedTheme() {
        try {
            var v = localStorage.getItem('ainvr_theme');
            return (v === 'light' || v === 'dark' || v === 'auto') ? v : 'auto';
        } catch (e) { return 'auto'; }
    }
    /** 解析为实际生效的深浅色：显式值直通；auto 优先飞牛桌面外观，独立浏览器回落系统偏好。 */
    function resolveTheme(raw) {
        if (raw === 'light' || raw === 'dark') { return raw; }
        if (trimState && typeof trimState.theme === 'string') { return trimState.theme; }
        try {
            if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) return 'light';
        } catch (e) { /* 老浏览器无该 API：回落深色 */ }
        return 'dark';
    }
    function currentTheme() { return resolveTheme(storedTheme()); }
    function applyTheme(raw) {
        var t = resolveTheme(raw);
        var root = document.documentElement;
        if (t === 'light') { root.setAttribute('data-theme', 'light'); } else { root.removeAttribute('data-theme'); }
        var m = document.querySelector('meta[name="theme-color"]');
        if (m) { m.setAttribute('content', t === 'light' ? '#eef1f6' : '#0e1116'); }
        document.querySelectorAll('input[name="uiTheme"]').forEach(function (r) { r.checked = r.value === raw; });
        var lb = $('btnThemeLabel');
        if (lb) {
            // 快捷按钮仅深/浅两态（「跟随系统」只在设置页提供）；auto 下按生效主题取反
            var next = currentTheme() === 'dark' ? 'light' : 'dark';
            lb.textContent = next === 'light' ? '浅色模式' : '深色模式';
        }
        try { localStorage.setItem('ainvr_theme', raw); } catch (e2) { /* 存储不可用忽略 */ }
    }

    /** 「查看全部」：切到事件告警页并按该摄像头筛选（用 pending 避免与 tab 自带加载打架）。 */
    function gotoCamEvents(did) {
        state.evPendingDid = String(did);
        var tab = document.querySelector('.tab[data-tab="events"]');
        if (tab) tab.click();
    }

    /** v2.3.1 事件页「看回放」：定位待办由 applyPbPending 消费；卡片回放按钮已移除（回放入口收敛到导航 tab）。 */

    /* ---------- v2.3.1 录像文件弹层（唯一入口=摄像头卡片；设置页提供全部摄像头管理） ---------- */
    function openFilesModal(did) {
        var locked = did != null && did !== '';
        $('filesModal').hidden = false;
        $('filesModal').classList.toggle('files-locked', locked);
        var sel = $('recFilter');
        var chip = $('recLockChip');
        if (locked) {
            var v = String(did);
            var has = Array.prototype.some.call(sel.options, function (o) { return o.value === v; });
            if (!has) {
                var opt = document.createElement('option');
                opt.value = v;
                opt.textContent = cameraName(v);
                sel.appendChild(opt);
            }
            sel.value = v;
            chip.textContent = '仅看：' + cameraName(v) + ' ✕';
            chip.hidden = false;
            state.recSelected.clear(); // 换锁定对象后清空勾选，避免误删看不见的文件
        } else {
            chip.hidden = true;
            sel.value = '';
        }
        state.filesLockedDid = locked ? String(did) : '';
        loadRecordings();
        enterOverlayHistory('files');
    }
    /** 关闭文件弹层（应用内触发：需要退掉弹层历史条目）。 */
    function closeFilesModal() {
        if ($('filesModal').hidden) return;
        $('filesModal').hidden = true;
        state.filesLockedDid = '';
        state.recSelected.clear();
        leaveOverlayHistory();
    }
    /** popstate / 门禁回锁路径：历史已处理，只关界面。 */
    function closeFilesModalQuiet() {
        $('filesModal').hidden = true;
        state.filesLockedDid = '';
        state.recSelected.clear();
    }

    /** v2.3.1 事件页「仅看」胶囊：从卡片进入 / 下拉筛选后显示，点击一键回全部。 */
    function updateEvLockChip() {
        var chip = $('evLockChip');
        if (!chip) return;
        var did = $('evFilterDid').value || '';
        if (did) {
            chip.textContent = '仅看：' + cameraName(did) + ' ✕';
            chip.hidden = false;
        } else {
            chip.hidden = true;
        }
    }

    /** 轮询只更新状态徽标、在线态与录像按钮，不重建卡片（避免按钮闪烁 / 点击中断）。 */
    function updateDeviceStates() {
        var grid = $('deviceGrid');
        state.devices.forEach(function (c) {
            var card = grid.querySelector('.cam-card[data-did="' + CSS.escape(c.did) + '"]');
            if (!card) {
                // 卡片不存在：可能是设备刚上线，也可能只是被当前筛选隐藏
                if (deviceMatchesFilter(c)) { renderDevices(); }
                return;
            }
            // v2.3.1 休眠态变化走整卡重渲染：卡顶唤醒入口 / 次行休眠切换按钮 / 徽标联动
            var slpNow = (c.online && c.sleeping === true && String(c.did).indexOf('#') < 0) ? '1' : '';
            if (card.getAttribute('data-sleeping') !== slpNow) { renderDevices(); return; }
            card.querySelector('.badges').innerHTML = statusBadges(c);
            applyOnline(card, c);
            var btn = card.querySelector('button[data-act="recstart"],button[data-act="recstop"]');
            if (btn) {
                btn.setAttribute('data-act', c.recording ? 'recstop' : 'recstart');
                btn.textContent = c.recording ? '停止录像' : '开始录像';
                btn.className = c.recording ? 'btn rec-on' : 'btn';
                btn.disabled = c.recording ? false : !c.online;
                btn.title = (!c.recording && !c.online) ? '设备离线，暂不可用' : '';
            }
        });
    }

    /** 同步在线徽标、卡片置灰与“实时预览”按钮禁用（离线不影响停止录像）。 */
    function applyOnline(card, c) {
        card.classList.toggle('offline', !c.online);
        // v2.3.1 用 onlineBadge 重新生成徽标：休眠态是「休眠」唤醒按钮，
        // 此前这里盲写成「在线/离线」，每 5s 轮询就把休眠徽标冲掉（状态跳在线根因）
        var net = card.querySelector('.net');
        if (net) {
            var html = onlineBadge(c);
            if (net.outerHTML !== html) { net.outerHTML = html; }
        }
        // v2.3.0 卡顶快照与操作区都是预览入口，一并启停（休眠中卡顶是唤醒入口，跳过）
        card.querySelectorAll('button[data-act="preview"]').forEach(function (pb) {
            pb.disabled = !c.online;
            pb.title = c.online ? '点击进入实时预览' : '设备离线，暂不可用';
        });
    }

    function findDevice(did) {
        return state.devices.find(function (c) { return String(c.did) === String(did); });
    }

    /** 摄像头展示名：有名称则“名称（DID）”，查不到名称时仅显示 DID。 */
    function cameraLabel(did) {
        var c = findDevice(did);
        return (c && c.name) ? (c.name + '（' + did + '）') : String(did);
    }

    /* ---------- v1.2.13 按摄像头分组展示的公共 helper ---------- */
    /** 分组标题用的设备名：优先取当前设备列表中的名称；历史/已移除设备明确标注。 */
    function cameraName(did) {
        var c = findDevice(did);
        if (c) return c.name ? c.name : String(did);
        return '已移除设备 ' + did;
    }

    /** 分组顺序：与「摄像头」页设备顺序一致，仅存在于历史数据中的设备排最后。 */
    function cameraGroupKeys(dids) {
        var known = (state.devices || []).map(function (c) { return String(c.did); });
        var out = known.filter(function (d) { return dids.indexOf(d) >= 0; });
        dids.forEach(function (d) { if (out.indexOf(d) < 0) out.push(d); });
        return out;
    }

    /** 摄像头快捷标签 HTML：全部 + 每台设备，带数量角标（counts 为全量统计）。 */
    function camChipsHtml(cur, counts) {
        var devs = (state.devices || []).map(function (c) {
            return { did: String(c.did), name: c.name || String(c.did) };
        });
        Object.keys(counts || {}).forEach(function (did) {
            if (!devs.some(function (d) { return d.did === did; })) {
                devs.push({ did: did, name: '已移除设备 ' + did });
            }
        });
        var html = '<button type="button" class="cam-chip' + (cur ? '' : ' active') +
            '" data-camdid="">全部摄像头</button>';
        devs.forEach(function (d) {
            var n = (counts && counts[d.did] != null) ? counts[d.did] : null;
            html += '<button type="button" class="cam-chip' + (String(cur) === d.did ? ' active' : '') +
                '" data-camdid="' + esc(d.did) + '" title="' + esc(d.did) + '">' +
                '<span>' + esc(d.name) + '</span>' +
                (n != null ? '<span class="cam-chip-n">' + n + '</span>' : '') + '</button>';
        });
        return html;
    }

    function bindCamChips(boxId, onPick) {
        var box = $(boxId);
        if (!box) return;
        box.querySelectorAll('.cam-chip').forEach(function (b) {
            b.addEventListener('click', function () { onPick(b.getAttribute('data-camdid') || ''); });
        });
    }

    /** 选中某摄像头筛选值；历史/已移除设备不在下拉里时动态补 option，避免选不中。 */
    function selectCamFilter(selId, did) {
        var sel = $(selId);
        if (!sel) return;
        if (did && !Array.prototype.some.call(sel.options, function (o) { return o.value === did; })) {
            var opt = document.createElement('option');
            opt.value = did;
            opt.textContent = cameraName(did) + ' (' + did + ')';
            sel.appendChild(opt);
        }
        sel.value = did;
    }

    /* 将秒数格式化为友好的分段时长（120 -> 2 分钟、90 -> 1 分 30 秒、10 -> 10 秒）。 */
    function formatSeg(sec) {
        sec = parseInt(sec, 10) || 0;
        if (sec > 0 && sec % 60 === 0) return (sec / 60) + ' 分钟';
        if (sec >= 60) return Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒';
        return sec + ' 秒';
    }

    /* ---------- 共享看板 ---------- */
    var BOARD_LAYOUTS = {
        grid4: { label: '4 宫格', slots: 4 },
        grid6: { label: '6 宫格（1 大 5 小）', slots: 6 },
        grid9: { label: '9 宫格', slots: 9 }
    };

    /** 基于 <base> 解析共享链接完整 URL（兼容 fnOS 网关前缀）。 */
    function boardShareUrl(token) {
        return new URL('share/' + token, document.baseURI).href;
    }

    /** 基于 <base> 解析 Webhook 完整地址。 */
    function webhookUrl() {
        return new URL('share-event', document.baseURI).href;
    }

    function layoutLabel(layout) {
        return (BOARD_LAYOUTS[layout] || { label: layout }).label;
    }

    async function loadBoards() {
        try {
            var data = await api('api/boards');
            state.boards = data.boards || [];
            renderBoards();
            state.lastLoads.boards = Date.now(); // 会话内一次；新建/删除看板后有各自的刷新入口
        } catch (e) {
            toast('看板加载失败：' + e.message, 'err');
        }
    }

    function renderBoards() {
        var grid = $('boardGrid');
        if (!state.boards.length) {
            grid.innerHTML = '<div class="empty">还没有共享看板，点击右上角“新建看板”创建</div>';
            return;
        }
        grid.innerHTML = state.boards.map(boardCardHtml).join('');
    }

    function boardCardHtml(b) {
        var statusBadge = b.enabled
            ? '<span class="badge live">启用中</span>'
            : '<span class="badge off">已停用</span>';
        var expire = b.expireAt
            ? '<span class="muted small">过期时间：' + fmtTime(b.expireAt) + '</span>'
            : '<span class="muted small">永久有效</span>';
        return ''
            + '<div class="cam-card board-card" data-id="' + esc(b.id) + '">'
            + '  <div class="cam-top"><div class="cam-name"><span class="nm">' + esc(b.name) + '</span>' + statusBadge + '</div></div>'
            + '  <div class="cam-model">' + esc(layoutLabel(b.layout)) + ' · ' + b.cameras.length + ' 路摄像头</div>'
            + '  <div class="board-cred-box">'
            + '    <div class="cred-item">'
            + '      <label>共享链接（免登录）</label>'
            + '      <div class="cred-row"><input type="text" readonly value="' + esc(boardShareUrl(b.shareToken)) + '">'
            + '      <button class="btn tiny" data-bact="copy-link" type="button">复制</button></div>'
            + '    </div>'
            + '    <div class="cred-item">'
            + '      <label>Webhook 密钥（请求头 X-Share-Secret）</label>'
            + '      <div class="cred-row"><input type="text" readonly value="' + esc(b.webhookSecret) + '">'
            + '      <button class="btn tiny" data-bact="copy-secret" type="button">复制</button></div>'
            + '    </div>'
            + '  </div>'
            + '  <details class="webhook-help">'
            + '    <summary>Webhook 调用方式</summary>'
            + '    <div class="help-body">'
            + '      <div class="help-label">请求方法与地址</div>'
            + '      <pre>POST ' + esc(webhookUrl()) + '</pre>'
            + '      <div class="help-label">请求头</div>'
            + '      <pre>X-Share-Secret: ' + esc(b.webhookSecret) + '\nContent-Type: application/json</pre>'
            + '      <div class="help-label">请求体（did 为小米 did，eventType 可选）</div>'
            + '      <pre>{"did": "1190193414", "eventType": "motion"}</pre>'
            + '      <p class="muted small help-note">触发后事件摄像头全屏（6 宫格进大格），默认 60 秒后自动复原；窗口内新事件覆盖并重新计时。</p>'
            + '    </div>'
            + '  </details>'
            + '  <div>' + expire + '</div>'
            + '  <div class="cam-actions">'
            + '    <button class="btn primary" data-bact="preview" type="button">预览</button>'
            + '    <button class="btn" data-bact="edit" type="button">编辑</button>'
            + '    <button class="btn" data-bact="toggle" type="button">' + (b.enabled ? '停用' : '启用') + '</button>'
            + '    <button class="btn soft" data-bact="rotate-token" type="button">换链接</button>'
            + '    <button class="btn soft" data-bact="rotate-secret" type="button">换密钥</button>'
            + '    <button class="btn soft-danger" data-bact="delete" type="button">删除</button>'
            + '  </div>'
            + '</div>';
    }

    /* ----- 编辑模态 ----- */
    function openBoardModal(id) {
        state.boardEditId = id || null;
        var b = id ? state.boards.find(function (x) { return x.id === id; }) : null;
        $('boardModalTitle').textContent = b ? '编辑共享看板' : '新建共享看板';
        $('boardName').value = b ? b.name : '';
        var layout = b ? b.layout : 'grid4';
        document.querySelectorAll('input[name="boardLayout"]').forEach(function (r) {
            r.checked = r.value === layout;
        });
        renderBoardSlots(layout, b ? b.cameras : null);
        $('boardExpire').value = (b && b.expireAt) ? toLocalInput(b.expireAt) : '';
        hideBoardError();
        $('boardModal').hidden = false;
    }

    function closeBoardModal() {
        $('boardModal').hidden = true;
        state.boardEditId = null;
    }

    function hideBoardError() {
        var box = $('boardError');
        box.hidden = true; box.textContent = '';
    }

    function showBoardError(m) {
        var box = $('boardError');
        box.hidden = false; box.textContent = m;
    }

    /** 按布局生成槽位下拉；selected 为已选 did 数组（按槽位索引），用于编辑或切换布局时保留。 */
    function renderBoardSlots(layout, selected) {
        var n = BOARD_LAYOUTS[layout].slots;
        var html = '';
        for (var i = 0; i < n; i++) {
            var slotName;
            if (layout === 'grid6') { slotName = i === 0 ? '大格（主画面）' : '小格 ' + i; }
            else { slotName = '槽位 ' + (i + 1); }
            var val = (selected && selected.length > i) ? selected[i] : '';
            html += '<div class="board-slot-row"><span>' + slotName + '</span><select class="select board-slot-sel">'
                + '<option value="">空置</option>'
                + state.devices.map(function (c) {
                    return '<option value="' + esc(c.did) + '"' + (c.did === val ? ' selected' : '') + '>'
                        + esc(c.name + '（' + c.did + '）') + '</option>';
                }).join('')
                + '</select></div>';
        }
        $('boardSlots').innerHTML = html;
    }

    async function saveBoard() {
        var name = $('boardName').value;
        var layout = document.querySelector('input[name="boardLayout"]:checked').value;
        var dids = Array.from(document.querySelectorAll('.board-slot-sel')).map(function (s) {
            return s.value || null;
        });
        var expireVal = $('boardExpire').value;
        var expireAt = expireVal ? new Date(expireVal).getTime() : null;

        if (!name.trim()) { showBoardError('看板名称不能为空'); return; }
        var payload = { name: name.trim(), layout: layout, cameras: dids, expireAt: expireAt };
        try {
            var path = state.boardEditId ? ('api/boards/' + encodeURIComponent(state.boardEditId)) : 'api/boards';
            await api(path, {
                method: state.boardEditId ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            closeBoardModal();
            await loadBoards();
            toast('看板已保存', 'ok');
        } catch (e) {
            showBoardError(e.message);
        }
    }

    async function handleBoardAction(btn) {
        var card = btn.closest('.board-card');
        var id = card.getAttribute('data-id');
        var b = state.boards.find(function (x) { return x.id === id; });
        var act = btn.getAttribute('data-bact');
        if (act === 'copy-link') { copyText(boardShareUrl(b.shareToken)); return; }
        if (act === 'copy-secret') { copyText(b.webhookSecret); return; }
        // v2.0.1 看板预览：直接新开免登录展示页，所见即观众所见
        if (act === 'preview') { window.open(boardShareUrl(b.shareToken), '_blank'); return; }
        if (act === 'edit') { openBoardModal(id); return; }
        try {
            if (act === 'toggle') {
                await api('api/boards/' + encodeURIComponent(id) + '/enabled', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: !b.enabled })
                });
                loadBoards();
            } else if (act === 'rotate-token') {
                await api('api/boards/' + encodeURIComponent(id) + '/rotate-token', { method: 'POST' });
                await loadBoards();
                toast('已生成新共享链接，旧链接失效', 'ok');
            } else if (act === 'rotate-secret') {
                await api('api/boards/' + encodeURIComponent(id) + '/rotate-secret', { method: 'POST' });
                await loadBoards();
                toast('已生成新 Webhook 密钥', 'ok');
            } else if (act === 'delete') {
                if (window.confirm('确定删除看板“' + b.name + '”？共享链接将立即失效。')) {
                    await api('api/boards/' + encodeURIComponent(id), { method: 'DELETE' });
                    await loadBoards();
                }
            }
        } catch (e) {
            toast('操作失败：' + e.message, 'err');
        }
    }

    function toLocalInput(ms) {
        var d = new Date(ms);
        function p(x) { return String(x).padStart(2, '0'); }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
            + 'T' + p(d.getHours()) + ':' + p(d.getMinutes());
    }

    /* ---------- 录像 ---------- */
    async function startRecord(did) {
        // 提示用当前生效的分段时长（设置页加载过则直接用缓存，否则实时取一次）
        var seg = state.settings && state.settings.segmentSec;
        if (seg == null) {
            try {
                state.settings = await api('api/settings');
                seg = state.settings.segmentSec;
            } catch (e) {
                seg = 120; // 仅用于提示文案兜底，实际录制时长以后端生效配置为准
            }
        }
        if (!confirm('确认开始录像？将按 ' + formatSeg(seg) + ' 分段保存为 MP4。')) return;
        try {
            await api('api/record/' + did + '/start', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
            });
            toast('录像已开始', 'ok');
            loadDevices(false); loadRecordings(true);
        } catch (e) { toast(e.message, 'err'); }
    }
    async function stopRecord(did) {
        try {
            var r = await api('api/record/' + did + '/stop', { method: 'POST' });
            toast('已停止，本段录像已保存（共 ' + (r.segments || 0) + ' 段）', 'ok');
            loadDevices(false); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }

    /** 录像列表 15s 客户端缓存：弹层反复开关 / 锁定切换时秒开，刷新按钮与批量操作后强制拉取。 */
    var recCache = { key: null, at: 0, data: null };

    async function loadRecordings(force) {
        var filter = $('recFilter').value || '';
        if (!force && recCache.data && recCache.key === filter && Date.now() - recCache.at < 15000) {
            applyRecList(recCache.data);
            return;
        }
        var url = 'api/recordings' + (filter ? '?did=' + encodeURIComponent(filter) : '');
        try {
            // 确保设备名已加载，录像列表才能显示“名称（DID）”而非裸 DID
            if (!state.devices.length) { try { await loadDevices(false); } catch (e2) { /* 忽略，退回显示 DID */ } }
            $('recList').innerHTML = '<tr><td colspan="6" class="empty">加载中…</td></tr>';
            var list = await api(url);
            recCache = { key: filter, at: Date.now(), data: list || [] };
            applyRecList(recCache.data);
        } catch (e) {
            $('recList').innerHTML = '<tr><td colspan="6" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
            state.recRows = new Map();
            updateBatchUi();
        }
    }

    function applyRecList(list) {
        state.recListCurrent = list || [];
        state.recRows = new Map(state.recListCurrent.map(function (r) {
            return [r.name, { size: r.size || 0, recording: !!r.recording }];
        }));
        // 刷新后剔除已被删除 / 已不在当前筛选结果里的勾选，避免误删看不见的文件
        Array.from(state.recSelected).forEach(function (n) {
            if (!state.recRows.has(n)) state.recSelected.delete(n);
        });
        renderRecordings(list);
    }
    /** v1.2.13 摄像头快捷标签：与 recFilter 下拉联动，点击即按该摄像头筛选。 */
    function renderRecChips() {
        var box = $('recChips');
        if (!box) return;
        box.innerHTML = camChipsHtml($('recFilter').value || '', state.recCounts);
        bindCamChips('recChips', function (did) {
            if (($('recFilter').value || '') === did) return;
            selectCamFilter('recFilter', did);
            state.recSelected.clear(); // 换设备后清空勾选，避免删到看不见的文件
            loadRecordings();
        });
    }

    /** 单个录像文件行；collapsed 控制整组折叠时不重建 DOM（勾选状态得以保留）。 */
    function recRowHtml(r, collapsed) {
        var sel = state.recSelected.has(r.name);
        // 录制中的分段由后端跳过，前端直接禁用勾选并说明原因，避免选了却没被处理
        var disabled = r.recording ? ' disabled title="该分段正在录制中，封段后可操作"' : '';
        var cls = [sel ? 'rec-selected' : '', collapsed ? 'is-collapsed' : ''].filter(Boolean).join(' ');
        return '<tr' + (cls ? ' class="' + cls + '"' : '') +
            ' data-recgroup-row="' + esc(String(r.did || '')) + '">' +
            '<td class="rec-check"><input type="checkbox" data-rsel="' + esc(r.name) + '"' +
                (sel ? ' checked' : '') + disabled + '></td>' +
            '<td class="fname">' + esc(r.name) + '</td>' +
            '<td>' + esc(cameraLabel(r.did)) + (r.recording ? ' <span class="badge rec">录制中</span>' : '') + '</td>' +
            '<td>' + fmtSize(r.size) + '</td>' +
            '<td>' + fmtTime(r.lastModified) + '</td>' +
            '<td class="acts">' +
            '<button class="btn tiny" data-ract="play" data-name="' + esc(r.name) + '">播放</button>' +
            '<a class="btn tiny" href="' + withToken('api/recordings/' + encodeURIComponent(r.name) + '?dl=1') + '" download>下载</a>' +
            '<button class="btn tiny sdk-only" data-ract="fopen" data-name="' + esc(r.name) +
                '" data-path="' + esc(r.path || '') + '">在文件管理打开</button>' +
            '<button class="btn tiny soft-danger" data-ract="del" data-name="' + esc(r.name) + '">删除</button>' +
            '</td></tr>';
    }

    /** v1.2.13 录像按摄像头分组：每组一个可折叠标题行（设备名 / 文件数 / 占用空间）。
     *  v2.3.1 增量渲染：先出一批行，底部「加载更多」续拉——上千个分段一次全部渲染
     *  （每行 4 个按钮）会阻塞主线程数秒，是录像文件页卡顿的主因。
     *  v2.3.1 二轮优化：折叠组零渲染——默认只渲染分组标题（“全部”多摄像头视图下
     *  默认全部收起），行只在组展开时才进 DOM；首屏行数 50→25、续拉 200→100。 */
    var REC_PAGE_FIRST = 25, REC_PAGE_MORE = 100;

    function recShownOf(did) { return state.recShown.get(did) || REC_PAGE_FIRST; }

    /** 分组是否折叠：显式收起 > 显式展开 > 默认（“全部”多摄像头视图收起，单摄像头筛选展开）。 */
    function recGroupCollapsed(did, totalGroups) {
        if (state.recCollapsed.has(did)) return true;
        if (state.recExpanded.has(did)) return false;
        return !$('recFilter').value && totalGroups > 1;
    }

    function renderRecordings(list) {
        var tb = $('recList');
        renderRecFilter();
        if (!list || !list.length) {
            tb.innerHTML = '<tr><td colspan="6" class="empty">暂无录像文件</td></tr>';
            renderRecChips();
            updateBatchUi();
            return;
        }
        var groups = new Map();
        list.forEach(function (r) {
            var k = String(r.did || '');
            if (!groups.has(k)) groups.set(k, []);
            groups.get(k).push(r);
        });
        // 仅在「全部摄像头」视图下刷新每台文件数；筛选视图保留全量角标
        if (!$('recFilter').value) {
            var counts = {};
            groups.forEach(function (rows, k) { counts[k] = rows.length; });
            state.recCounts = counts;
            renderRecFilter();
        }
        renderRecChips();
        var keys = cameraGroupKeys(Array.from(groups.keys()));
        state.recGroupCount = keys.length;
        tb.innerHTML = keys.map(function (did) {
            var rows = groups.get(did);
            var collapsed = recGroupCollapsed(did, keys.length);
            var bytes = rows.reduce(function (s, r) { return s + (r.size || 0); }, 0);
            var shown = recShownOf(did);
            var head = '<tr class="rec-group" data-recgroup="' + esc(did) + '"><td colspan="6">' +
                '<button type="button" class="cam-group-toggle" aria-expanded="' + (!collapsed) + '">' +
                    '<span class="cam-caret">' + (collapsed ? '▸' : '▾') + '</span>' +
                    '<span class="cam-group-name">' + esc(cameraName(did)) + '</span>' +
                    '<span class="cam-group-did">' + esc(did) + '</span>' +
                    '<span class="cam-group-count">' + rows.length + ' 个文件 · ' + fmtSize(bytes) +
                    (!collapsed && rows.length > shown ? ' · 已显示 ' + Math.min(shown, rows.length) : '') +
                    '</span>' +
                '</button></td></tr>';
            // v2.3.1 折叠组零渲染：收起的组完全不生成行 DOM，展开时才建
            var body = collapsed ? '' : rows.slice(0, shown).map(function (r) { return recRowHtml(r, false); }).join('');
            if (!collapsed && rows.length > shown) {
                body += '<tr class="rec-more-row"><td colspan="6">' +
                    '<button type="button" class="btn tiny" data-rmore="' + esc(did) + '">加载更多（还有 ' +
                    (rows.length - shown) + ' 个）</button></td></tr>';
            }
            return head + body;
        }).join('');
        tb.querySelectorAll('button[data-ract]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var name = btn.getAttribute('data-name');
                var act = btn.getAttribute('data-ract');
                if (act === 'play') openPlayer(name);
                else if (act === 'del') delRecording(name);
                else if (act === 'fopen') {
                    var p = btn.getAttribute('data-path');
                    if (p) trimOpenFile(p);
                }
            });
        });
        tb.querySelectorAll('button[data-rmore]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var did = btn.getAttribute('data-rmore');
                state.recShown.set(did, recShownOf(did) + REC_PAGE_MORE);
                renderRecordings(state.recListCurrent);
            });
        });
        tb.querySelectorAll('.rec-group').forEach(function (tr) {
            tr.querySelector('.cam-group-toggle').addEventListener('click', function () {
                var did = tr.getAttribute('data-recgroup');
                // v2.3.1 折叠组零渲染：行 DOM 随折叠状态增删，直接整表重渲染；
                // 勾选状态存于 recSelected，重渲染后自动还原
                var nowCollapsed = !recGroupCollapsed(did, state.recGroupCount || 1);
                if (nowCollapsed) { state.recCollapsed.add(did); state.recExpanded.delete(did); }
                else { state.recCollapsed.delete(did); state.recExpanded.add(did); }
                renderRecordings(state.recListCurrent);
            });
        });
        updateBatchUi();
    }

    /* ---------- v1.2.2 批量选择 / 批量删除 / 打包下载 ---------- */
    function recSelectableNames() {
        var out = [];
        state.recRows.forEach(function (meta, name) { if (!meta.recording) out.push(name); });
        return out;
    }
    function selectedRecBytes() {
        var total = 0;
        state.recSelected.forEach(function (n) {
            var m = state.recRows.get(n);
            if (m) total += m.size || 0;
        });
        return total;
    }
    function updateBatchUi() {
        var n = state.recSelected.size;
        var all = recSelectableNames();
        var allChecked = all.length > 0 && all.every(function (x) { return state.recSelected.has(x); });
        var box = $('recSelAll');
        box.checked = allChecked;
        box.indeterminate = n > 0 && !allChecked;
        box.disabled = all.length === 0;
        $('btnRecBatchZip').disabled = n === 0;
        $('btnRecBatchDel').disabled = n === 0;
        $('btnRecClearSel').hidden = n === 0;
        var info = $('recSelInfo');
        info.textContent = n ? ('已选 ' + n + ' 个文件 · 合计 ' + fmtSize(selectedRecBytes()))
            : '勾选文件后可批量打包下载或删除';
        info.className = 'rec-selinfo' + (n ? ' has-sel' : '');
    }
    /** 全选 / 清空后同步已渲染行的勾选与高亮，不重新拉列表。 */
    function syncRecChecks() {
        $('recList').querySelectorAll('input[data-rsel]').forEach(function (cb) {
            var on = state.recSelected.has(cb.getAttribute('data-rsel'));
            cb.checked = on;
            var tr = cb.closest('tr');
            if (tr) tr.classList.toggle('rec-selected', on);
        });
    }
    function fileStamp() {
        var d = new Date();
        function p(x) { return String(x).padStart(2, '0'); }
        return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate())
            + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
    }
    async function recBatchDelete() {
        var names = Array.from(state.recSelected);
        if (!names.length) return;
        if (!confirm('确认删除选中的 ' + names.length + ' 个录像文件（' + fmtSize(selectedRecBytes())
            + '）？\n此操作不可恢复；正在录制中的分段会自动跳过。')) return;
        var btn = $('btnRecBatchDel');
        btn.disabled = true;
        var deleted = 0, skipped = 0, failed = [];
        try {
            // 后端单次上限 500，超出时分批串行提交，避免一次请求超时
            for (var i = 0; i < names.length; i += 500) {
                var r = await api('api/recordings/batch/delete', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ names: names.slice(i, i + 500) })
                });
                deleted += r.deletedCount || 0;
                skipped += r.skippedCount || 0;
                failed = failed.concat(r.failed || []);
            }
            toast('已删除 ' + deleted + ' 个' + (skipped ? '，跳过录制中 ' + skipped + ' 个' : '')
                + (failed.length ? '，失败 ' + failed.length + ' 个' : ''), failed.length ? 'err' : 'ok');
            if (failed.length) console.warn('批量删除失败明细：', failed);
            state.recSelected.clear();
        } catch (e) {
            toast('批量删除失败：' + e.message, 'err');
        } finally {
            await loadRecordings(true);
            btn.disabled = false;
            updateBatchUi();
        }
    }
    async function recBatchZip() {
        var names = Array.from(state.recSelected);
        if (!names.length) return;
        if (names.length > 1000) { toast('单次最多打包 1000 个文件，请分批选择', 'err'); return; }
        var bytes = selectedRecBytes();
        if (bytes > 1073741824 && !confirm('所选文件约 ' + fmtSize(bytes)
            + '，浏览器需暂存整个压缩包，可能较慢或占用较多磁盘。继续打包？')) return;
        var btn = $('btnRecBatchZip');
        var label = btn.textContent;
        btn.disabled = true;
        btn.textContent = '打包中…';
        try {
            // 这里要拿 blob，不能直接走 api()（它会尝试按 JSON 解析响应体）
            var headers = { 'Content-Type': 'application/json' };
            var tk = getToken();
            if (tk) headers['Authorization'] = 'Bearer ' + tk;
            var res = await fetch('api/recordings/batch/download', {
                method: 'POST', headers: headers, body: JSON.stringify({ names: names })
            });
            if (!res.ok) {
                var msg = '打包失败 HTTP ' + res.status;
                try {
                    var data = await res.json();
                    if (data && data.error) msg = data.error;
                } catch (ignored) { /* 非 JSON 错误体 */ }
                if (res.status === 401) scheduleGateRelock();
                throw new Error(msg);
            }
            var blob = await res.blob();
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = 'ainvr-recordings-' + fileStamp() + '.zip';
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
            var count = parseInt(res.headers.get('X-aiNVR-Zip-Files') || names.length, 10);
            var skipped = parseInt(res.headers.get('X-aiNVR-Zip-Skipped') || '0', 10);
            toast('已打包 ' + count + ' 个文件' + (skipped ? '，跳过 ' + skipped + ' 个（录制中/已不存在）' : ''), 'ok');
        } catch (e) {
            toast('打包下载失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
            btn.textContent = label;
            updateBatchUi();
        }
    }
    function renderRecFilter() {
        var sel = $('recFilter');
        var cur = sel.value;
        var opts = '<option value="">全部摄像头</option>' +
            (state.devices || []).map(function (c) {
                return '<option value="' + esc(c.did) + '">' + esc(c.name) + ' (' + esc(c.did) + ')</option>';
            }).join('');
        // v1.2.13：历史录像可能属于已移除的设备，也补进下拉，保证快捷标签能选中
        var known = (state.devices || []).map(function (c) { return String(c.did); });
        Object.keys(state.recCounts || {}).forEach(function (did) {
            if (known.indexOf(did) < 0) {
                opts += '<option value="' + esc(did) + '">' + esc(cameraName(did)) + ' (' + esc(did) + ')</option>';
            }
        });
        if (sel.innerHTML !== opts) sel.innerHTML = opts;
        if (cur) sel.value = cur;
    }
    async function delRecording(name) {
        if (!confirm('确认删除录像文件？\n' + name)) return;
        try {
            await api('api/recordings/' + encodeURIComponent(name), { method: 'DELETE' });
            toast('已删除', 'ok'); loadRecordings(true);
        } catch (e) { toast(e.message, 'err'); }
    }

    /* ---------- 系统设置（存储路径 / 分段 / 端口 / 目录选择） ---------- */
    var FOLDER_SVG = '<svg class="dir-ico" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
        '<path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>';

    function setKv(k, v) {
        return '<div><span>' + esc(k) + '</span><b>' + esc(v) + '</b></div>';
    }

    /* v2.1.1 AI 模型按需下载：状态渲染 + 触发 + 进度轮询 */
    var aiModelPollTimer = null;
    function fmtModelBytes(n) {
        if (n == null || n < 0) return '';
        if (n >= 1048576) return (n / 1048576).toFixed(1) + 'MB';
        return Math.round(n / 1024) + 'KB';
    }
    function renderAiModel(ai) {
        var st = $('aiModelState');
        var hint = $('aiModelHint');
        var btn = $('btnAiDownload');
        var urlInput = $('setAiModelUrl');
        if (!st || !btn) return;
        if (urlInput && document.activeElement !== urlInput) {
            urlInput.value = (ai.modelUrl && ai.modelUrl !== ai.defaultModelUrl) ? ai.modelUrl : '';
            urlInput.placeholder = ai.defaultModelUrl
                ? '留空使用默认地址（GitHub，约 ' + fmtModelBytes(ai.modelBytes || 28900000) + '）'
                : '模型下载地址（可自定义，如 gitee 附件镜像）';
        }
        var stateText;
        var downloading = ai.modelState === 'downloading';
        if (downloading) {
            var pct = ai.downloadProgress != null && ai.downloadProgress >= 0
                ? ai.downloadProgress + '%' : fmtModelBytes(ai.downloadReceived);
            stateText = '模型下载中… ' + pct + '（'
                + fmtModelBytes(ai.downloadReceived) + (ai.downloadTotal > 0 ? ' / ' + fmtModelBytes(ai.downloadTotal) : '') + '）';
        } else if (ai.modelState === 'ready') {
            stateText = '模型已就绪（SSD-MobileNetV1，' + (ai.modelBundled ? '内置' : fmtModelBytes(ai.modelBytes))
                + '，存放于 ' + ai.modelPath + '）';
        } else if (ai.modelState === 'missing') {
            stateText = '模型未下载：启用 AI 检测后点击「下载模型」（约 28MB，下载后完全离线运行）。'
                + '也可手动把 ssd_mobilenet_v1_10.onnx 放到 ' + ai.modelPath + ' 所在目录。';
        } else {
            stateText = '模型状态异常（加载失败或文件损坏）：可重新下载，或检查 ' + ai.modelPath + '。';
        }
        if (ai.downloadError) {
            stateText += ' 上次下载失败：' + ai.downloadError;
        }
        st.textContent = stateText;
        btn.disabled = downloading;
        btn.textContent = downloading ? '下载中…' : (ai.modelState === 'missing' || ai.modelState === 'error' ? '下载模型' : '重新下载');
        // 下载中每 2 秒轮询刷新，结束后停表并恢复设置区
        if (downloading && !aiModelPollTimer) {
            aiModelPollTimer = setInterval(function () {
                loadSettings().catch(function () {});
            }, 2000);
        } else if (!downloading && aiModelPollTimer) {
            clearInterval(aiModelPollTimer);
            aiModelPollTimer = null;
        }
        if (hint) {
            hint.textContent = '默认地址为 ONNX Model Zoo 官方 GitHub（MIT 许可）；国内网络不稳时可在上方填入镜像地址（如 gitee 仓库附件）后点下载，地址会被记住。';
        }
    }

    async function loadSettings() {
        try {
            var s = await api('api/settings');
            state.settings = s;
            state.settingsLoadedAt = Date.now();
            $('setCurDir').textContent = s.recordingDir;
            $('setDefaultDir').textContent = s.defaultDir;
            $('setStats').innerHTML =
                '<span>录像文件 <b>' + (s.recordingCount || 0) + '</b> 个</span>' +
                '<span>录制中 <b>' + (s.activeCount || 0) + '</b> 路</span>' +
                '<span>可用空间 <b>' + fmtSize(s.freeSpace) + '</b> / ' + fmtSize(s.totalSpace) + '</span>';
            $('setMisc').innerHTML = setKv('程序数据目录', s.dataDir);
            $('setError').hidden = true;
            $('setError').textContent = '';
            // 未选择新目录前禁用保存
            $('setDirInput').value = '';
            $('btnSaveDir').disabled = true;
            // v2.3.1 事件快照归档
            renderSnapshotArchive(s);
            // v2.3.1 Home Assistant（MQTT）
            renderMqtt(s);
            // 分段时长
            $('setSegmentInput').value = s.segmentSec;
            $('setSegmentHint').textContent = (s.segmentCustomized ? '当前为自定义值，默认 ' + s.defaultSegmentSec + ' 秒。' : '当前为默认值。')
                + '保存后正在录制的分段会封段并按新时长续录。分段越小文件越多（默认 2 分钟 ≈ 每天 720 个/路），'
                + '觉得文件太碎可在保存后调大到 300～1800 秒；分段时长不影响运动侦测自动录像的成段逻辑。';
            // 本地 AI 检测
            var ai = s.ai || {};
            $('setAiEnabled').checked = ai.enabled !== false;
            $('setAiMinScore').value = ai.minScore != null ? ai.minScore : 0.5;
            $('setAiHint').textContent = '置信度阈值 0.3～0.9，低于阈值的目标不计类型。'
                + (ai.pending > 0 ? ' 待分类 ' + ai.pending + ' 条。' : '');
            renderAiModel(ai);
            // RTSP 端口
            $('setRtspInput').value = s.rtspPort;
            $('setRtspHint').textContent = '当前为' + (s.rtspCustomized ? '自定义' : '默认') + '端口，默认 ' + s.defaultRtspPort
                + '。保存后立即切换，现有 RTSP 播放器会短暂断开，需用新地址重连。';
            // v1.2.22 RTSP 访问令牌
            $('setRtspTokenInput').value = '';
            $('setRtspTokenHint').textContent = s.rtspTokenSet
                ? '已启用令牌：RTSP 地址需带 ?t=令牌（设备卡片已自动拼接，可直接复制）。'
                : '未启用令牌：任何能访问该端口的播放器都可以直接取流。';
            // 录像保留天数
            $('setRetentionInput').value = s.retentionDays;
            $('setRetentionHint').textContent = (s.retentionDays > 0)
                ? '当前保留最近 ' + s.retentionDays + ' 天，超期录像每小时自动清理（'
                    + (s.retentionCustomized ? '自定义值' : '默认值') + '，默认 ' + s.defaultRetentionDays + ' 天）。'
                : '当前为永久保存，不自动清理（0 表示永久；默认值为 ' + s.defaultRetentionDays + ' 天）。';
            // v1.2.21 容量水位
            $('setWaterInput').value = (s.waterPercent == null ? 0 : s.waterPercent);
            // HLS 预览参数
            var h = s.hls || {};
            $('setHlsTargetInput').value = h.targetSec;
            $('setHlsWindowInput').value = h.window;
            $('setHlsIdleInput').value = h.idleSec;
            $('setHlsAudioInput').checked = !!h.audio;
            $('setHlsPrewarmInput').checked = !!h.prewarm;
            $('setHlsHint').textContent = '默认值：分片 ' + h.defaultTargetSec + 's、窗口 '
                + h.defaultWindow + '、空闲 ' + h.defaultIdleSec + 's、音频 ' + h.defaultAudio
                + '、预热 ' + h.defaultPrewarm + '。';
            // 共享看板参数
            var sh = s.share || {};
            $('setShareEventInput').value = sh.eventDurationSec;
            $('setShareHeartbeatInput').value = sh.sseHeartbeatSec;
            $('setShareReloadInput').value = sh.pageReloadSec;
            $('setSharePollInput').value = sh.configPollSec;
            $('setShareHint').textContent = '默认值：事件 ' + sh.defaultEventDurationSec + 's、心跳 '
                + sh.defaultSseHeartbeatSec + 's、兜底重载 ' + sh.defaultPageReloadSec + 's、轮询 '
                + sh.defaultConfigPollSec + 's。';
            // 按设备取流清晰度 / 云台控制
            renderQualitySettings(s.quality);
            // 开放接口 API 密钥状态（独立接口，失败不阻塞其余设置项）
            loadApiKey();
            // v1.2.8 运动侦测与自动录像（独立接口，失败不阻塞其余设置项）
            loadMotionSettings();
            // v1.2.19 事件通知推送（独立接口，失败不阻塞其余设置项）
            loadNotifySettings();
        } catch (e) {
            if (e.status === 401) return; // 门禁失效由全局逻辑切回解锁页
            toast('加载设置失败：' + e.message, 'err');
        }
    }

    /* ---------- 按设备取流清晰度 / 云台控制 ---------- */
    function renderQualitySettings(q) {
        var box = $('setQualityList');
        if (!box) return;
        var opts = (q && q.options) || [];
        var list = (q && q.devices) || [];
        $('setPtzMode').value = (q && q.ptzMode) || 'auto';
        $('setQualityHint').textContent = '设备由离线转在线、局域网 CS2 服务恢复可达或云端 IP 变化时，'
            + '系统会自动打断重连退避并立即重建取流，无需手动刷新页面。';
        if (!list.length) {
            box.innerHTML = '<div class="q-empty">尚未发现摄像头，请先在「设备」页刷新设备列表。</div>';
            return;
        }
        box.innerHTML = list.map(function (d, idx) {
            var reach = d.lanReachable === true ? '<span class="q-state ok">局域网可达</span>'
                : d.lanReachable === false ? '<span class="q-state bad">局域网不可达</span>'
                    : '<span class="q-state">探测中</span>';
            var stream = d.streamState === '有画面' ? '<span class="q-state ok">有画面</span>'
                : '<span class="q-state">' + esc(d.streamState || '未取流') + '</span>';
            var sel = '<select data-q-did="' + esc(d.did) + '">' + opts.map(function (o) {
                return '<option value="' + esc(o.value) + '"' + (o.value === d.quality ? ' selected' : '')
                    + '>' + esc(o.label) + '</option>';
            }).join('') + '</select>';
            var lensTag = d.secondaryLens ? '<span class="q-state">副镜头</span>' : '';
            // v2.3.1 固定编号：主镜头显示序号 + ↑↓ 调序（副镜头随主镜头移动，不单独给按钮）
            var noTag = d.secondaryLens ? '' : '<span class="q-no">#' + (idx + 1) + '</span>';
            var moveBtns = d.secondaryLens ? ''
                : '<span class="q-move"><button class="btn tiny" type="button" data-q-up="' + esc(d.did)
                    + '" title="上移"' + (idx === 0 ? ' disabled' : '') + '>↑</button>'
                    + '<button class="btn tiny" type="button" data-q-down="' + esc(d.did)
                    + '" title="下移"' + (idx >= list.length - 1 ? ' disabled' : '') + '>↓</button></span>';
            // 双摄第二镜头开关：auto 按型号识别 / on 手动开启 / off 手动关闭
            var lensSel = '';
            if (!d.secondaryLens) {
                var cur = d.dualLensOverride === true ? 'on'
                    : d.dualLensOverride === false ? 'off' : 'auto';
                lensSel = '<select data-dl-did="' + esc(d.did) + '" title="双摄第二镜头">'
                    + '<option value="auto"' + (cur === 'auto' ? ' selected' : '') + '>副镜头：自动'
                    + (d.dualLensAuto ? '（本机型支持）' : '') + '</option>'
                    + '<option value="on"' + (cur === 'on' ? ' selected' : '') + '>副镜头：开启</option>'
                    + '<option value="off"' + (cur === 'off' ? ' selected' : '') + '>副镜头：关闭</option>'
                    + '</select>';
            }
            return '<div class="q-row"><div>' +
                '<div class="q-name">' + noTag + esc(d.name || d.did) + lensTag + reach + stream + moveBtns + '</div>' +
                '<div class="q-meta">' + esc(d.did) + ' · ' + esc(d.model)
                + ' · 型号默认档位 ' + esc(d.modelDefault) + '</div>' +
                '</div><div class="set-edit">' + lensSel + sel +
                '<button class="btn primary" type="button" data-q-save="' + esc(d.did) + '">应用</button>' +
                '</div></div>';
        }).join('');
    }

    /** v2.3.1 保存摄像头顺序：副镜头随主镜头一起移动。 */
    async function saveDeviceOrder(dids) {
        try {
            var s = await api('api/settings/device-order', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ order: dids })
            });
            state.settings = s;
            renderQualitySettings(s.quality);
            loadDevices(true); // 摄像头页 / 宫格 / 回放下拉立即按新顺序重排
            toast('顺序已保存并固定', 'ok');
        } catch (e) {
            toast('保存顺序失败：' + e.message, 'err');
        }
    }

    /** 上移 / 下移一台摄像头（连同其副镜头）。 */
    function moveDevice(did, dir) {
        var list = (state.settings && state.settings.quality && state.settings.quality.devices) || [];
        var dids = list.map(function (d) { return d.did; });
        var baseOrder = [], companion = {};
        dids.forEach(function (d) {
            if (/#2$/.test(d)) { companion[d.replace(/#2$/, '')] = d; }
            else { baseOrder.push(d); }
        });
        var idx = baseOrder.indexOf(did);
        var ni = idx + dir;
        if (idx < 0 || ni < 0 || ni >= baseOrder.length) { return; }
        var t = baseOrder[idx]; baseOrder[idx] = baseOrder[ni]; baseOrder[ni] = t;
        var full = [];
        baseOrder.forEach(function (b) { full.push(b); if (companion[b]) { full.push(companion[b]); } });
        saveDeviceOrder(full);
    }

    async function saveDualLens(did, value) {
        try {
            var enabled = value === 'auto' ? null : value === 'on';
            var s = await api('api/settings/dual-lens', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ did: did, enabled: enabled })
            });
            state.settings = s;
            renderQualitySettings(s.quality);
            var msg = enabled === null ? '副镜头已恢复按型号自动识别'
                : enabled ? '副镜头已开启，设备列表将出现「（副镜头）」摄像头'
                    : '副镜头已关闭';
            toast(msg, 'ok');
        } catch (e) {
            toast('副镜头设置失败：' + e.message, 'err');
        }
    }

    async function saveQuality(did, value) {
        try {
            var s = await api('api/settings/quality', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ did: did, quality: value })
            });
            // v2.3.1 提速后端只返回 quality + reconnected，合并进缓存而非整体覆盖
            state.settings = Object.assign({}, state.settings, s);
            renderQualitySettings(s.quality);
            var msg = s.reconnected ? '清晰度已应用，正在重建取流连接' : '清晰度已保存，下次取流时生效';
            setPtzStatus(msg + '（画面会短暂中断数秒）', s.reconnected ? 'warn' : '');
            toast(msg, 'ok');
        } catch (e) {
            toast('保存失败：' + e.message, 'err');
        }
    }

    async function savePtzMode() {
        try {
            var s = await api('api/settings/ptz', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mode: $('setPtzMode').value })
            });
            state.settings = s;
            toast('云台命令格式已保存', 'ok');
        } catch (e) {
            toast('保存失败：' + e.message, 'err');
        }
    }

    function setPtzStatus(text, kind) {
        var el = $('ptzStatus');
        if (!el) return;
        el.textContent = text;
        el.className = 'ptz-status small' + (kind ? ' ' + kind : '');
    }

    /* ---------- v1.2.1 低延迟：主动追到直播边缘 ----------
       HLS 播放器会累积缓冲，看门狗原先要落后 15s 才追帧。云台转动是「此刻」发生的，
       若画面还停在 10 秒前的缓冲里，用户就会觉得按了方向键半天没反应。这里在命令发出的
       同时把播放位置拉到 liveSyncPosition（原生 HLS 用 seekable.end），把等待压到约 1 个分片。 */
    function chaseLiveEdge(quiet) {
        var v = $('previewVideo');
        if (!v || $('previewModal').hidden) { return false; }
        var target = null;
        try {
            if (state.hls && isFinite(state.hls.liveSyncPosition)) {
                target = state.hls.liveSyncPosition;
            } else if (v.seekable && v.seekable.length) {
                target = v.seekable.end(v.seekable.length - 1) - 0.3;
            }
        } catch (e) { return false; }
        if (target == null || !isFinite(target) || target <= 0) { return false; }
        var lag = target - v.currentTime;
        if (lag < 0.35) { return false; }
        try {
            v.currentTime = Math.max(0, target - 0.1);
            if (!quiet) { toast('已跳到最新画面（追回 ' + lag.toFixed(1) + 's）', 'ok'); }
            return true;
        } catch (e) { return false; }
    }

    function currentLatency() {
        if (state.hls && isFinite(state.hls.latency)) { return state.hls.latency; }
        var v = $('previewVideo');
        try {
            if (v && v.seekable && v.seekable.length) {
                return Math.max(0, v.seekable.end(v.seekable.length - 1) - v.currentTime);
            }
        } catch (e) { /* 原生 HLS 可能拿不到 seekable */ }
        return null;
    }

    function updateLiveBadge() {
        var el = $('liveBadge');
        if (!el) { return; }
        var txt = el.querySelector('span');
        if (!txt) { return; }
        if (state.compatTimer) {
            el.className = 'live-badge idle';
            txt.textContent = '兼容画面';
            return;
        }
        var lat = currentLatency();
        if (lat == null) {
            el.className = 'live-badge idle';
            txt.textContent = '直播';
        } else {
            el.className = 'live-badge' + (lat > 6 ? ' laggy' : '');
            txt.textContent = '延迟 ' + lat.toFixed(1) + 's';
        }
    }

    function startLiveBadge() {
        stopLiveBadge();
        updateLiveBadge();
        state.liveBadgeTimer = setInterval(updateLiveBadge, 1000);
    }

    function stopLiveBadge() {
        if (state.liveBadgeTimer) { clearInterval(state.liveBadgeTimer); state.liveBadgeTimer = null; }
        var el = $('liveBadge');
        if (el) {
            el.className = 'live-badge idle';
            var txt = el.querySelector('span');
            if (txt) { txt.textContent = '直播'; }
        }
    }

    /* 打开预览时拉取该设备的清晰度档位、当前配置、推荐云台格式与取流状态。 */
    async function loadPreviewControls(did) {
        setPtzStatus('正在读取设备控制信息…');
        loadPreviewPresets(did);
        try {
            var c = await api('api/ptz/' + encodeURIComponent(did) + '/capabilities');
            $('previewQuality').innerHTML = (c.qualityOptions || []).map(function (o) {
                return '<option value="' + esc(o.value) + '"' + (o.value === c.quality ? ' selected' : '')
                    + '>' + esc(o.label) + '</option>';
            }).join('');
            $('previewPtzMode').value = c.mode || 'auto';
            setPtzStatus('型号 ' + esc(c.model) + ' · 默认档位 ' + esc(c.modelDefault)
                + ' · ' + esc(c.sessionHealthy ? '取流正常' : (c.streamState || '未取流'))
                + ' · 推荐云台格式 ' + esc(c.recommendedMode || 'direction')
                + '；点方向键后会自动追到最新画面。');
        } catch (e) {
            setPtzStatus('控制信息加载失败：' + e.message, 'warn');
        }
    }

    /* 云台预置位（米家「常看位置」）：打开预览时异步加载；固件不支持/未设置时隐藏该区。 */
    async function loadPreviewPresets(did) {
        var field = $('presetField');
        try {
            var r = await api('api/ptz/' + encodeURIComponent(did) + '/presets');
            var list = r.presets || [];
            if (!list.length) { field.hidden = true; return; }
            $('previewPreset').innerHTML = list.map(function (p) {
                return '<option value="' + esc(p.location) + '">'
                    + esc(p.name || ('位置 ' + p.location)) + '</option>';
            }).join('');
            field.hidden = false;
        } catch (e) {
            field.hidden = true;
        }
    }

    async function gotoPreset(did) {
        var loc = parseInt($('previewPreset').value, 10);
        if (isNaN(loc)) { return; }
        var btn = $('btnGotoPreset');
        btn.disabled = true;
        setPtzStatus('正在转到预置位…', 'ok');
        beginPtzCompat();
        try {
            await api('api/ptz/' + encodeURIComponent(did) + '/preset', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ location: loc })
            });
            setPtzStatus('已调用预置位，画面正在转动', 'ok');
            setTimeout(function () { chaseLiveEdge(true); }, 1200);
        } catch (e) {
            setPtzStatus('预置位调用失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
            updateLiveBadge();
        }
    }

    /* 设备 0x113 应答由后端命令通道异步记录；这里延时回读，不阻塞按钮反馈。
       连续点击时用 ptzSeq 让上一轮的轮询自动作废，避免旧结果覆盖新状态。 */
    function pollMotorReply(did, seq, attempt) {
        if (seq !== state.ptzSeq || state.previewDid !== did) { return; }
        setTimeout(async function () {
            if (seq !== state.ptzSeq || state.previewDid !== did) { return; }
            try {
                var c = await api('api/ptz/' + encodeURIComponent(did) + '/capabilities');
                if (seq !== state.ptzSeq) { return; }
                if (c.replyFresh && c.lastMotorReply) {
                    setPtzStatus('设备已应答（命令被识别）：' + String(c.lastMotorReply).slice(0, 120), 'ok');
                } else if (attempt < 2) {
                    pollMotorReply(did, seq, attempt + 1);
                } else {
                    setPtzStatus('命令已发出、画面已追到最新；设备未返回 0x113 应答。'
                        + '部分机型静默执行属正常，若画面未转动可切换云台格式重试。', 'warn');
                }
            } catch (e) { /* 查询失败不影响已发出的命令，保留「已发出」状态 */ }
        }, attempt === 1 ? 450 : 900);
    }

    /* 云台命令复用当前预览会话；没有会话时服务端返回 503，明确提示而不新建连接。
       v1.2.1：服务端发完即返回（不再等 800ms 设备应答），点击瞬间就有反馈并立刻追帧；
       设备应答改为异步回读，避免「画面已经在转、界面还在转圈」。 */
    async function ptzMove(direction) {
        var did = state.previewDid;
        if (!did) { toast('请先打开实时预览', 'err'); return; }
        var speed = parseInt($('ptzSpeed').value, 10) || 5;
        var mode = $('previewPtzMode').value || 'auto';
        var seq = ++state.ptzSeq;
        var label = { left: '左', right: '右', up: '上', down: '下', stop: '停止' }[direction] || direction;

        var key = $('ptzPad').querySelector('[data-dir="' + direction + '"]');
        if (key) {
            key.classList.add('fired');
            setTimeout(function () { key.classList.remove('fired'); }, 180);
        }
        setPtzStatus('云台「' + label + '」已发出，正在同步最新画面…', 'ok');
        // v1.2.8：操作期间临时用常驻 JPEG 帧显示，避开 HLS 的 GOP 等待；停止操作后自动切回
        beginPtzCompat();
        // 立即丢弃滞后缓冲；1.2s 后再追一次，覆盖「追帧时新分片刚好还没生成」的情况
        chaseLiveEdge(true);
        setTimeout(function () { if (seq === state.ptzSeq) { chaseLiveEdge(true); } }, 1200);
        updateLiveBadge();

        try {
            var r = await api('api/ptz/' + encodeURIComponent(did), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ direction: direction, speed: speed, mode: mode })
            });
            if (seq !== state.ptzSeq) { return; }
            if (r.deviceReplied) {
                setPtzStatus('设备已应答（' + r.effectiveMode + '）：'
                    + String(r.deviceReply).slice(0, 120), 'ok');
            } else {
                pollMotorReply(did, seq, 1);
            }
        } catch (e) {
            var msg = e.status === 503 ? '画面尚未就绪，请等待预览出图后再控制云台'
                : ('云台控制失败：' + e.message);
            setPtzStatus(msg, 'warn');
            toast(msg, 'err');
        }
    }

    async function saveSettings(reset) {
        var val = reset ? '' : $('setDirInput').value.trim();
        if (!reset && !val) {
            toast('请先点击“浏览”选择一个目录', 'err');
            return;
        }
        if (!confirm(reset
            ? '确认恢复为默认存储目录？\n当前正在录制的分段会在封段后写入默认目录。'
            : '确认将录像存储路径修改为：\n' + val + '\n\n正在录制的分段会在当前段封段后写入新目录，已有录像不会自动移动。')) {
            return;
        }
        $('btnSaveDir').disabled = true;
        $('btnResetDir').disabled = true;
        try {
            var s = await api('api/settings', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ recordingDir: val })
            });
            toast('存储路径已应用：' + s.recordingDir, 'ok');
            $('setDirInput').value = '';
            await loadSettings();
            loadRecordings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            var box = $('setError');
            box.hidden = false;
            box.textContent = e.message;
        } finally {
            $('btnResetDir').disabled = false;
        }
    }

    async function saveSegment() {
        var sec = parseInt($('setSegmentInput').value, 10);
        if (isNaN(sec) || sec < 10 || sec > 3600) {
            toast('分段时长需在 10～3600 秒之间', 'err');
            return;
        }
        if (!confirm('确认将录像分段时长改为 ' + sec + ' 秒？\n正在录制的分段会正常封段后按新时长续录。')) return;
        var btn = $('btnSaveSegment');
        btn.disabled = true;
        try {
            var s = await api('api/settings/segment', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ segmentSec: sec })
            });
            toast('分段时长已保存：' + s.segmentSec + ' 秒', 'ok');
            await loadSettings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    async function saveRtsp() {
        var port = parseInt($('setRtspInput').value, 10);
        if (isNaN(port) || port < 1024 || port > 65535) {
            toast('RTSP 端口需在 1024～65535 之间', 'err');
            return;
        }
        if (!confirm('确认将 RTSP 端口改为 ' + port + '？\n现有 RTSP 播放器会断开，需用新端口重连。')) return;
        var btn = $('btnSaveRtsp');
        btn.disabled = true;
        try {
            var s = await api('api/settings/rtsp', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ rtspPort: port })
            });
            toast('RTSP 端口已切换为 ' + s.rtspPort, 'ok');
            await loadSettings();
            loadDevices(false); // 刷新设备卡片上的 RTSP 地址
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('切换失败：' + e.message, 'err');
            await loadSettings();
        } finally {
            btn.disabled = false;
        }
    }

    /** v1.2.22 RTSP 访问令牌：设置 / 清除（输入 "-"），立即生效并持久化。 */
    async function saveRtspToken() {
        var t = $('setRtspTokenInput').value.trim();
        var clearing = (t === '-' || t === '');
        if (clearing) {
            if (!confirm('确认清除 RTSP 访问令牌？清除后任何能访问该端口的播放器都可以直接取流。')) return;
            t = '-';
        } else if (t.length < 4 || t.length > 64 || /[\s/?#]/.test(t)) {
            toast('令牌需为 4～64 个字符且不含空格 / ? #', 'err');
            return;
        } else if (!confirm('确认设置 RTSP 访问令牌？设置后 RTSP 播放地址需带 ?t=' + t + ' 才能取流。')) {
            return;
        }
        var btn = $('btnSaveRtspToken');
        btn.disabled = true;
        try {
            var s = await api('api/settings/rtsp', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ rtspPort: parseInt($('setRtspInput').value, 10) || 8554, rtspToken: t })
            });
            toast(s.rtspTokenSet ? 'RTSP 访问令牌已生效，设备卡片的 RTSP 地址已自动带上令牌' : 'RTSP 访问令牌已清除', 'ok');
            $('setRtspTokenInput').value = '';
            await loadSettings();
            loadDevices(false);
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
            await loadSettings();
        } finally {
            btn.disabled = false;
        }
    }

    async function saveWater() {
        var pct = parseInt($('setWaterInput').value, 10);
        if (isNaN(pct) || pct < 0 || pct > 95) {
            toast('容量水位需在 0～95 之间（0 表示关闭）', 'err');
            return;
        }
        if (!confirm(pct === 0
            ? '确认关闭容量水位循环覆盖？'
            : '确认将容量水位设为 ' + pct + '%？\n分段总大小超过所在卷容量的 ' + pct + '% 时，将从最旧分段开始自动删除。')) return;
        var btn = $('btnSaveWater');
        btn.disabled = true;
        try {
            var s = await api('api/settings/water-level', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ waterPercent: pct })
            });
            toast(pct === 0 ? '已关闭容量水位循环覆盖' : '容量水位已保存：' + s.waterPercent + '%', 'ok');
            await loadSettings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    async function saveRetention() {
        var days = parseInt($('setRetentionInput').value, 10);
        if (isNaN(days) || days < 0 || days > 3650) {
            toast('保留天数需在 0～3650 之间（0 表示永久保存）', 'err');
            return;
        }
        if (!confirm(days === 0
            ? '确认设置为永久保存？\n将不再自动删除历史录像。'
            : '确认将录像保留天数改为 ' + days + ' 天？\n超过该天数的录像将被自动删除。')) return;
        var btn = $('btnSaveRetention');
        btn.disabled = true;
        try {
            var s = await api('api/settings/retention', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ retentionDays: days })
            });
            toast(days === 0 ? '已设置为永久保存' : '保留天数已保存：' + s.retentionDays + ' 天', 'ok');
            await loadSettings();
            loadRecordings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    async function saveHls() {
        var targetSec = parseInt($('setHlsTargetInput').value, 10);
        var window = parseInt($('setHlsWindowInput').value, 10);
        var idleSec = parseInt($('setHlsIdleInput').value, 10);
        if (isNaN(targetSec) || targetSec < 1 || targetSec > 10) { toast('HLS 分片时长需在 1～10 秒之间', 'err'); return; }
        if (isNaN(window) || window < 3 || window > 20) { toast('HLS 窗口分片数需在 3～20 之间', 'err'); return; }
        if (isNaN(idleSec) || idleSec < 10 || idleSec > 3600) { toast('HLS 空闲回收秒数需在 10～3600 之间', 'err'); return; }
        var audio = $('setHlsAudioInput').checked;
        var prewarm = $('setHlsPrewarmInput').checked;
        if (!confirm('确认保存 HLS 预览参数？\n分片时长/窗口/音频变更会让正在进行的预览短暂中断后自动恢复。')) return;
        var btn = $('btnSaveHls');
        btn.disabled = true;
        try {
            await api('api/settings/hls', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ targetSec: targetSec, window: window, idleSec: idleSec,
                    audio: audio, prewarm: prewarm })
            });
            toast('HLS 预览参数已保存并应用', 'ok');
            await loadSettings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    async function saveShare() {
        var eventSec = parseInt($('setShareEventInput').value, 10);
        var heartbeatSec = parseInt($('setShareHeartbeatInput').value, 10);
        var reloadSec = parseInt($('setShareReloadInput').value, 10);
        var pollSec = parseInt($('setSharePollInput').value, 10);
        if (isNaN(eventSec) || eventSec < 5 || eventSec > 3600) { toast('事件画面时长需在 5～3600 秒之间', 'err'); return; }
        if (isNaN(heartbeatSec) || heartbeatSec < 1 || heartbeatSec > 300) { toast('SSE 心跳周期需在 1～300 秒之间', 'err'); return; }
        if (isNaN(reloadSec) || reloadSec < 0 || reloadSec > 86400) { toast('大屏兜底重载周期需在 0～86400 秒之间', 'err'); return; }
        if (isNaN(pollSec) || pollSec < 5 || pollSec > 3600) { toast('看板配置轮询周期需在 5～3600 秒之间', 'err'); return; }
        if (!confirm('确认保存共享看板参数？')) return;
        var btn = $('btnSaveShare');
        btn.disabled = true;
        try {
            await api('api/settings/share', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ eventDurationSec: eventSec, sseHeartbeatSec: heartbeatSec,
                    pageReloadSec: reloadSec, configPollSec: pollSec })
            });
            toast('共享看板参数已保存并应用', 'ok');
            await loadSettings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    function showPwError(msg) {
        var box = $('setPwError');
        box.hidden = false;
        box.textContent = msg;
    }

    async function changeAccessPassword() {
        var oldPw = $('setPwOld').value;
        var newPw = $('setPwNew').value;
        var newPw2 = $('setPwNew2').value;
        $('setPwError').hidden = true;
        $('setPwError').textContent = '';
        if (!oldPw) { showPwError('请输入当前密码'); return; }
        if (!newPw || newPw.length < 6) { showPwError('新密码至少 6 位'); return; }
        if (newPw.length > 64) { showPwError('新密码不能超过 64 位'); return; }
        if (newPw.trim() === '') { showPwError('新密码不能全为空白字符'); return; }
        if (newPw !== newPw2) { showPwError('两次输入的新密码不一致'); return; }
        if (!confirm('确认修改门禁访问密码？\n修改后需用新密码解锁，原一键解锁链接将失效。')) return;
        var btn = $('btnSavePw');
        btn.disabled = true;
        try {
            var r = await api('api/settings/password', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ oldPassword: oldPw, newPassword: newPw })
            });
            $('setPwOld').value = ''; $('setPwNew').value = ''; $('setPwNew2').value = '';
            toast('门禁密码已修改，当前会话保持登录', 'ok');
            if (r.unlockPath) {
                try { prompt('密码已修改。新的一键解锁链接（原链接已失效，可复制保存）：', location.origin + r.unlockPath); } catch (e2) { /* 忽略弹窗受限场景 */ }
            }
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            showPwError(e.message || '修改失败');
        } finally {
            btn.disabled = false;
        }
    }

    /* ---------- 开放接口 API 密钥 ---------- */
    function showApiKeyError(msg) {
        var box = $('apiKeyError');
        if (!msg) { box.hidden = true; box.textContent = ''; return; }
        box.hidden = false;
        box.textContent = msg;
    }

    /** 按当前页面地址拼出开放接口完整 URL（兼容根路径与 fnOS 网关 /app/{appname}/ 部署）。 */
    function snapshotApiUrl() {
        var p = location.pathname;
        var dir = p.substring(0, p.lastIndexOf('/') + 1);
        return location.origin + dir + 'api/snapshot/摄像头DID';
    }

    function renderApiKey(s, freshPlain) {
        var html = s.configured
            ? setKv('密钥状态', '已配置 ' + esc(s.mask || ''))
                + setKv('配置时间', s.createdAt ? fmtTime(s.createdAt) : '-')
            : setKv('密钥状态', '未配置');
        $('apiKeyStatus').innerHTML = html;
        $('btnApiDelete').disabled = !s.configured;
        $('apiKeyCustomInput').value = '';
        showApiKeyError('');
        var freshBox = $('apiKeyFreshBox');
        if (freshPlain) {
            freshBox.hidden = false;
            $('apiKeyFreshText').value = freshPlain;
        } else {
            freshBox.hidden = true;
            $('apiKeyFreshText').value = '';
        }
        $('apiCurl').textContent = 'curl -H "X-API-Key: 你的密钥" "'
            + snapshotApiUrl() + '" --output snapshot.jpg';
    }

    async function loadApiKey() {
        try {
            var s = await api('api/settings/apikey');
            renderApiKey(s, null);
        } catch (e) {
            if (e.status === 401) { return; }
            toast('加载 API 密钥状态失败：' + e.message, 'err');
        }
    }

    async function generateApiKey() {
        if (!confirm('确认生成新的随机 API 密钥？\n现有密钥将立即失效，已使用旧密钥的外部系统需同步更换。')) return;
        try {
            var s = await api('api/settings/apikey/generate', { method: 'POST' });
            renderApiKey(s, s.apiKey);
            toast('新密钥已生成，请立即复制保存', 'ok');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('生成失败：' + e.message, 'err');
        }
    }

    async function saveApiKey() {
        var v = $('apiKeyCustomInput').value.trim();
        if (!v) { showApiKeyError('请输入自定义 API 密钥（至少 16 位）'); return; }
        if (!confirm('确认保存自定义 API 密钥？\n现有密钥将立即失效。')) return;
        try {
            var s = await api('api/settings/apikey', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ apiKey: v })
            });
            renderApiKey(s, s.apiKey);
            toast('自定义密钥已保存，请立即复制保存', 'ok');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            showApiKeyError(e.message || '保存失败');
        }
    }

    async function deleteApiKey() {
        if (!confirm('确认删除 API 密钥？\n删除后所有携带该密钥的开放接口调用都会返回 401。')) return;
        try {
            var s = await api('api/settings/apikey', { method: 'DELETE' });
            renderApiKey(s, null);
            toast('API 密钥已删除', 'ok');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('删除失败：' + e.message, 'err');
        }
    }

    /* ---------- v2.3.1 飞牛桌面 SDK 融合（@trimjs/web-app） ---------- */
    /* SDK 由 vendor/trimapp-bridge.js 以 ESM module 挂到 window.TrimApp 并派发
       trimapp:ready。独立浏览器访问时探测不到宿主（isStandaloneWeb=true），
       全部 sdk-only 能力保持隐藏，行为与旧版一致。 */
    var trimState = { probed: false, hostOk: false, app: null, theme: null };
    var trimCbs = [];

    /** 宿主就绪回调：宿主可用时以 SDK 实例调用 cb，否则以 null 调用（一次性探测，结果缓存）。 */
    function whenTrimHost(cb) {
        if (trimState.probed) { cb(trimState.hostOk ? trimState.app : null); return; }
        trimCbs.push(cb);
        if (trimCbs.length > 1) return; // 首个调用者负责探测
        function finish(ok, app) {
            trimState.probed = true;
            trimState.hostOk = ok;
            trimState.app = app || null;
            if (ok) {
                document.body.classList.add('sdk-host');
                trimStartThemeSync(app);
            }
            var cbs = trimCbs; trimCbs = [];
            cbs.forEach(function (f) { f(ok ? trimState.app : null); });
        }
        function boot() {
            var t;
            try { t = new window.TrimApp(); } catch (e) { finish(false, null); return; }
            var settled = false;
            var timer = setTimeout(function () { if (!settled) { settled = true; finish(false, null); } }, 12000);
            Promise.resolve(t.ready()).then(function () {
                if (settled) return;
                settled = true; clearTimeout(timer);
                // isStandaloneWeb=false ⇒ 运行在飞牛桌面 iframe / 手机 App WebView 宿主内
                finish(t.isStandaloneWeb === false, t);
            }).catch(function () {
                if (settled) return;
                settled = true; clearTimeout(timer);
                finish(false, null);
            });
        }
        if (window.TrimApp) { boot(); return; }
        var done = false;
        window.addEventListener('trimapp:ready', function () {
            if (done) return; done = true;
            if (window.TrimApp) boot();
            else finish(false, null);
        }, { once: true });
        // 桥接脚本缺失 / 加载失败兜底（正常部署 trimapp:ready 必然触发）
        setTimeout(function () {
            if (done || trimState.probed) return;
            done = true;
            finish(false, null);
        }, 8000);
    }

    /** 飞牛宿主外观主题同步：轮询 getPlatformConfig().theme（桌面切换外观后 ≤15s 生效），
     *  页面重新可见时立即刷新；主题设置为「跟随系统」时即时生效。 */
    function trimStartThemeSync(t) {
        var refresh = function () {
            Promise.resolve(t.getPlatformConfig()).then(function (c) {
                var th = c && c.theme === 'light' ? 'light' : 'dark';
                if (trimState.theme !== th) {
                    trimState.theme = th;
                    if (storedTheme() === 'auto') { applyTheme('auto'); }
                }
            }).catch(function () { /* 宿主不支持时静默：回落浏览器偏好 */ });
        };
        refresh();
        setInterval(refresh, 15000);
        document.addEventListener('visibilitychange', function () { if (!document.hidden) refresh(); });
    }

    /** v2.3.1 向飞牛宿主申请所选目录的存储授权（选完即授权，免去去系统设置手动开）：
     *  先按用户空间（authorizeUserFile），失败再按共享空间（authorizeSharedFile）。 */
    async function trimAuthorizePath(p) {
        var t = trimState.app;
        if (!t) { return false; }
        try { if (typeof t.authorizeUserFile === 'function') { await t.authorizeUserFile(p); return true; } } catch (e) { /* 继续尝试共享授权 */ }
        try { if (typeof t.authorizeSharedFile === 'function') { await t.authorizeSharedFile(p); return true; } } catch (e2) { /* 放弃，回落可写性校验 */ }
        return false;
    }

    /** 飞牛系统文件选择器选目录；返回绝对路径或 null（取消 / 拒绝 / 无宿主）。 */
    async function trimPickDir(title) {
        var t = trimState.app;
        if (!t) return null;
        try {
            var res = await t.pickUserFile({
                directory: true,
                multiple: false,
                creatable: true,
                title: title || '选择文件夹',
                okText: '选择此目录',
                sidebarGroup: ['myFiles', 'external', 'favorites', 'storage']
            });
            // 宿主返回形态不一：数组 / {data|paths|files:[...]} / {path:'...'}，逐层归一化
            var arr = Array.isArray(res) ? res
                : (res && (Array.isArray(res.data) ? res.data
                    : Array.isArray(res.paths) ? res.paths
                    : Array.isArray(res.files) ? res.files : null));
            if (!arr && res && typeof res.path === 'string') { arr = [res.path]; }
            if (Array.isArray(arr) && arr.length) {
                var first = arr[0];
                if (typeof first === 'string' && first) { return first; }
                if (first && typeof first.path === 'string' && first.path) { return first.path; }
            }
        } catch (e) { /* 用户取消或宿主拒绝：按未选择处理 */ }
        return null;
    }

    /** 在飞牛文件管理中打开路径（宿主不可用时 toast 提示）。 */
    function trimOpenFile(path) {
        var t = trimState.app;
        if (!t || !path) return;
        Promise.resolve(t.openFile(path)).catch(function (e) {
            toast('在文件管理中打开失败：' + (e && e.message ? e.message : path), 'err');
        });
    }

    /** 桌面标题跟随未处理事件数：宿主 setTitle + document.title 双保险（同文本去重）。 */
    var lastTrimTitle = null;
    function trimApplyTitle(unhandled) {
        var text = unhandled > 0 ? 'aiNVR（' + unhandled + ' 未处理）' : 'aiNVR';
        if (text === lastTrimTitle) return;
        lastTrimTitle = text;
        try { document.title = text; } catch (e) { /* ignore */ }
        whenTrimHost(function (t) {
            if (t) { Promise.resolve(t.setTitle(text)).catch(function () { /* ignore */ }); }
        });
    }

    /* ---------- 服务器目录选择弹窗 ---------- */
    var dirData = { current: null, parent: null, roots: [], entries: [] };
    var dirPicked = null;
    /** v2.3.1 目录弹窗目标：'recording'（录像存储）| 'archive'（快照归档）。 */
    var dirTarget = 'recording';

    function openDirModal(target) {
        dirTarget = target === 'archive' ? 'archive' : 'recording';
        dirPicked = null;
        $('dirTitle').textContent = dirTarget === 'archive' ? '选择快照归档目录（服务器）' : '选择录像存储目录（服务器）';
        $('dirConfirm').disabled = true;
        $('dirSelected').textContent = '未选择';
        $('dirModal').hidden = false;
        var archiveCfg = (state.settings && state.settings.snapshotArchive) || {};
        var start = dirTarget === 'archive'
            ? (archiveCfg.dir || '')
            : ((state.settings && state.settings.recordingDir) || '');
        // 飞牛宿主内提供系统文件选择器入口；独立浏览器保持隐藏（CSS sdk-only）
        whenTrimHost(function (t) {
            $('dirSysPick').hidden = !t;
            if (t) {
                $('dirStatus').textContent = '飞牛应用沙盒内仅能列出已授权目录，推荐点「系统文件选择器…」浏览全部共享目录，选完自动申请授权。';
            }
        });
        loadDir(start);
    }

    /** 把选定目录落到对应输入框并解锁保存按钮（系统选择器直达 / 弹窗确认共用）。 */
    function applyPickedDir(p) {
        if (dirTarget === 'archive') {
            $('setArchiveDirInput').value = p;
            $('btnSaveArchiveDir').disabled = false;
        } else {
            $('setDirInput').value = p;
            $('btnSaveDir').disabled = false;
        }
    }

    /** 「浏览…」入口：飞牛宿主直接调系统文件选择器（沙盒内后端列不出 fnOS 目录），
     *  选完自动向宿主申请存储授权；取消 / 失败 / 独立浏览器回落服务器目录弹窗。 */
    async function browseDirFlow(target) {
        dirTarget = target === 'archive' ? 'archive' : 'recording';
        var t = trimState.app;
        if (!t) { openDirModal(dirTarget); return; }
        // 宿主握手期间用户已等待：给出即时反馈再弹系统选择器
        toast('正在打开飞牛文件选择器…');
        var picked = await trimPickDir(dirTarget === 'archive' ? '选择快照归档目录' : '选择录像存储目录');
        if (!picked) { openDirModal(dirTarget); return; }
        var p = picked.replace(/\/+$/, '') || '/';
        var ok = await trimAuthorizePath(p); // v2.3.1 选完即申请授权
        try {
            var d = await api('api/settings/browse?path=' + encodeURIComponent(p));
            if (d && d.currentWritable === true) {
                applyPickedDir(p);
                toast('已选择目录（' + (ok ? '已自动授权' : '可写') + '，记得点保存）：' + p, 'ok');
                return;
            }
            toast('已尝试自动授权，该目录仍不可写：为你打开系统授权页后请重新选择', 'err');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('目录可写性校验失败：' + (e.message || ''), 'err');
        }
        if (t.openAppSetting) { try { t.openAppSetting(); } catch (e3) { /* 忽略 */ } }
        openDirModal(dirTarget);
    }

    /** 飞牛系统文件选择器：选完目录后申请授权、校验可写性并作为当前选择。 */
    async function sysPickDir() {
        var picked = await trimPickDir(dirTarget === 'archive' ? '选择快照归档目录' : '选择录像存储目录');
        if (!picked) return;
        var p = picked.replace(/\/+$/, '') || '/';
        await trimAuthorizePath(p); // v2.3.1 选完即申请授权
        try {
            var d = await api('api/settings/browse?path=' + encodeURIComponent(p));
            var writable = d.currentWritable === true;
            dirPicked = writable ? p : null;
            $('dirSelected').textContent = writable ? p : (p + '（已尝试授权，仍不可写，无法选择）');
            $('dirConfirm').disabled = !writable;
            if (!writable) toast('已尝试自动授权仍不可写：请到 fnOS 应用设置 → 存储管理手动授权', 'err');
        } catch (e) {
            $('dirSelected').textContent = p + '（无法校验可写性）';
            $('dirConfirm').disabled = true;
        }
    }

    function closeDirModal() {
        $('dirModal').hidden = true;
    }

    /* ---------- v2.3.1 内置存储权限面板（设置 → 存储与录像） ---------- */
    /** 检测当前录像目录对本应用的可写性，实时显示授权状态。 */
    async function refreshPermStatus() {
        var el = $('permStatusText');
        if (!el) return;
        var dir = ($('setCurDir').textContent || '').trim();
        if (!dir || dir === '—') {
            el.textContent = '存储权限：使用默认目录（应用私有空间，无需授权）';
            return;
        }
        try {
            var d = await api('api/settings/browse?path=' + encodeURIComponent(dir));
            if (d && d.currentWritable === true) {
                el.textContent = '存储权限：已授权 ✓（' + dir + '）';
                return;
            }
            // v2.3.1 热修：独立浏览器访问时面板不再隐藏，未授权补文字指引
            // （「打开系统授权页」按钮是 sdk-only，独立环境只能给出路径指引）
            el.textContent = '存储权限：未授权或不可写，录像写入会失败'
                + (document.body.classList.contains('sdk-host')
                    ? ''
                    : '——请在飞牛桌面上打开本应用重新选择一次目录（会自动申请授权），'
                      + '或到 应用中心 → 本应用 → 设置 → 存储管理 勾选该目录并重启应用');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            el.textContent = '存储权限：状态未知（' + (e.message || '检测失败') + '）';
        }
    }

    async function loadDir(path, create) {
        var box = $('dirList');
        box.innerHTML = '<div class="dir-empty">加载中…</div>';
        $('dirStatus').textContent = '';
        try {
            var q = path ? ('?path=' + encodeURIComponent(path)) : '';
            if (create) { q += (q ? '&' : '?') + 'create=1'; }
            dirData = await api('api/settings/browse' + q);
            renderDir();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); closeDirModal(); return; }
            box.innerHTML = '';
            $('dirStatus').textContent = '无法打开该目录：' + e.message;
        }
    }

    /** v1.2.22 手动路径前往：fnOS 授权层级列举不到时，直接按绝对路径定位 / 创建。 */
    function goManualDir() {
        var p = $('dirManualPath').value.trim();
        if (!p) { $('dirStatus').textContent = '请先输入绝对路径，如 /vol2/1000/cam'; return; }
        // v1.2.23：漏写前导 / 时自动补齐（后端会按进程工作目录拼接相对路径，必错）；
        // 去掉末尾多余斜杠。Windows 盘符形态不处理（fnOS 场景不存在）。
        if (p.charAt(0) !== '/' && !/^[A-Za-z]:[\\/]/.test(p)) { p = '/' + p; }
        p = p.replace(/\/+$/, '');
        if (p === '') { p = '/'; }
        $('dirManualPath').value = p;
        loadDir(p, $('dirManualCreate').checked);
    }

    function renderDir() {
        var d = dirData;
        $('dirCurrent').textContent = d.current || '此电脑 / 根目录';
        $('dirUp').disabled = !d.parent;
        renderCrumbs(d.current);
        $('dirRoots').innerHTML = (d.roots || []).map(function (r) {
            return '<button class="btn tiny dir-drive" type="button" data-path="' + esc(r.path) + '">' + esc(r.name) + '</button>';
        }).join('');
        var box = $('dirList');
        var html = '';
        // 允许直接选中“当前目录”本身：用户授权的外部卷目录（如 /vol2/1000/影视/cam）常直接作为
        // 录像根，可能为空；若只允许选子目录会陷入“空目录无可选”的死结，故显式提供“选择此目录”。
        if (d.current) {
            var cw = !!d.currentWritable;
            html += '<div class="dir-item' + (cw ? '' : ' ro') + '" data-path="' + esc(d.current) + '" data-writable="' + (cw ? '1' : '0') + '">' +
                FOLDER_SVG +
                '<span class="dir-name">✔ 选择此目录：' + esc(d.current) + '</span>' +
                '<span class="dir-flag">' + (cw ? '可写' : '不可写') + '</span>' +
                '</div>';
        }
        if (d.entries && d.entries.length) {
            html += d.entries.map(function (e) {
                var v = !!e.virtual;
                var cls = e.writable ? 'dir-item' : (v ? 'dir-item virtual' : 'dir-item ro');
                return '<div class="' + cls + '" data-path="' + esc(e.path) + '" data-writable="' + (e.writable ? '1' : '0') +
                    '" data-virtual="' + (v ? '1' : '0') + '">' +
                    FOLDER_SVG +
                    '<span class="dir-name">' + esc(e.name) + '</span>' +
                    '<span class="dir-flag">' + (e.writable ? '可写' : (v ? '进入' : '不可写')) + '</span>' +
                    '</div>';
            }).join('');
        }
        box.innerHTML = html || '<div class="dir-empty">（此目录下没有子目录）</div>';
        box.querySelectorAll('.dir-item').forEach(function (el) {
            var p = el.getAttribute('data-path');
            var writable = el.getAttribute('data-writable') === '1';
            var isVirtual = el.getAttribute('data-virtual') === '1';
            el.addEventListener('click', function () {
                // fnOS 授权路径的中间层级（/vol2/1000、影视…）只有穿越权限、不可写，
                // 单击直接进入下一级，不参与「选择」，避免误显示成不可选目录。
                if (isVirtual && !writable) { loadDir(p); return; }
                box.querySelectorAll('.dir-item').forEach(function (x) { x.classList.remove('sel'); });
                el.classList.add('sel');
                dirPicked = writable ? p : null;
                $('dirSelected').textContent = writable ? p : '该目录不可写，无法选择';
                $('dirConfirm').disabled = !writable;
            });
            el.addEventListener('dblclick', function () {
                loadDir(p); // 双击进入（不可写也可尝试进入查看）
            });
        });
        $('dirRoots').querySelectorAll('.dir-drive').forEach(function (b) {
            b.addEventListener('click', function () { loadDir(b.getAttribute('data-path')); });
        });
        if (d.unreadable) {
            $('dirStatus').textContent = '该目录无权限列出内容（fnOS 仅授权了最末级目录，上级目录不可列举）。'
                + '如需浏览更多目录，请在 fnOS「应用设置 → 存储管理」中把更上层的目录（如 /vol2/1000 或 /vol2/1000/影视）授权给本应用。';
        }
    }

    /** 把当前绝对路径渲染成可点击的面包屑（卷/1000/影视/cam…），点击任一段跳到该前缀路径。 */
    function renderCrumbs(path) {
        var el = $('dirCrumbs');
        if (!el) return;
        if (!path) { el.innerHTML = ''; return; }
        var parts = path.split('/').filter(function (x) { return x.length; });
        var acc = '';
        var segs = parts.map(function (p) {
            acc += '/' + p;
            return { name: p, path: acc };
        });
        el.innerHTML = segs.map(function (s, i) {
            var last = i === segs.length - 1;
            return '<span class="crumb' + (last ? ' cur' : '') + '"'
                + (last ? '' : ' data-path="' + esc(s.path) + '"') + '>' + esc(s.name) + '</span>';
        }).join('<span class="crumb-sep">/</span>');
        el.querySelectorAll('.crumb[data-path]').forEach(function (c) {
            c.addEventListener('click', function () { loadDir(c.getAttribute('data-path')); });
        });
    }

    function confirmDir() {
        if (!dirPicked) {
            toast('请选择一个可写目录', 'err');
            return;
        }
        if (dirTarget === 'archive') {
            $('setArchiveDirInput').value = dirPicked;
            $('btnSaveArchiveDir').disabled = false;
        } else {
            $('setDirInput').value = dirPicked;
            $('btnSaveDir').disabled = false;
        }
        closeDirModal();
    }

    /* ---------- v2.3.1 事件快照归档设置 ---------- */
    function renderSnapshotArchive(s) {
        var a = (s && s.snapshotArchive) || {};
        $('setSnapArchEnabled').checked = a.enabled === true;
        $('setArchiveDirInput').value = a.dir || '';
        $('btnSaveArchiveDir').disabled = true;
        $('setArchiveMsg').textContent = (a.enabled === true && a.dir)
            ? '归档中：' + a.dir : '未启用';
    }

    async function saveSnapshotArchive() {
        var dir = $('setArchiveDirInput').value.trim();
        var enabled = $('setSnapArchEnabled').checked;
        if (enabled && !dir) {
            toast('请先选择归档目录', 'err');
            return;
        }
        try {
            var s = await api('api/settings/snapshot-archive', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: enabled, dir: dir })
            });
            state.settings = s;
            renderSnapshotArchive(s);
            toast(enabled ? '快照归档已启用：' + dir : '快照归档已关闭', 'ok');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            $('setArchiveMsg').textContent = e.message || '保存失败';
            toast('保存失败：' + (e.message || '未知错误'), 'err');
        }
    }

    /** 保存前校验：目录能否创建 / 可写（后端 browse 端点返回 currentWritable）。 */
    async function verifyArchiveDir() {
        var dir = $('setArchiveDirInput').value.trim() ||
            ((state.settings && state.settings.snapshotArchive && state.settings.snapshotArchive.dir) || '');
        if (!dir) { toast('请先选择归档目录', 'err'); return; }
        try {
            var d = await api('api/settings/browse?path=' + encodeURIComponent(dir) + '&create=1');
            if (d && d.currentWritable) { toast('目录可写：' + dir, 'ok'); return; }
            toast('目录不可写（检查 fnOS 应用存储授权）：' + dir, 'err');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('校验失败：' + (e.message || dir), 'err');
        }
    }

    /* ---------- v2.3.1 Home Assistant（MQTT）设置 ---------- */
    function renderMqtt(s) {
        var m = (s && s.mqtt) || {};
        $('setMqttEnabled').checked = m.enabled === true;
        $('setMqttHost').value = m.host || '';
        $('setMqttPort').value = m.port || 1883;
        $('setMqttUser').value = m.username || '';
        $('setMqttPass').value = '';
        $('setMqttPass').placeholder = m.mqttPassSet ? '●●●●●●（已保存，留空保持不变）' : '可留空';
        $('setMqttTls').checked = m.tls === true;
        $('setMqttDiscoveryPrefix').value = m.discoveryPrefix || 'homeassistant';
        $('setMqttBaseTopic').value = m.baseTopic || 'ainvr';
        $('setMqttOffDelay').value = m.offDelaySec || 90;
        $('setMqttSnapInterval').value = m.snapshotIntervalSec != null ? m.snapshotIntervalSec : 30;
        var ha = m.ha || {};
        var st = $('setMqttStatus');
        if (ha.connected === true) {
            st.textContent = '已连接 broker：' + (ha.deviceCount || 0) + ' 台摄像头 × 5 实体已发布到 HA discovery，设备页面应已出现。';
        } else if (ha.lastError) {
            st.textContent = '未连接：' + ha.lastError + '（保存后会自动重试）';
        } else {
            st.textContent = '未启用或尚未连接。';
        }
    }

    function mqttFormBody(withPassword) {
        var snap = parseInt($('setMqttSnapInterval').value, 10);
        var body = {
            enabled: $('setMqttEnabled').checked,
            host: $('setMqttHost').value.trim(),
            port: parseInt($('setMqttPort').value, 10) || 1883,
            username: $('setMqttUser').value.trim(),
            tls: $('setMqttTls').checked,
            discoveryPrefix: $('setMqttDiscoveryPrefix').value.trim() || 'homeassistant',
            baseTopic: $('setMqttBaseTopic').value.trim() || 'ainvr',
            offDelaySec: parseInt($('setMqttOffDelay').value, 10) || 90,
            snapshotIntervalSec: isNaN(snap) ? null : snap
        };
        var pass = $('setMqttPass').value;
        if (withPassword || pass) { body.password = pass; }
        return body;
    }

    async function saveMqtt() {
        var body = mqttFormBody(true);
        if (body.enabled && !body.host) {
            toast('请填写 Broker 地址', 'err');
            return;
        }
        var btn = $('btnSaveMqtt');
        btn.disabled = true;
        $('setMqttMsg').textContent = '';
        try {
            var s = await api('api/settings/mqtt', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            state.settings = s;
            renderMqtt(s);
            toast(body.enabled ? 'MQTT 设置已保存，正在连接…' : 'MQTT 已关闭', 'ok');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            $('setMqttMsg').textContent = e.message || '保存失败';
            toast('保存失败：' + (e.message || '未知错误'), 'err');
        } finally {
            btn.disabled = false;
        }
    }

    async function testMqtt() {
        var btn = $('btnTestMqtt');
        btn.disabled = true;
        $('setMqttMsg').textContent = '正在测试连接…';
        try {
            var r = await api('api/settings/mqtt/test', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(mqttFormBody(false))
            });
            $('setMqttMsg').textContent = r && r.ok
                ? '✓ 连接成功（' + (r.latencyMs || 0) + 'ms），可以保存并连接。'
                : '✗ 连接失败：' + ((r && r.error) || '未知错误');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            $('setMqttMsg').textContent = '✗ 测试失败：' + (e.message || '未知错误');
        } finally {
            btn.disabled = false;
        }
    }

    /* ---------- HLS 预览 ---------- */
    // 冷启动时服务端首个 fMP4 分片尚未生成（需先连上摄像头并缓存一个 IDR）。原生 HLS 对
    // “无 ENDLIST 的直播播放列表”需要攒够约 3 个分片才可起播；分片不足就赋 src 会直接判
    // MEDIA_ERR_SRC_NOT_SUPPORTED(error4) 且不可恢复。这里串行轮询 m3u8（同时也在保活 HLS
    // 订阅），等列表里稳定出现 >=3 个 .m4s 分片后再赋 src 播放。
    function warmHls(u, cb) {
        var done = false, tries = 0;
        function finish() { if (!done) { done = true; cb(); } }
        function segCount(txt) { return (txt.match(/\.m4s/g) || []).length; }
        function once() {
            tries++;
            fetch(u, { cache: 'no-store' }).then(function (r) { return r.text(); }).then(function (txt) {
                // v2.3.1 第二十轮起播提速：原先等 3 个分片（每片约 3.2s ≈ 白等 10 秒）
                // 才赋 src；改为首个分片就绪即起播，后续分片边播边缓冲，
                // 起播时间 10s 级 → 3-4s 级。起播后短暂缓冲由看门狗与原生缓冲兜底。
                if (segCount(txt) >= 1 || tries >= 40) { finish(); }
                else { setTimeout(once, 300); }
            }).catch(function () { if (tries >= 40) { finish(); } else { setTimeout(once, 300); } });
        }
        once();
    }

    /* Chromium 内核（Edge/Chrome/内置浏览器）：其原生 MediaFoundation HLS 在长期运行遇停滞时
       会产生缓冲空洞且无法自愈，故优先 hls.js(MSE)；Safari 等保留原生优先。 */
    /* 显式探测 HEVC 解码能力。Windows 上的 Chrome/Edge 依赖系统「HEVC 视频扩展」+ 硬件解码，
       macOS Safari / Apple Silicon 上的 Chrome 通常原生支持。仅用于诊断文案，不改变播放路径。
       多个常见 profile 任一命中即视为支持，避免因 level 写法差异把能播的浏览器误判为不支持。 */
    function hevcDecodeSupported() {
        try {
            var codecs = ['hvc1.1.6.L93.B0', 'hev1.1.6.L93.B0', 'hvc1.1.6.L153.B0'];
            var probe = document.createElement('video');
            for (var i = 0; i < codecs.length; i++) {
                var mime = 'video/mp4; codecs="' + codecs[i] + '"';
                if (window.MediaSource && window.MediaSource.isTypeSupported && window.MediaSource.isTypeSupported(mime)) return true;
                if (probe.canPlayType(mime)) return true;
            }
            return false;
        } catch (e) { return false; }
    }

    /* 原生 HLS 能力（iOS Safari / 飞牛 App WKWebView）。
       实测：局域网内 iPhone 通过飞牛 App 打开本应用可以看到 H.265 画面，说明这类
       WebView 的「原生 HLS + HEVC」链路是通的；而 MP4 容器里 hvc1 的 canPlayType
       探测在部分 WKWebView 会返回空，不能拿它否定 HLS 中的 HEVC 解码能力。
       v1.2.4：这类设备一律先试 HLS，只有真实播放失败才降级，避免把流畅画面
       误降成 2 帧/秒、无声音的 JPEG 兼容帧。 */
    function nativeHlsSupported() {
        try {
            var probe = document.createElement('video');
            return !!probe.canPlayType('application/vnd.apple.mpegurl');
        } catch (e) { return false; }
    }

    /* ==================== v1.2.3 兼容画面模式 ====================
       摄像头输出为 fMP4 + H.265，PC Edge/Safari 与部分手机可解；Android WebView / 缺少
       HEVC 扩展的 Chromium 会直接报 codec 不兼容黑屏。这里改用服务端解帧后的
       JPEG 连续帧（/api/frame/{did}），只需 <img>，任何浏览器/WebView 都能出画面。
       代价：低帧率、无声音、服务端需要软解一路；故仅在「确定播不了」或「实际播放失败」
       时启用。v1.2.4 起前置探测只用于 Chromium+MSE 快速跳过必然黑屏的等待，
       原生 HLS 设备改为乐观试播 + 失败降级（首帧看门狗 / autoFallbackToCompat）。 */
    function previewWantsCompat() {
        var mode = $('previewMode') ? ($('previewMode').value || 'auto') : 'auto';
        if (mode === 'compat') { return true; }
        if (mode === 'hls') { return false; }
        if (state.forceCompat) { return true; }
        if (nativeHlsSupported()) { return false; }
        return !hevcDecodeSupported();
    }

    function stopCompatMode() {
        state.compatSeq++;
        if (state.compatTimer) { clearInterval(state.compatTimer); state.compatTimer = null; }
        var img = $('compatFrame');
        if (img) {
            img.onload = null; img.onerror = null;
            img.removeAttribute('src');
            img.hidden = true;
        }
        if ($('compatBadge')) { $('compatBadge').hidden = true; }
        var v = $('previewVideo');
        if (v) { v.hidden = false; }
    }

    function startCompatMode(did, quiet) {
        var img = $('compatFrame');
        if (!img) { return; }
        var seq = ++state.compatSeq;
        var n = 0;
        state.compatFails = 0;
        img.hidden = false;
        $('compatBadge').hidden = false;
        $('previewVideo').hidden = true;
        $('previewHint').hidden = true;
        $('previewLoading').hidden = true;
        img.onload = function () { state.compatFails = 0; };
        img.onerror = function () {
            state.compatFails++;
            if (state.compatFails === 4 && state.compatSeq === seq) {
                showPreviewHint('兼容画面取帧失败：请确认摄像头在线、米家账号已登录，稍后重试；'
                    + '也可把「画面」切回 HLS 高清，或在局域网用下方 RTSP 地址播放。');
            }
        };
        function tick() {
            if (state.compatSeq !== seq || $('previewModal').hidden || state.previewDid !== did) { return; }
            n++;
            // 查询串同时携带门禁令牌与递增序号：绕开移动 WebView 对同 URL 图片的强缓存
            var u = withToken('api/frame/' + encodeURIComponent(did))
                + '&_=' + Date.now() + '_' + n;
            img.src = u;
        }
        tick();
        state.compatTimer = setInterval(tick, 450);
        var badge = $('liveBadge');
        if (badge) { badge.querySelector('span').textContent = '兼容画面'; }
        // PTZ 临时跟手模式不覆盖"云台「上」已发出"的操作反馈
        if (!quiet) {
            setPtzStatus('兼容画面模式：以 JPEG 连续帧显示（约 2 帧/秒、无声音），不依赖浏览器 HEVC 解码；云台控制仍可用。');
        }
    }

    /** HLS 播放失败且处于「自动」时，强制切一次兼容画面；手动 HLS 模式只给出提示。 */
    function autoFallbackToCompat(msg) {
        var mode = $('previewMode') ? ($('previewMode').value || 'auto') : 'auto';
        if (mode === 'auto' && !state.forceCompat && state.previewDid) {
            state.forceCompat = true;
            state.recoveredOnce = false;
            toast('H.265 无法播放，已切换到兼容画面', 'ok');
            var c = findDevice(state.previewDid);
            if (c) { applyPreviewPlayback(state.previewDid, withToken(c.hlsUrl)); }
            return;
        }
        showPreviewHint(msg);
    }

    /* ==================== v1.2.8 PTZ 临时跟手画面 ====================
       HLS/fMP4 的 GOP≈3.2s，云台转动后要等下一个关键帧才更新；JPEG 兼容帧由
       LiveFrameService 常驻解码（≤4fps），没有 GOP 等待，方向键更跟手。
       操作时临时把 compatFrame 盖到 video 上，连续 4 秒无操作后自动切回 HLS。
       关键：不销毁 / 重建 HLS 会话，video 只是 hidden，切回无需重连、无黑屏。 */
    var PTZ_COMPAT_HOLD_MS = 4000;
    var COMPAT_BADGE_TEXT = '兼容画面 · 低帧率 / 无声音';

    function ptzCompatEligible() {
        var sel = $('previewMode');
        var mode = sel ? (sel.value || 'auto') : 'auto';
        var img = $('compatFrame');
        return mode === 'auto' && !state.forceCompat && !!state.previewDid
            && !$('previewModal').hidden && !!img && img.hidden;
    }

    function beginPtzCompat() {
        // 已因 HLS 失败永久降级：保持兼容画面，不要回切
        if (state.forceCompat) return;
        if (state.ptzCompatActive || !ptzCompatEligible()) {
            if (state.ptzCompatActive) schedulePtzCompatBack(); // 连续操作只做续期
            return;
        }
        state.ptzCompatActive = true;
        startCompatMode(state.previewDid, true);
        if ($('compatBadge')) $('compatBadge').textContent = '云台跟手 · 临时低延迟画面';
        updateLiveBadge();
        schedulePtzCompatBack();
    }

    function schedulePtzCompatBack() {
        if (state.ptzCompatTimer) clearTimeout(state.ptzCompatTimer);
        state.ptzCompatTimer = setTimeout(endPtzCompat, PTZ_COMPAT_HOLD_MS);
    }

    function endPtzCompat() {
        if (state.ptzCompatTimer) { clearTimeout(state.ptzCompatTimer); state.ptzCompatTimer = null; }
        if (!state.ptzCompatActive) return;
        state.ptzCompatActive = false;
        var mode = $('previewMode') ? ($('previewMode').value || 'auto') : 'auto';
        // 期间若发生自动降级、用户切了模式、或预览已关闭，播放器已被别处接管
        if (state.forceCompat || mode !== 'auto' || !state.previewDid || $('previewModal').hidden) return;
        stopCompatMode(); // 只停 JPEG 轮询并隐藏 img，HLS 实例与订阅保持不动
        if ($('compatBadge')) $('compatBadge').textContent = COMPAT_BADGE_TEXT;
        $('previewLoading').hidden = true;
        $('previewHint').hidden = true;
        var v = $('previewVideo');
        if (v) {
            v.hidden = false;
            try { v.play().catch(function () {}); } catch (e) {}
        }
        chaseLiveEdge(true); // 丢掉操作期间的滞后缓冲
        setTimeout(function () { if (!state.ptzCompatActive) chaseLiveEdge(true); }, 1200);
        setPtzStatus('已切回 HLS 高清画面', 'ok');
        updateLiveBadge();
    }

    function cancelPtzCompat() {
        if (state.ptzCompatTimer) { clearTimeout(state.ptzCompatTimer); state.ptzCompatTimer = null; }
        state.ptzCompatActive = false;
        if ($('compatBadge')) $('compatBadge').textContent = COMPAT_BADGE_TEXT;
    }

    /* ==================== v1.2.3 外网可播链接 ====================
       RTSP 是独立监听的 9554 TCP 端口，fnOS 网关只反代 HTTP；用公网域名访问时未做端口
       映射必然连不上。改为生成一张临时单画面共享看板，链接走当前域名 + /share/{token}，
       免登录、免端口映射，24 小时有效且可随时撤销。 */
    function absUrl(path) {
        try { return new URL(path, document.baseURI).href; } catch (e) { return path; }
    }
    function resetLiveShareUi() {
        state.liveShare = null;
        var box = $('previewShareUrl');
        box.textContent = '未生成 · 走当前域名，无需 9554 端口映射';
        box.className = 'share-url';
        box.title = '';
        $('btnCopyLiveLink').disabled = true;
        $('btnRevokeLiveLink').hidden = true;
    }
    function showLiveShare(data) {
        state.liveShare = data;
        var box = $('previewShareUrl');
        var url = absUrl(data.path);
        box.textContent = url;
        box.title = url;
        box.className = 'share-url has-link';
        $('btnCopyLiveLink').disabled = false;
        $('btnRevokeLiveLink').hidden = false;
    }
    async function loadLiveShareStatus(did) {
        try {
            var r = await api('api/live-share/' + encodeURIComponent(did));
            if (r && r.active && state.previewDid === did) { showLiveShare(r); }
        } catch (e) { /* 无历史链接时接口返回 active:false，静默即可 */ }
    }
    async function genLiveShare(copyAfter) {
        var did = state.previewDid;
        if (!did) { toast('请先打开实时预览', 'err'); return; }
        var btn = $('btnGenLiveLink');
        btn.disabled = true;
        try {
            var r = await api('api/live-share/' + encodeURIComponent(did), { method: 'POST' });
            if (state.previewDid !== did) { return; }
            showLiveShare(r);
            if (copyAfter) {
                copyText(absUrl(r.path));
                toast('外网可播链接已复制（24 小时有效）', 'ok');
            } else {
                toast('链接已生成，可复制后在外网打开', 'ok');
            }
        } catch (e) {
            toast('生成链接失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }
    function copyLiveShare() {
        if (state.liveShare) {
            copyText(absUrl(state.liveShare.path));
            toast('外网可播链接已复制', 'ok');
        } else {
            genLiveShare(true);
        }
    }
    async function revokeLiveShare() {
        if (!state.liveShare) { return; }
        var id = state.liveShare.id;
        try {
            await api('api/live-share/' + encodeURIComponent(id), { method: 'DELETE' });
            resetLiveShareUi();
            toast('链接已撤销，立即失效', 'ok');
        } catch (e) {
            toast('撤销失败：' + e.message, 'err');
        }
    }

    /* 预览 hls.js（MSE）挂载：低延迟调优 + 令牌注入 + 致命错误恢复，返回 hls 实例 */
    function mountPreviewMse(video, url) {
        // 除 m3u8 内 URI 携带令牌外，所有 XHR 再注入 Authorization 头（双保险）
        // v1.2.1 低延迟：同步点 1 个分片、落后 2 片即加速追帧、收紧前后向缓冲（真机分片≈3.2s）。
        // 不把缓冲压到低于 2 个分片，避免弱网下频繁起播失败；真正的追帧由 chaseLiveEdge 主动触发。
        var hlsOpts = {
            liveSyncDurationCount: 1,
            liveMaxLatencyDurationCount: 2,
            lowLatencyMode: true,
            maxLiveSyncPlaybackRate: 1.5,
            backBufferLength: 15,
            maxBufferLength: 8,
            maxMaxBufferLength: 20,
            startPosition: -1,
            enableWorker: true,
            // v2.3.1 第二十轮起播提速：manifest 解析期间即预取首分片，
            // 与解析完成后的首片加载并行，省一个串行往返
            startFragPrefetch: true
        };
        var ht = getToken();
        if (ht) {
            hlsOpts.xhrSetup = function (xhr) {
                try { xhr.setRequestHeader('Authorization', 'Bearer ' + ht); } catch (e) {}
            };
        }
        var hls = new Hls(hlsOpts);
        hls.loadSource(url);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, function () {
            video.play().catch(function () {});
        });
        hls.on(Hls.Events.ERROR, function (evt, data) {
            if (!data.fatal) return;
            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                if (!state.recoveredOnce) { state.recoveredOnce = true; hls.startLoad(); }
                else showPreviewHint('取流中断，已尝试重连失败。请关闭后重新预览，或用 RTSP 在 VLC 播放。');
            } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                var codecFail = data.details === Hls.ErrorDetails.MANIFEST_INCOMPATIBLE_CODECS_ERROR ||
                    data.details === Hls.ErrorDetails.BUFFER_ADD_CODEC_ERROR;
                if (codecFail) {
                    try { hls.destroy(); } catch (e) {}
                    autoFallbackToCompat('当前浏览器无法解码 H.265(HEVC)：Windows 上的 Chrome / Edge 需安装「HEVC 视频扩展」，局域网也可用下方 RTSP 地址在 VLC 播放。');
                } else if (!state.recoveredOnce) {
                    state.recoveredOnce = true;
                    try { hls.recoverMediaError(); } catch (e) {
                        showPreviewHint('视频解码失败，建议用 RTSP 在 VLC 播放。');
                    }
                } else {
                    showPreviewHint('视频解码失败，建议复制下方 RTSP 地址用 VLC 播放。');
                }
            } else {
                showPreviewHint('播放失败：' + (data.details || '未知错误') + '。可改用 RTSP 在 VLC 播放。');
            }
        });
        return hls;
    }

    /* 按浏览器能力选择路径并挂载：hls.js 可用即优先 MSE（v2.3.1 第二十二轮），
       原生降级；返回 hls 实例 / null（原生）/ undefined（不支持）。
       第二十二轮根修：去掉 isChromium 前置门槛。iPhone（iOS 17.1+）的 hls.js 走
       ManagedMediaSource，此前被该门槛排除在 MSE 之外只能用原生 HLS——而原生 HLS
       会被 WKWebView 系统播放器接管（全屏劫持 + 退出后暂停黑屏，真机实测）。
       MSE 路径完全是应用内 <video> 播放，从根上消灭系统全屏；旧 iOS 无 MSE 时
       Hls.isSupported() 自然为 false，仍走原生 HLS 兜底。 */
    function mountPreviewPlayer(video, url) {
        if (window.Hls && Hls.isSupported()) {
            state.hls = mountPreviewMse(video, url);
            return state.hls;
        }
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            // Safari / Edge 原生 HLS：先预热等首个 fMP4 分片生成，再赋 src 播放
            warmHls(url, function () {
                if (!$('previewModal').hidden) {
                    video.src = url;
                    video.play().catch(function () {});
                }
            });
            return null;
        }
        if (window.Hls && Hls.isSupported()) {
            state.hls = mountPreviewMse(video, url);
            return state.hls;
        }
        return undefined;
    }

    /* 预览看门狗回调：销毁旧会话并清理 video，再按当前能力重新挂载，随后重建看门狗 */
    function remountPreview(video, url) {
        stopPreviewWatchdog();
        if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }
        video.pause();
        video.removeAttribute('src');
        video.load();
        mountPreviewPlayer(video, url);
        startPreviewWatchdog(video, url);
    }

    function startPreviewWatchdog(video, url) {
        stopPreviewWatchdog();
        // getLatency/seekLive 闭包引用 state.hls，remount 后 state.hls 更新为新实例，回调始终读到当前会话
        state.previewWd = Watchdog.start(video, function () { remountPreview(video, url); }, {
            // v1.2.1：原先 latencyLimitSec=15 才追帧，云台操作后要等十几秒才看到转动。
            // 收紧到 6s（约 2 个分片）并加快检测，落后即主动追到直播边缘。
            checkMs: 1000,
            latencyLimitSec: 6,
            seekConfirmMs: 3000,
            getLatency: function () {
                // 原生 HLS 没有 hls.js 的 latency 语义，不参与墙钟延迟判定，避免误触发重挂载
                return state.hls && isFinite(state.hls.latency) ? state.hls.latency : null;
            },
            seekLive: function () {
                chaseLiveEdge(true);
            }
        });
        video.getWatchdogState = state.previewWd.getState; // 可观测性：便于 CDP 验收与运维排查
    }

    function stopPreviewWatchdog() {
        if (state.previewWd) { state.previewWd.stop(); state.previewWd = null; }
    }

    function openPreview(did) {
        var c = findDevice(did);
        if (!c) { toast('设备信息缺失', 'err'); return; }
        cancelPtzCompat(); // 换设备 / 重开预览时清掉上一次的临时跟手状态
        state.previewDid = did;
        state.forceCompat = false;
        state.recoveredOnce = false;
        $('previewTitle').textContent = '实时预览 · ' + c.name;
        $('previewRtsp').textContent = c.rtspUrl;
        $('previewModal').hidden = false;
        $('previewHint').hidden = true;
        $('previewLoading').hidden = false;
        loadPreviewControls(did);
        var video = $('previewVideo');
        video.hidden = false;
        video.style.visibility = 'visible';
        resetLiveShareUi();
        loadLiveShareStatus(did);
        applyPreviewPlayback(did, withToken(c.hlsUrl));
        startLiveBadge();
        enterOverlayHistory('preview');
    }

    /**
     * 按当前「画面」选择重建播放：兼容画面（JPEG 帧）或 HLS（含诊断与看门狗）。
     * 切换模式、自动降级时都走这里，保证两种通道不会同时占用播放器。
     */
    function applyPreviewPlayback(did, url) {
        stopPreviewWatchdog();
        stopCompatMode();
        resetIosFsGuard(); // v2.3.1 第二十二轮：新会话重新给予 iOS 内联恢复机会
        if (state.previewWatch) { clearTimeout(state.previewWatch); state.previewWatch = null; }
        if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }
        var video = $('previewVideo');
        try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}
        video.hidden = false;
        $('previewHint').hidden = true;
        $('previewLoading').hidden = false;

        if (previewWantsCompat()) {
            startCompatMode(did);
            return;
        }

        var clearWatch = function () {
            if (state.previewWatch) { clearTimeout(state.previewWatch); state.previewWatch = null; }
        };
        var diagnosed = false;

        /* 首帧失败时先分辨「服务端没取到流」与「浏览器解不了 HEVC」，不再一律归因为解码问题。 */
        function diagnoseFirstFrame(reason) {
            if (diagnosed) return;
            diagnosed = true;
            fetch(url, { cache: 'no-store' }).then(function (r) {
                var lines = ['预览' + reason + '（readyState=' + video.readyState +
                    '，videoWidth=' + (video.videoWidth || 0) + '）。'];
                if (!r.ok) {
                    lines.push('播放列表返回 HTTP ' + r.status + '：取流或首片尚未就绪。请确认摄像头在线、稍后重试。');
                } else if (!hevcDecodeSupported()) {
                    lines.push('播放列表正常，但本机缺少 H.265(HEVC) 解码支持；可把顶栏「画面」切到「兼容画面」。');
                } else {
                    lines.push('播放列表正常且浏览器声明支持 HEVC，仍无画面：可能是码流参数集或分片异常，请刷新重试。');
                }
                lines.push('局域网也可用下方 RTSP 地址在 VLC 播放。');
                showPreviewHint(lines.join('\n'));
            }).catch(function () {
                showPreviewHint('预览' + reason + '，且播放列表请求失败（网络或登录态失效）。请刷新页面重新登录。');
            });
        }

        // 看门狗：预热攒片 + 首片加载后，26s 内仍未解码出画面则分类诊断或自动降级
        clearWatch();
        state.previewWatch = setTimeout(function () {
            state.previewWatch = null;
            if ($('previewModal').hidden) return;
            if (!video.videoWidth || video.readyState < 2) {
                if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }
                // v1.2.3：自动模式下首帧迟迟不出，也降级到兼容画面（只降一次，避免来回抖动）
                if (($('previewMode').value || 'auto') === 'auto' && !state.forceCompat) {
                    state.forceCompat = true;
                    toast('HLS 迟迟未出画面，已切换到兼容画面', 'ok');
                    applyPreviewPlayback(did, url);
                    return;
                }
                diagnoseFirstFrame('超时未出画面');
            }
        }, 26000);
        video.addEventListener('loadeddata', clearWatch, { once: true });
        video.addEventListener('playing', clearWatch, { once: true });
        video.addEventListener('error', function () {
            if (!$('previewModal').hidden && !video.videoWidth) diagnoseFirstFrame('播放出错');
        }, { once: true });

        // 按浏览器能力选择路径：Chromium 优先 hls.js(MSE)，原生降级；不支持则自动切兼容画面
        var mountResult = mountPreviewPlayer(video, url);
        if (mountResult === undefined) {
            autoFallbackToCompat('当前浏览器不支持 HLS 播放，请把顶栏「画面」切到「兼容画面」；局域网也可用下方 RTSP 地址在 VLC 播放。');
        } else {
            // 长期运行看门狗：停滞/错误自动重新挂载（带退避），与首帧看门狗相互独立
            startPreviewWatchdog(video, url);
        }
    }

    function showPreviewHint(text) {
        var h = $('previewHint');
        h.hidden = false;
        h.style.whiteSpace = 'pre-line';
        h.textContent = text;
        $('previewLoading').hidden = true;
    }
    /**
     * v1.2.22 退出 iOS / Android WebView 的原生视频全屏层。
     * 返回 true 表示该 video 正处于原生全屏（调用方需延迟销毁流，
     * 否则 iOS 在全屏展示中销毁 video 会残留一个「僵尸全屏播放页」，
     * 用户表现为：关掉预览后按返回，屏幕上又出现一个全屏播放器，再点一次叉才回到列表）。
     */
    function exitNativeFullscreen(video) {
        if (!video) return false;
        try {
            if (document.fullscreenElement === video || document.webkitFullscreenElement === video) {
                var f = document.exitFullscreen || document.webkitExitFullscreen;
                if (f) { f.call(document); return true; }
            }
        } catch (e) { /* 继续尝试 webkit 私有接口 */ }
        try {
            if (video.webkitDisplayingFullscreen) { video.webkitExitFullscreen(); return true; }
        } catch (e) { /* 忽略 */ }
        return false;
    }
    /** 统一的视频流销毁：暂停 + 移除 src + reset。 */
    function teardownVideoEl(video) {
        if (!video) return;
        try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}
    }
    function stopPreviewOnly() {
        cancelPtzCompat();
        state.previewDid = null;
        state.forceCompat = false;
        stopCompatMode();
        resetLiveShareUi();
        state.ptzSeq++;            // 作废在途的云台应答轮询
        $('previewModal').hidden = true;
        var video = $('previewVideo');
        if (state.previewWatch) { clearTimeout(state.previewWatch); state.previewWatch = null; }
        stopPreviewWatchdog();
        stopLiveBadge();
        if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }
        // v1.2.22：先退出原生全屏再销毁；刚退出全屏时延迟销毁，等 iOS 收回全屏层
        if (exitNativeFullscreen(video)) {
            setTimeout(function () { teardownVideoEl(video); }, 400);
        } else {
            teardownVideoEl(video);
        }
        $('previewHint').hidden = true;
        $('previewLoading').hidden = true;
    }
    function closePreview() {
        // 双击 / 触摸+click 重复触发守卫：避免二次 leaveOverlayHistory 退掉首页历史条目
        if ($('previewModal').hidden && !overlayHistoryDepth) return;
        exitPreviewFullscreen(true);
        talkStop(true);
        stopPreviewOnly();
        leaveOverlayHistory();
        loadDevices(false);
    }

    /* ==================== v2.3.1 全屏悬浮控制 ====================
       全屏时云台方向键 + 喊话按钮悬浮在画面上，无需在全屏/半屏反复横跳。
       三条路径：标准元素全屏（Android/桌面）→ fullscreenchange 同步浮层；
       iOS 等不支持元素全屏 → 应用内伪全屏（video-wrap fixed inset 0）；
       视频原生全屏键已用 CSS 隐藏（Chromium/Safari），统一走应用全屏。 */
    function isFsActive() {
        return !!(document.fullscreenElement || document.webkitFullscreenElement)
            || $('videoWrap').classList.contains('fs-fake');
    }

    function updateFsOverlay() {
        $('fsOverlay').hidden = !isFsActive();
        $('btnFullscreen').textContent = isFsActive() ? '⛶ 退出全屏' : '⛶ 全屏';
        // v2.3.1 第二十一轮热修：伪全屏（fs-fake）也必须能退出——此前只在
        // document.fullscreenElement 存在时显示退出按钮，iOS 上系统播放器被
        // 拦截后落入伪全屏，按钮隐藏、videoWrap 盖住顶栏，用户被卡死在全屏里
        $('btnFsExit').hidden = !isFsActive();
    }

    /* v2.3.1 第二十一轮热修：原生视频全屏拦截改为「退回内联」而非「转伪全屏」。
       第二十轮实测（iPhone 15 Pro / 飞牛 App）：点预览即落全屏且无法退出——根因是
       自动转伪全屏后 btnFsExit 被隐藏、videoWrap(fixed) 盖住预览顶栏，无任何出口。
       现在策略：系统/元素级全屏一律退回内联播放，伪全屏只由用户点「⛶ 全屏」显式进入。 */
    function guardNativeVideoFs() {
        var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
        if (fsEl && fsEl.id === 'previewVideo') {
            var exit = document.exitFullscreen || document.webkitExitFullscreen;
            try { if (exit) { var fp = exit.call(document); if (fp && fp.catch) { fp.catch(function () { /* 忽略 */ }); } } } catch (e) { /* 忽略 */ }
            return;
        }
        updateFsOverlay();
    }

    /* v2.3.1 第二十二轮：iOS 原生全屏兜底状态 + 客户端诊断上报。
       resume/gaveUp 语义见下方 webkitbeginfullscreen 绑定处；每次重建预览播放时重置。 */
    var iosFsGuard = { resume: 0, gaveUp: false };

    function resetIosFsGuard() {
        iosFsGuard.resume = 0;
        iosFsGuard.gaveUp = false;
    }

    /** 轻量客户端事件上报：落服务端应用日志（[client-diag] 前缀），失败静默。
        noRelock：诊断上报失败不应把用户踢回锁屏页。 */
    function reportClientDiag(event, detail) {
        api('api/diagnostics/client-log', {
            method: 'POST',
            noRelock: true,
            body: JSON.stringify({ event: event, detail: detail || '' })
        }).catch(function () { /* 忽略 */ });
    }

    function exitPreviewFullscreen(silent) {
        if ($('videoWrap').classList.contains('fs-fake')) {
            $('videoWrap').classList.remove('fs-fake');
        }
        var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
        if (fsEl) {
            var exit = document.exitFullscreen || document.webkitExitFullscreen;
            try { if (exit) { var fp = exit.call(document); if (fp && fp.catch) { fp.catch(function () { /* 忽略 */ }); } } } catch (e) { /* 忽略 */ }
        }
        if (!silent) { updateFsOverlay(); }
    }

    function togglePreviewFullscreen() {
        if (isFsActive()) {
            exitPreviewFullscreen(false);
            return;
        }
        var wrap = $('videoWrap');
        var req = wrap.requestFullscreen || wrap.webkitRequestFullscreen;
        if (req) {
            try {
                var p = req.call(wrap);
                if (p && p.catch) {
                    p.catch(function () {
                        wrap.classList.add('fs-fake');
                        updateFsOverlay();
                    });
                }
            } catch (e) {
                wrap.classList.add('fs-fake');
            }
        } else {
            // iOS Safari 等不支持元素全屏：应用内伪全屏（浮层仍在 DOM 内可见）
            wrap.classList.add('fs-fake');
        }
        updateFsOverlay();
    }

    /* ==================== v2.3.1 对讲喊话 ====================
       浏览器麦克风 → 8kHz 重采样 → G.711 A-law（或 hlc6 线性 PCM）编码 →
       WebSocket 二进制上行 → 服务端 MISS CS2 通道 3 → 摄像头扬声器。
       复用当前取流会话（0x106/0x108），不新增摄像头连接。 */
    var talk = { ws: null, ctx: null, stream: null, node: null, src: null, gain: null,
        active: false, did: null, linearPcm: false, opus: false, enc: null, opusFrame: null, opusUsed: 0, opusTs: 0,
        buf: null,
        pool: null, queue: null, pacer: null, dc: 0, acc: null, accPos: 0 };

    function talkTip(text, kind) {
        var el = $('talkTip');
        if (el) { el.textContent = text; el.className = 'ptz-tip' + (kind ? ' ' + kind : ''); }
    }

    function talkUrl(did) {
        // WebSocket 无法走相对路径：以当前页面路径（兼容 fnOS 网关 /app/{appname}/）拼绝对地址
        var base = location.pathname.replace(/[^/]*$/, '');
        var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
        var u = proto + location.host + base + 'ws/talk/' + encodeURIComponent(did);
        var t = getToken();
        return t ? u + '?__ainvr_t=' + encodeURIComponent(t) : u;
    }

    /* 标准 G.711 线性 → A-law（lalmax-nvr xiaomiTalkAudio.ts 同款算法）。
       v2.3.1 第十八轮根修：旧版在 13bit 域对负数做「-pcm-8 + 段内移位」，
       回环测试（编码→标准解码）显示负数全域严重失真——小幅负样本 -41 解码成
       -248（误差 207），-31800 处误差达 568，负半周解码值跳变 → 摄像头喇叭
       「有杂音但能听出语音轮廓」的正半周正常/负半周乱码特征。新版在 16bit
       域编码，负数 -pcm-1，全域回环误差 ≤8（小幅区）/512（满幅，A-law 固有）。 */
    function linearToAlaw(sample) {
        var pcm = Math.trunc(Math.max(-32768, Math.min(32767, sample)));
        var mask = pcm >= 0 ? 0xD5 : 0x55;
        if (pcm < 0) { pcm = -pcm - 1; }
        var compressed;
        if (pcm < 256) {
            compressed = pcm >> 4;
        } else {
            var exponent = 7;
            for (var bit = 0x4000; (pcm & bit) === 0 && exponent > 0; bit >>= 1) { exponent--; }
            compressed = (exponent << 4) | ((pcm >> (exponent + 3)) & 0x0F);
        }
        return (compressed ^ mask) & 0xFF;
    }

    async function talkStart(did) {
        if (talk.active) { talkStop(true); return; }
        talk.did = did;
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.WebSocket) {
            setPtzStatus('当前浏览器不支持麦克风访问（需 HTTPS 或 localhost 环境）', 'err');
            return;
        }
        try {
            talk.stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
        } catch (e) {
            setPtzStatus('无法访问麦克风：' + e.message + '（浏览器需 HTTPS/localhost 才允许麦克风）', 'err');
            talkCleanup();
            return;
        }
        talk.active = true;
        $('btnTalk').classList.add('talking');
        $('btnTalk').textContent = '🔇 结束喊话';
        if ($('btnTalkFs')) { $('btnTalkFs').classList.add('talking'); $('btnTalkFs').textContent = '🔇 结束喊话'; }
        talkTip('对讲已开启：请对着麦克风说话，再次点击结束', 'ok');
        setPtzStatus('对讲通道建立中…', 'warn');

        var ws = new WebSocket(talkUrl(did));
        ws.binaryType = 'arraybuffer';
        talk.ws = ws;
        ws.onmessage = function (ev) {
            if (typeof ev.data !== 'string') { return; }
            var msg;
            try { msg = JSON.parse(ev.data); } catch (e2) { return; }
            if (!msg.ok) {
                setPtzStatus('喊话失败：' + (msg.error || '未知原因'), 'err');
                talkStop(true);
                return;
            }
            talk.linearPcm = !!msg.linearPcm;
            talk.opus = msg.codec === 1032;
            if (talk.opus && (!window.AudioEncoder || !window.AudioData)) {
                setPtzStatus('该机型对讲需要浏览器支持 WebCodecs（较新的 Chrome/Edge/Safari）；当前浏览器不支持', 'err');
                talkStop(true);
                return;
            }
            talk.buf = null;
            setPtzStatus('对讲已接通' + (msg.deviceReplied ? '（设备已确认扬声器）' : '（设备未应答，声音可能仍可送达）'), 'ok');
            talkTip('对讲进行中：说话即可，再次点击「结束喊话」停止', 'ok');
            talkStartMic();
        };
        ws.onclose = function () {
            if (talk.active) { talkStop(true); }
        };
        ws.onerror = function () { /* onclose 会跟进 */ };
    }

    function talkStartMic() {
        try {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            var ctx = null, rate = 0;
            if (talk.opus) {
                // 72ac1 等 OPUS 机型：48kHz 上下文 + WebCodecs AudioEncoder（lalmax 同款）
                try { ctx = new Ctx({ sampleRate: 48000 }); rate = ctx.sampleRate; } catch (eOp) { ctx = null; }
                if (!ctx || rate !== 48000) {
                    setPtzStatus('该机型对讲需要浏览器支持 48kHz 音频上下文（WebCodecs）', 'err');
                    talkStop(true);
                    return;
                }
            } else {
                // v2.3.1 第十六轮（对照 go2rtc / lalmax-nvr 参考实现重写）：
                // 首选 8000Hz 采集上下文——设备扬声器即 8kHz，浏览器原生重采样自带
                // 高质量低通，样本零抽取直发（lalmax 同款做法）。此前 16kHz 上下文
                // + 成对平均在部分内核不生效时静默掉进 48k 线性插值兜底，无低通
                // 抗混叠 → 高频折回 → 摄像头喇叭满幅杂音。
                try { ctx = new Ctx({ sampleRate: 8000 }); rate = ctx.sampleRate; } catch (e0) { ctx = null; }
                if (!ctx || rate !== 8000) {
                    if (ctx) { try { ctx.close(); } catch (e0c) { /* 忽略 */ } ctx = null; }
                    try { ctx = new Ctx({ sampleRate: 16000 }); rate = ctx.sampleRate; } catch (e1) { ctx = null; }
                    if (ctx && rate !== 16000 && rate !== 8000) {
                        // 内核不支持自定义采样率：用默认上下文 + 兜底重采样
                        try { ctx.close(); } catch (e1c) { /* 忽略 */ }
                        ctx = new Ctx();
                        rate = ctx.sampleRate || 16000;
                    }
                }
            }
            talk.ctx = ctx;
            talk.acc = [];
            talk.accPos = 0;
            talk.pool = [];   // 非 8kHz 上下文时的原始样本池
            talk.queue = [];  // 40ms 整块队列（匀速发送）
            talk.src = ctx.createMediaStreamSource(talk.stream);
            talk.node = ctx.createScriptProcessor(1024, 1, 1);
            if (talk.opus) {
                setupOpusEncoder();
            }
            talk.node.onaudioprocess = function (e) {
                if (!talk.active || !talk.ws || talk.ws.readyState !== 1) { return; }
                var input = e.inputBuffer.getChannelData(0);
                if (talk.opus) {
                    feedOpusSamples(input);
                    return;
                }
                var out = [];
                if (rate === 8000) {
                    // 主路径：上下文即 8kHz，样本直接可用，只做 DC 高通去底噪
                    for (var i = 0; i < input.length; i++) {
                        talk.dc += (input[i] - talk.dc) * 0.01;
                        out.push(input[i] - talk.dc);
                    }
                } else {
                    var pool = talk.pool;
                    for (var p = 0; p < input.length; p++) { pool.push(input[p]); }
                    if (rate === 16000) {
                        // 2:1 成对平均（[0.5,0.5] FIR，8k Nyquist 处有零点，抗混叠）
                        while (pool.length >= 2) {
                            var s16 = (pool.shift() + pool[0]) * 0.5;
                            talk.dc += (s16 - talk.dc) * 0.01;
                            out.push(s16 - talk.dc);
                        }
                    } else {
                        // 兜底：上下文不支持 8k/16k（旧 iOS 等），整数倍滑动平均低通
                        // + 带窗线性插值抽取到 8kHz（先滤后抽，抑制混叠）
                        var step = rate / 8000;
                        var acc = pool, pos = talk.accPos;
                        var i0, f;
                        while (pos + 1 < acc.length) {
                            i0 = Math.floor(pos); f = pos - i0;
                            var lo = Math.max(0, i0 - 3), hi = Math.min(acc.length - 1, i0 + 4);
                            var s = 0, n = 0;
                            for (var w = lo; w <= hi; w++) { s += acc[w]; n++; }
                            out.push((s / n) * (1 - f) + acc[i0 + 1] * f);
                            pos += step;
                        }
                        talk.pool = acc.slice(Math.floor(pos));
                        talk.accPos = pos - Math.floor(pos);
                    }
                }
                if (!out.length) { return; }
                if (talk.linearPcm) {
                    // hlc6：16bit 小端线性 PCM，每 40ms 640 字节
                    var pcm = new Int16Array(out.length);
                    for (var m = 0; m < out.length; m++) {
                        pcm[m] = Math.max(-32768, Math.min(32767, Math.round(out[m] * 32767)));
                    }
                    pushTalkChunk(new Uint8Array(pcm.buffer), 640);
                } else {
                    // 绝大多数机型：G.711 A-law 8kHz，每 40ms 320 字节
                    var bytes = new Uint8Array(out.length);
                    for (var k = 0; k < out.length; k++) {
                        var sv = Math.max(-32768, Math.min(32767, Math.round(out[k] * 32767)));
                        bytes[k] = linearToAlaw(sv);
                    }
                    pushTalkChunk(bytes, 320);
                }
            };
            talk.src.connect(talk.node);
            // 经零增益节点接到 destination：保证 ScriptProcessor 持续回调，又不把麦克风
            // 声音外放（手机扬声器外放会被麦克风再次拾音造成回声啸叫）
            talk.gain = ctx.createGain();
            talk.gain.gain.value = 0;
            talk.node.connect(talk.gain);
            talk.gain.connect(ctx.destination);
            if (!talk.opus) {
                // 40ms 匀速发送——ScriptProcessor 回调天然抖动，直发会让设备侧播放
                // 忽快忽慢（机械感杂音）；队列积压 ≥3 块时双发追赶。
                // OPUS 路径按 40ms 帧在编码器输出侧即时发送，无需节奏器。
                talk.pacer = setInterval(function () {
                    if (!talk.active || !talk.ws || talk.ws.readyState !== 1) { return; }
                    var q = talk.queue;
                    if (!q.length) { return; }
                    var n = q.length >= 3 ? 2 : 1;
                    while (n-- > 0 && q.length) {
                        talk.ws.send(q.shift().buffer);
                    }
                }, 40);
            }
        } catch (e) {
            setPtzStatus('麦克风处理初始化失败：' + e.message, 'err');
            talkStop(true);
        }
    }

    /* OPUS 机型（72ac1 等）：WebCodecs AudioEncoder 40ms 帧，输出即时发送。
       对照 lalmax-nvr web/src/lib/xiaomiTalkAudio.ts：bitrate 32k、frameDuration 40ms、
       原始 opus 帧（非 WebM/Ogg 容器）。 */
    function setupOpusEncoder() {
        var OPUS_FRAME = 1920; // 48000 × 40ms
        talk.opusFrame = new Float32Array(OPUS_FRAME);
        talk.opusUsed = 0;
        talk.opusTs = 0;
        talk.enc = new window.AudioEncoder({
            output: function (chunk) {
                if (!talk.active || !talk.ws || talk.ws.readyState !== 1) { return; }
                if (talk.ws.bufferedAmount > 64000) {
                    setPtzStatus('对讲发送拥塞，已停止', 'err');
                    talkStop(true);
                    return;
                }
                var outBuf = new ArrayBuffer(chunk.byteLength);
                chunk.copyTo(outBuf);
                talk.ws.send(outBuf);
            },
            error: function (err) {
                setPtzStatus('对讲编码失败：' + (err && err.message), 'err');
                talkStop(true);
            }
        });
        talk.enc.configure({
            codec: 'opus', sampleRate: 48000, numberOfChannels: 1, bitrate: 32000,
            opus: { format: 'opus', frameDuration: 40000 }
        });
        talk.feedOpus = function (samples) {
            var data = new window.AudioData({
                format: 'f32', sampleRate: 48000, numberOfFrames: samples.length,
                numberOfChannels: 1, timestamp: talk.opusTs, data: samples
            });
            talk.opusTs += 40000;
            try { talk.enc.encode(data); } finally { data.close(); }
        };
    }

    /** 麦克风样本 → 1920 样本（40ms@48k）帧 → OPUS 编码。 */
    function feedOpusSamples(input) {
        var OPUS_FRAME = 1920;
        var frame = talk.opusFrame, used = talk.opusUsed;
        for (var i = 0; i < input.length; i++) {
            if (used === OPUS_FRAME) {
                talk.feedOpus(frame);
                frame = talk.opusFrame = new Float32Array(OPUS_FRAME);
                used = 0;
            }
            frame[used++] = input[i];
        }
        talk.opusUsed = used;
    }

    /* 整块（320B A-law / 640B PCM）入匀速队列，保证服务端 MISS 包大小正确 */
    function pushTalkChunk(bytes, chunkSize) {
        var q = talk.queue || (talk.queue = []);
        var buf = talk.buf || (talk.buf = []);
        for (var i = 0; i < bytes.length; i++) {
            buf.push(bytes[i]);
        }
        while (buf.length >= chunkSize) {
            q.push(new Uint8Array(buf.splice(0, chunkSize)));
        }
        // 队列深度保护：落后太多（>15 块 = 600ms）时丢最老的，避免持续累积延迟
        while (q.length > 15) { q.shift(); }
    }

    function talkCleanup() {
        if (talk.node) { try { talk.node.disconnect(); } catch (e) { /* 忽略 */ } talk.node = null; }
        if (talk.gain) { try { talk.gain.disconnect(); } catch (e) { /* 忽略 */ } talk.gain = null; }
        if (talk.src) { try { talk.src.disconnect(); } catch (e) { /* 忽略 */ } talk.src = null; }
        if (talk.enc) { try { if (talk.enc.state !== 'closed') { talk.enc.close(); } } catch (e) { /* 忽略 */ } talk.enc = null; }
        talk.opusFrame = null;
        talk.opusUsed = 0;
        talk.opusTs = 0;
        talk.feedOpus = null;
        if (talk.ctx) { try { talk.ctx.close(); } catch (e) { /* 忽略 */ } talk.ctx = null; }
        if (talk.stream) { talk.stream.getTracks().forEach(function (t) { t.stop(); }); talk.stream = null; }
        if (talk.pacer) { clearInterval(talk.pacer); talk.pacer = null; }
        talk.buf = null;
        talk.pool = null;
        talk.queue = null;
        talk.dc = 0;
        talk.acc = null;
        talk.accPos = 0;
    }

    function talkStop(silent) {
        var wasActive = talk.active;
        talk.active = false;
        talkCleanup();
        if (talk.ws) {
            try { talk.ws.close(); } catch (e) { /* 忽略 */ }
            talk.ws = null;
        }
        $('btnTalk').classList.remove('talking');
        $('btnTalk').textContent = '🎤 喊话';
        if ($('btnTalkFs')) { $('btnTalkFs').classList.remove('talking'); $('btnTalkFs').textContent = '🎤 喊话'; }
        if (wasActive && !silent) { toast('对讲已结束', 'ok'); }
        if (wasActive) { talkTip('喊话复用当前连接，点击开始/再次点击结束', ''); }
    }

    /* ==================== v1.2.8 事件告警页 ====================
       数据来自 P0 的 /api/events*：JSONL 事件库 + JPEG 快照。列表按时间倒序，
       勾选后走批量接口（单条操作也复用同一套渲染），避免一次请求写爆日志。 */
    var EV_PAGE = 20;

    function evQuery(offset) {
        var p = ['limit=' + EV_PAGE, 'offset=' + offset];
        var did = $('evFilterDid').value;
        var type = $('evFilterType').value;
        var handled = $('evFilterHandled').value;
        var date = $('evFilterDate').value;
        if (did) p.push('did=' + encodeURIComponent(did));
        if (type) p.push('type=' + encodeURIComponent(type));
        if (handled !== '') p.push('handled=' + handled);
        if (date) p.push('date=' + encodeURIComponent(date));
        return p.join('&');
    }

    function loadEvFilters() {
        var sel = $('evFilterDid');
        var cur = sel.value;
        var devs = state.devices || [];
        var html = '<option value="">全部设备</option>' + devs.map(function (d) {
            return '<option value="' + esc(d.did) + '">' + esc(d.name || d.did) + '</option>';
        }).join('');
        // v1.2.13：历史事件可能属于已移除设备，补进下拉以便按摄像头查看
        var known = devs.map(function (d) { return String(d.did); });
        Object.keys(state.evCounts || {}).forEach(function (did) {
            if (known.indexOf(did) < 0) {
                html += '<option value="' + esc(did) + '">' + esc(cameraName(did)) + '</option>';
            }
        });
        sel.innerHTML = html;
        sel.value = cur;
    }

    async function loadEvents(reset) {
        if (state.evBusy) return;
        if (reset) {
            state.evOffset = 0;
            state.evRows = [];
            state.evSelected.clear();
            // v2.3.1 全部设备视图：重置后默认折叠各摄像头分组（仅第一组展开），先看概览再按需展开
            state.evCollapsePending = !$('evFilterDid').value;
        }
        state.evBusy = true;
        try {
            // 分组标题需要设备名，先确保设备列表已加载（失败则回落到 DID）
            if (!state.devices.length) { try { await loadDevices(false); } catch (e2) { /* 忽略 */ } }
            var r = await api('api/events?' + evQuery(state.evOffset));
            var list = r.list || [];
            state.evRows = reset ? list : state.evRows.concat(list);
            state.evTotal = r.total || 0;
            state.evOffset = state.evRows.length;
            renderEvents();
            var st = await api('api/events/stats');
            // v1.2.13：按设备统计用于摄像头快捷标签角标；顺带把历史设备补进筛选下拉
            state.evCounts = st.byDid || {};
            state.evAllTotal = st.total || 0; // v1.2.15：全量清空按钮的确认条数
            loadEvFilters();
            renderEvChips();
            $('evStats').innerHTML = '<span>累计事件 <b>' + (st.total || 0) + '</b></span>' +
                '<span>今日 <b>' + (st.today || 0) + '</b></span>' +
                '<span>未处理 <b>' + (st.unhandled || 0) + '</b></span>';
            $('btnEvMore').hidden = state.evRows.length >= state.evTotal;
        } catch (e) {
            toast('事件加载失败：' + e.message, 'err');
        } finally {
            state.evBusy = false;
        }
    }

    /** v1.2.13 事件页摄像头快捷标签：与 evFilterDid 联动，角标为全量事件数。 */
    function renderEvChips() {
        var box = $('evChips');
        if (!box) return;
        box.innerHTML = camChipsHtml($('evFilterDid').value || '', state.evCounts);
        bindCamChips('evChips', function (did) {
            if (($('evFilterDid').value || '') === did) return;
            selectCamFilter('evFilterDid', did);
            updateEvLockChip();
            loadEvents(true);
        });
    }

    /** 单张事件卡片（DOM 结构保持不变，批量/快照/删除的事件委托继续生效）。 */
    function evCardHtml(e) {
        var thumb = e.snapshotUrl
            ? '<img class="ev-thumb" src="' + esc(withToken(snapFullUrl(e.snapshotUrl))) + '" alt="事件快照" data-evact="snapshot" data-evid="' + e.id + '">'
            : '<div class="ev-nothumb">无快照</div>';
        var score = (e.score != null && e.score > 0)
            ? ' · 变化 ' + Number(e.score).toFixed(2) : '';
        return '<article class="ev-card' + (state.evSelected.has(e.id) ? ' sel' : '') +
            '" data-evid="' + e.id + '">' +
            '<div class="ev-card-top">' +
                '<input type="checkbox" class="ev-check" data-evid="' + e.id + '"' +
                    (state.evSelected.has(e.id) ? ' checked' : '') + ' aria-label="选择事件">' + thumb +
            '</div>' +
            '<div class="ev-body">' +
                '<div class="ev-title">' + esc(e.title || e.typeName) + '</div>' +
                '<div class="ev-meta">' + esc(e.deviceName || e.did) + ' · ' + fmtTime(e.createdAt) + '</div>' +
                '<div class="ev-meta">' + esc(e.typeName || e.type) + score +
                    (e.repeat > 0 ? ' · <span class="ev-repeat" title="持续变动期间的 ' + e.repeat + ' 次重复触发已合并到本条">×' + e.repeat + '</span>' : '') + '</div>' +
                '<div class="ev-tags"><span class="ev-tag ' + (e.handled ? 'handled' : 'unhandled') +
                    '">' + (e.handled ? '已处理' : '未处理') + '</span>' +
                    (e.level && e.level !== 'info' ? '<span class="ev-tag">' + esc(e.level) + '</span>' : '') +
                '</div>' +
                '<div class="ev-actions">' +
                    (e.snapshotUrl ? '<button class="btn tiny soft" data-evact="snapshot" data-evid="' + e.id + '">查看快照</button>' : '') +
                    '<button class="btn tiny soft" data-evact="replay" data-evid="' + e.id + '">看回放</button>' +
                    (e.type === 'person' || e.type === 'vehicle' || e.type === 'pet'
                        ? '<button class="btn tiny soft" data-evact="archive" data-evid="' + e.id + '">打开归档</button>' : '') +
                    '<button class="btn tiny soft" data-evact="handled" data-evid="' + e.id +
                        '" data-evval="' + (e.handled ? 'false' : 'true') + '">' +
                        (e.handled ? '取消处理' : '标记已处理') + '</button>' +
                    '<button class="btn tiny soft-danger" data-evact="del" data-evid="' + e.id + '">删除</button>' +
                '</div>' +
            '</div></article>';
    }

    /** v1.2.13 事件按摄像头分组：每组一个可折叠标题（设备名 / 条数 / 未处理数），组内仍按时间倒序。 */
    function renderEvents() {
        var box = $('evList');
        renderEvChips();
        if (!state.evRows.length) {
            box.innerHTML = '<div class="ev-empty">暂无事件。请先在「设置 → 运动侦测与自动录像」启用侦测。</div>';
            updateEvBatchUi();
            return;
        }
        var groups = new Map();
        state.evRows.forEach(function (e) {
            var k = String(e.did || '');
            if (!groups.has(k)) groups.set(k, []);
            groups.get(k).push(e);
        });
        var keys = cameraGroupKeys(Array.from(groups.keys()));
        // v2.3.1 全部设备视图默认折叠：仅保留第一组展开，其余只显示分组头（条数 / 未处理）
        if (state.evCollapsePending && keys.length > 1) {
            state.evCollapsed = new Set(keys.slice(1));
        }
        state.evCollapsePending = false;
        box.innerHTML = keys.map(function (did) {
            var rows = groups.get(did);
            var unhandled = rows.filter(function (e) { return !e.handled; }).length;
            var collapsed = state.evCollapsed.has(did);
            return '<section class="ev-group" data-evgroup="' + esc(did) + '">' +
                '<button type="button" class="cam-group-toggle ev-group-head" aria-expanded="' + (!collapsed) + '">' +
                    '<span class="cam-caret">' + (collapsed ? '▸' : '▾') + '</span>' +
                    '<span class="cam-group-name">' + esc(cameraName(did)) + '</span>' +
                    '<span class="cam-group-did">' + esc(did) + '</span>' +
                    '<span class="cam-group-count">' + rows.length + ' 条' +
                        (unhandled ? ' · 未处理 ' + unhandled : '') + '</span>' +
                '</button>' +
                '<div class="ev-grid ev-group-body' + (collapsed ? ' is-collapsed' : '') + '">' +
                    rows.map(evCardHtml).join('') +
                '</div></section>';
        }).join('');
        box.querySelectorAll('.ev-group-head').forEach(function (b) {
            b.addEventListener('click', function () {
                var sec = b.closest('.ev-group');
                var did = sec.getAttribute('data-evgroup');
                var body = sec.querySelector('.ev-group-body');
                var nowCollapsed = !state.evCollapsed.has(did);
                if (nowCollapsed) state.evCollapsed.add(did); else state.evCollapsed.delete(did);
                body.classList.toggle('is-collapsed', nowCollapsed);
                b.querySelector('.cam-caret').textContent = nowCollapsed ? '▸' : '▾';
                b.setAttribute('aria-expanded', String(!nowCollapsed));
            });
        });
        updateEvBatchUi();
    }

    /** v2.3.1 打开该事件的归档快照：飞牛宿主内直接调起文件管理；
     *  独立浏览器则展示归档路径并复制到剪贴板（可粘到飞牛文件管理的「前往」）。 */
    async function evOpenArchive(id) {
        try {
            var r = await api('api/events/' + id + '/archive-path');
            if (r && r.ok && r.path) {
                if (trimState.app) { trimOpenFile(r.path); return; }
                copyText(r.path);
                toast('归档路径已复制，可在飞牛文件管理中打开：' + r.path, 'ok');
                return;
            }
            toast('该事件尚未归档：归档开启后新产生的 AI 事件才会归档', 'err');
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('查询归档路径失败：' + e.message, 'err');
        }
    }

    function updateEvBatchUi() {
        var n = state.evSelected.size;
        var ids = state.evRows.map(function (e) { return e.id; });
        $('evSelAll').checked = ids.length > 0 && ids.every(function (id) { return state.evSelected.has(id); });
        $('evSelInfo').textContent = n ? ('已选 ' + n + ' 条事件') : '勾选事件后可批量标记或删除';
        $('btnEvBatchHandled').disabled = n === 0;
        $('btnEvBatchDel').disabled = n === 0;
        $('btnEvClearSel').hidden = n === 0;
        Array.prototype.forEach.call(document.querySelectorAll('.ev-card'), function (card) {
            card.classList.toggle('sel', state.evSelected.has(Number(card.getAttribute('data-evid'))));
        });
    }

    async function evBatchHandled() {
        var ids = Array.from(state.evSelected);
        if (!ids.length) return;
        try {
            var r = await api('api/events/batch/handled', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ids: ids })
            });
            toast('已标记 ' + (r.updated || 0) + ' 条事件', 'ok');
            await loadEvents(true);
            loadDashboard();
        } catch (e) { toast('操作失败：' + e.message, 'err'); }
    }

    /**
     * v1.2.15 批量删除：服务端已改为单次原子重写日志、上限 5000 条，
     * 前端仍按 1000 一组串行提交，避免超大 JSON 请求体，同时给出累计结果。
     */
    async function evBatchDelete() {
        if (state.evDeleting) return;
        var ids = Array.from(state.evSelected);
        if (!ids.length) return;
        if (!confirm('确认删除选中的 ' + ids.length + ' 条事件？对应快照也会一并删除，操作不可恢复。')) return;
        state.evDeleting = true;
        try {
            var done = 0;
            for (var i = 0; i < ids.length; i += 1000) {
                var r = await api('api/events/batch/delete', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ ids: ids.slice(i, i + 1000) })
                });
                done += r.deleted || 0;
            }
            toast('已删除 ' + done + ' 条事件', 'ok');
            state.camRecentSig = null; // 首页卡片缩略图缓存需刷新
            await loadEvents(true);
            loadDashboard();
        } catch (e) { toast('删除失败：' + e.message, 'err'); }
        finally { state.evDeleting = false; }
    }

    /** v1.2.15 当前筛选条件（供清空接口复用，口径与 evQuery 一致）。 */
    function evFilterBody() {
        var b = { confirm: 'CLEAR' };
        var did = $('evFilterDid').value;
        var type = $('evFilterType').value;
        var handled = $('evFilterHandled').value;
        var date = $('evFilterDate').value;
        if (did) b.did = did;
        if (type) b.type = type;
        if (handled !== '') b.handled = (handled === 'true');
        if (date) b.date = date;
        return b;
    }

    /**
     * v1.2.15 一次清空：filtered=true 只清当前筛选，false 清全部。
     * 两者都要求二次确认；清空后同步刷新事件页、概览统计与首页卡片快照。
     */
    async function evClear(filtered) {
        if (state.evDeleting) return;
        var body = filtered ? evFilterBody() : { confirm: 'CLEAR' };
        var hasFilter = !!(body.did || body.type || body.handled !== undefined || body.date);
        var all = !filtered || !hasFilter;
        var n = all ? state.evAllTotal : state.evTotal;
        if (!n) { toast('当前没有可清空的事件', 'ok'); return; }
        var scope = (all ? '全部 ' : '当前筛选下的 ') + n + ' 条事件告警';
        if (!confirm('确认清空' + scope + '？\n对应的快照文件会一并删除，操作不可恢复。\n\n' +
                (all ? '这是全量清空，无需再分批删除。' : '仅清空匹配当前筛选条件的事件。'))) return;
        state.evDeleting = true;
        try {
            var r = await api('api/events/clear', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('已清空 ' + (r.deleted || 0) + ' 条事件', 'ok');
            state.evSelected.clear();
            state.camRecent = {}; state.camRecentIndex = {}; state.camRecentSig = null;
            await loadEvents(true);
            loadDashboard();
        } catch (e) { toast('清空失败：' + e.message, 'err'); }
        finally { state.evDeleting = false; }
    }

    /**
     * v1.2.16 一键清理历史糊屏事件：服务端逐张检查快照，只删「大片纯灰 + 局部残差」
     * 的残缺帧与体积异常小的纯色灰屏帧，正常画面事件全部保留。全库扫描需数秒。
     */
    async function evPurgeBroken() {
        if (state.evDeleting) return;
        if (!confirm('确认清理历史糊屏 / 灰屏事件？\n\n系统会检查每条事件的快照，只删除画面残缺（大片灰色、半透明残影）或纯色灰屏的记录，正常画面事件会保留。\n对应快照文件也会一并删除，操作不可恢复。')) return;
        state.evDeleting = true;
        var btn = $('btnEvPurgeBroken'), label = btn ? btn.textContent : '';
        if (btn) { btn.disabled = true; btn.textContent = '检查中…'; }
        try {
            var r = await api('api/events/purge-broken', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ confirm: 'PURGE' })
            });
            toast(r.deleted ? ('已清理 ' + r.deleted + ' 条糊屏事件') : '没有发现糊屏事件', 'ok');
            state.evSelected.clear();
            state.camRecent = {}; state.camRecentIndex = {}; state.camRecentSig = null;
            await loadEvents(true);
            loadDashboard();
        } catch (e) { toast('清理失败：' + e.message, 'err'); }
        finally {
            state.evDeleting = false;
            if (btn) { btn.disabled = false; btn.textContent = label; }
        }
    }

    async function evSingle(id, act, val) {
        if (act === 'del' && !confirm('确认删除这条事件？快照也会一并删除。')) return;
        try {
            if (act === 'handled') {
                await api('api/events/' + id, {
                    method: 'PUT', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ handled: val === 'true' })
                });
            } else {
                await api('api/events/' + id, { method: 'DELETE' });
            }
            await loadEvents(true);
            loadDashboard();
        } catch (e) { toast('操作失败：' + e.message, 'err'); }
    }

    function openEvSnapshot(id) {
        // 事件页行优先；首页摄像头卡片的快照来自 /api/events/recent 的独立缓存
        var e = state.evRows.find(function (x) { return x.id === id; }) || state.camRecentIndex[id];
        if (!e || !e.snapshotUrl) return;
        state.evSnapshotId = id;
        $('evSnapshotTitle').textContent = (e.deviceName || e.did) + ' · ' + fmtTime(e.createdAt);
        var img = $('evSnapshotImg');
        img.alt = '快照不可用或已被清理';
        img.onerror = function () { $('evSnapshotTitle').textContent += ' · 快照不可用或已被清理'; };
        img.src = withToken(snapFullUrl(e.snapshotUrl));
        $('evSnapshotModal').hidden = false;
    }

    function closeEvSnapshot() {
        $('evSnapshotModal').hidden = true;
        state.evSnapshotId = 0;
        var img = $('evSnapshotImg');
        img.onerror = null;
        img.removeAttribute('src');
    }

    /**
     * v1.2.19 事件跳转回放：把回放页切到该摄像头、该事件日期，并 seek 到事件时刻。
     * 该时刻没有录像时 seekTo 会给出明确提示，不做静默失败。
     */
    function openEvReplay(id) {
        // 事件页行优先；首页卡片快照对应的缓存事件可能不在 evRows 里，找不到时提示
        var e = state.evRows.find(function (x) { return x.id === id; });
        if (!e || !e.createdAt) { toast('该事件没有可定位的时间信息', 'err'); return; }
        closeEvSnapshot();
        state.pbPending = { did: e.did, tMs: Number(e.createdAt) };
        var tab = document.querySelector('.tab[data-tab="playback"]');
        if (tab) tab.click();
    }

    /** 回放页就绪后应用「事件跳转」待办：定位摄像头 + 日期 + 时刻（一次性）。 */
    async function applyPbPending() {
        var p = state.pbPending;
        if (!p || !p.did) return;
        state.pbPending = null;
        var pb = state.pb;
        pb.did = p.did;
        pb.dateStr = localDateStr(new Date(p.tMs));
        $('pbCam').value = p.did;
        $('pbDate').value = pb.dateStr;
        await loadTimeline();
        seekTo(p.tMs);
    }

    /* ---------- 设置页：运动侦测与自动录像 ---------- */
    async function loadMotionSettings() {
        try {
            var m = await api('api/motion/config');
            $('setMotionEnabled').checked = !!m.enabled;
            $('setAutoRecord').checked = !!m.autoRecord;
            $('setMotionThreshold').value = m.threshold;
            $('setRecordTail').value = m.recordTailSec;
            $('setMotionSchedule').checked = !!m.scheduleEnabled;
            $('setMotionScheduleRow').hidden = !m.scheduleEnabled;
            $('setMotionActiveStart').value = minuteToHHMM(m.activeStartMin);
            $('setMotionActiveEnd').value = minuteToHHMM(m.activeEndMin);
            $('setEventRetention').value = m.retentionDays;
            $('setEventMax').value = m.maxEvents;
            var selected = (m.dids && m.dids.length) ? m.dids : [];
            var all = selected.length === 0;
            $('setMotionAllDids').checked = all;
            await renderMotionDids(selected, all);
            await refreshRoiDeviceOptions();
            $('setMotionHint').textContent = m.enabled
                ? '当前已启用运动侦测' + (m.autoRecord ? '与自动录像' : '') + '，参数保存后立即生效，无需重启。'
                : '当前未启用，不会产生事件，也不会自动录像。';
        } catch (e) {
            $('setMotionHint').textContent = '读取运动侦测设置失败：' + e.message;
        }
    }

    /** 渲染「生效摄像头」复选列表；all=true 时回落为全部设备（dids 留空）。 */
    async function renderMotionDids(selected, all) {
        var box = $('setMotionDids');
        if (all) { box.innerHTML = ''; return; }
        var set = new Set(selected || []);
        var html = '';
        try {
            var dev = await api('api/devices');
            var list = (dev && dev.devices) || [];
            if (!list.length) {
                html = '<div class="empty">暂无可列出设备，请先在首页刷新设备列表</div>';
            } else {
                list.forEach(function (c) {
                    var checked = set.has(c.did) ? ' checked' : '';
                    var off = c.online ? '' : ' <span class="did-off">(离线)</span>';
                    html += '<label><input type="checkbox" value="' + escapeHtml(c.did) + '"' + checked + '>'
                        + escapeHtml(c.name || c.did) + off + '</label>';
                });
            }
        } catch (e) {
            html = '<div class="empty">设备列表读取失败：' + escapeHtml(e.message) + '</div>';
        }
        box.innerHTML = html;
    }

    async function saveMotionSettings() {
        var dids;
        if ($('setMotionAllDids').checked) {
            dids = [];
        } else {
            dids = Array.prototype.slice
                .call(document.querySelectorAll('#setMotionDids input:checked'))
                .map(function (i) { return i.value; });
        }
        // 时段计划开启时要求两个时间点有效；关闭时不提交时段值（null → 后端沿用当前配置）
        var actStart = hhmmToMinute($('setMotionActiveStart').value);
        var actEnd = hhmmToMinute($('setMotionActiveEnd').value);
        if ($('setMotionSchedule').checked && (actStart == null || actEnd == null)) {
            toast('请选择有效的侦测时段（开始与结束时间都要填）', 'err');
            return;
        }
        if (!$('setMotionSchedule').checked) {
            actStart = null;
            actEnd = null;
        }
        var body = {
            enabled: $('setMotionEnabled').checked,
            autoRecord: $('setAutoRecord').checked,
            threshold: Number($('setMotionThreshold').value),
            recordTailSec: Number($('setRecordTail').value),
            scheduleEnabled: $('setMotionSchedule').checked,
            activeStartMin: actStart,
            activeEndMin: actEnd,
            retentionDays: Number($('setEventRetention').value),
            maxEvents: Number($('setEventMax').value),
            dids: dids
        };
        try {
            await api('api/motion/config', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('运动侦测设置已保存并立即生效', 'ok');
            loadMotionSettings();
            loadDashboard();
        } catch (e) { toast('保存失败：' + e.message, 'err'); }
    }

    /* ---------- v1.2.19 事件通知推送 ---------- */
    /** 分钟 → "HH:MM"；1440（24:00 全天结束）在 &lt;input type=time&gt; 中不合法，回落 23:59 显示。 */
    function minuteToHHMM(min) {
        if (min == null || min < 0) return '';
        var n = Math.min(min, 1439);
        var h = Math.floor(n / 60), m = n % 60;
        return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
    }

    /** "HH:MM" → 分钟；空或非法返回 null（JSON null → 后端沿用当前值，不报错覆盖）。 */
    function hhmmToMinute(v) {
        if (!v) return null;
        var p = v.split(':');
        var n = (Number(p[0]) || 0) * 60 + (Number(p[1]) || 0);
        return (n >= 0 && n <= 1439) ? n : null;
    }

    async function loadNotifySettings() {
        try {
            var n = await api('api/notify/settings');
            $('setNotifyEnabled').checked = !!n.enabled;
            var types = n.types || [];
            document.querySelectorAll('#setNotifyTypes input[type=checkbox]').forEach(function (i) {
                i.checked = types.length === 0 || types.indexOf(i.value) >= 0;
            });
            // 全空 = 推送全部类型，勾上全部复选框直观呈现
            $('setNotifyQuietStart').value = minuteToHHMM(n.quietStartMin);
            $('setNotifyQuietEnd').value = minuteToHHMM(n.quietEndMin);
            $('setNotifyWebhook').value = n.webhookUrl || '';
            $('setNotifySecret').value = '';
            $('setNotifySecret').placeholder = n.webhookSecretSet ? '已配置，留空保持原值' : '未配置';
            $('setNotifyMailEnabled').checked = !!n.mailEnabled;
            $('setNotifySmtpHost').value = n.smtpHost || '';
            $('setNotifySmtpPort').value = n.smtpPort || 465;
            $('setNotifySmtpSsl').checked = n.smtpSsl !== false;
            $('setNotifySmtpUser').value = n.smtpUser || '';
            $('setNotifySmtpPass').value = '';
            $('setNotifySmtpPass').placeholder = n.smtpPassSet ? '已配置，留空保持原值' : '未配置';
            $('setNotifyMailFrom').value = n.mailFrom || '';
            $('setNotifyMailTo').value = n.mailTo || '';
            $('setNotifyHint').textContent = (n.enabled ? '推送已启用。' : '推送未启用。')
                + (n.webhookUrl ? 'Webhook 已配置。' : '') + (n.mailEnabled ? '邮件通道已开启。' : '');
        } catch (e) {
            $('setNotifyHint').textContent = '读取通知设置失败：' + e.message;
        }
    }

    function collectNotifyBody() {
        var types = Array.prototype.slice
            .call(document.querySelectorAll('#setNotifyTypes input[type=checkbox]:checked'))
            .map(function (i) { return i.value; });
        var body = {
            enabled: $('setNotifyEnabled').checked,
            types: types,
            quietStartMin: hhmmToMinute($('setNotifyQuietStart').value),
            quietEndMin: hhmmToMinute($('setNotifyQuietEnd').value),
            webhookUrl: $('setNotifyWebhook').value.trim(),
            mailEnabled: $('setNotifyMailEnabled').checked,
            smtpHost: $('setNotifySmtpHost').value.trim(),
            smtpPort: Number($('setNotifySmtpPort').value) || 465,
            smtpSsl: $('setNotifySmtpSsl').checked,
            smtpUser: $('setNotifySmtpUser').value.trim(),
            mailFrom: $('setNotifyMailFrom').value.trim(),
            mailTo: $('setNotifyMailTo').value.trim()
        };
        var secret = $('setNotifySecret').value.trim();
        if (secret) body.webhookSecret = secret;
        var pass = $('setNotifySmtpPass').value;
        if (pass) body.smtpPass = pass;
        return body;
    }

    async function saveNotifySettings() {
        var start = hhmmToMinute($('setNotifyQuietStart').value);
        var end = hhmmToMinute($('setNotifyQuietEnd').value);
        if ((start >= 0) !== (end >= 0)) {
            toast('静默时段需要同时填写开始和结束（或都留空）', 'err');
            return;
        }
        try {
            await api('api/notify/settings', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(collectNotifyBody())
            });
            toast('通知推送设置已保存', 'ok');
            loadNotifySettings();
        } catch (e) { toast('保存失败：' + e.message, 'err'); }
    }

    async function testNotify(channel) {
        // 先保存再测试：保证测试用的是页面上的最新配置
        var start = hhmmToMinute($('setNotifyQuietStart').value);
        var end = hhmmToMinute($('setNotifyQuietEnd').value);
        if ((start >= 0) !== (end >= 0)) {
            toast('静默时段需要同时填写开始和结束（或都留空）', 'err');
            return;
        }
        $('setNotifyHint').textContent = '正在发送测试' + (channel === 'all' ? '通知' : channel) + '…';
        try {
            await api('api/notify/settings', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(collectNotifyBody())
            });
            var r = await api('api/notify/test?channel=' + channel, { method: 'POST' });
            var lines = [];
            ['webhook', 'mail'].forEach(function (k) {
                if (r[k] !== undefined) lines.push(k + '：' + (r[k] === null ? '发送成功' : r[k]));
            });
            $('setNotifyHint').textContent = lines.join(' · ') || '未选择通道';
            toast(lines.join(' · ') || '测试完成', 'ok');
        } catch (e) {
            $('setNotifyHint').textContent = '测试失败：' + e.message;
            toast('测试失败：' + e.message, 'err');
        }
    }

    async function showMotionStatus() {
        try {
            var s = await api('api/motion/status');
            var d = s.devices || {};
            var lines = Object.keys(d).map(function (k) {
                return k + '：评分 ' + d[k].lastScore + '，触发 ' + d[k].triggered + ' 次';
            });
            var scope = (s.dids && s.dids.length) ? '指定 ' + s.dids.length + ' 台设备' : '全部在线设备';
            $('setMotionHint').textContent = '侦测 ' + (s.enabled ? '开' : '关') +
                ' · 自动录像 ' + (s.autoRecord ? '开' : '关') +
                ' · 范围 ' + scope +
                ' · 事件 ' + ((s.events && s.events.total) || 0) + ' 条' +
                (lines.length ? '\n' + lines.join('\n') : '\n（尚无采样数据）');
        } catch (e) { toast('读取运行状态失败：' + e.message, 'err'); }
    }

    /* ---------- 设置页：检测排除区域（ROI）编辑器（v1.2.19） ---------- */
    var roi = { did: '', zones: [], drag: null };

    /** 填充「检测排除区域」设备下拉；与生效摄像头复选共用设备列表口径。 */
    async function refreshRoiDeviceOptions() {
        var sel = $('setRoiDid');
        try {
            var dev = await api('api/devices');
            var list = (dev && dev.devices) || [];
            sel.innerHTML = list.length
                ? list.map(function (c) {
                    return '<option value="' + escapeHtml(c.did) + '">'
                        + escapeHtml(c.name || c.did) + (c.online ? '' : '（离线）') + '</option>';
                }).join('')
                : '<option value="">暂无设备</option>';
        } catch (e) {
            sel.innerHTML = '<option value="">设备列表读取失败</option>';
        }
    }

    function sizeRoiCanvas() {
        var img = $('roiImg'), cv = $('roiCanvas');
        var w = img.clientWidth || img.naturalWidth || 320;
        var h = img.clientHeight || img.naturalHeight || 180;
        cv.width = w;
        cv.height = h;
        drawRoi();
    }

    /** 依归一化坐标把所有矩形（含拖拽预览）画到覆盖层。 */
    function drawRoi() {
        var cv = $('roiCanvas'), g = cv.getContext('2d');
        g.clearRect(0, 0, cv.width, cv.height);
        var rects = roi.zones.map(function (z) {
            return { x: z.x * cv.width, y: z.y * cv.height, w: z.w * cv.width, h: z.h * cv.height };
        });
        if (roi.drag) rects.push(roi.drag.cur);
        rects.forEach(function (r) {
            g.fillStyle = 'rgba(255,72,72,0.28)';
            g.strokeStyle = '#ff4848';
            g.lineWidth = 2;
            g.fillRect(r.x, r.y, r.w, r.h);
            g.strokeRect(r.x, r.y, r.w, r.h);
        });
    }

    function renderRoiZones() {
        var box = $('roiZones');
        if (!roi.zones.length) {
            box.innerHTML = '<span class="muted small">未框选任何区域（全画面参与侦测）</span>';
            return;
        }
        box.innerHTML = roi.zones.map(function (z, i) {
            return '<span class="roi-zone-tag">区域 ' + (i + 1)
                + ' · ' + Math.round(z.w * 100) + '%×' + Math.round(z.h * 100) + '%'
                + ' <button class="btn tiny soft" data-roidel="' + i + '" type="button">删除</button></span>';
        }).join('');
    }

    async function openRoiEditor() {
        var did = $('setRoiDid').value;
        if (!did) { toast('暂无设备可选，请先在首页刷新设备列表', 'err'); return; }
        roi.did = did;
        roi.zones = [];
        roi.drag = null;
        $('roiTitle').textContent = '框选排除区域';
        $('roiImg').removeAttribute('src');
        $('roiModal').hidden = false;
        var img = $('roiImg');
        img.onerror = function () {
            img.onerror = null;
            $('roiTitle').textContent = '框选排除区域 · 最近画面加载失败（设备离线或抓拍超时）';
        };
        img.onload = function () {
            img.onerror = null;
            sizeRoiCanvas();
        };
        img.src = withToken('api/snapshot/' + encodeURIComponent(did) + '?t=' + Date.now());
        try {
            var r = await api('api/motion/zones/' + encodeURIComponent(did));
            roi.zones = (r.zones || []).slice();
            renderRoiZones();
            if (img.complete && img.naturalWidth) sizeRoiCanvas();
        } catch (e) {
            toast('排除区域读取失败：' + e.message, 'err');
        }
    }

    function closeRoiEditor() {
        $('roiModal').hidden = true;
        roi.drag = null;
        var img = $('roiImg');
        img.onload = null;
        img.onerror = null;
        img.removeAttribute('src');
    }

    async function saveRoiZones() {
        try {
            var r = await api('api/motion/zones/' + encodeURIComponent(roi.did), {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ zones: roi.zones })
            });
            roi.zones = r.zones || [];
            toast('排除区域已保存并立即生效（'
                + (r.excludedBlocks || 0) + '/64 个侦测块被排除）', 'ok');
            closeRoiEditor();
        } catch (e) {
            toast('保存失败：' + e.message, 'err');
        }
    }

    async function clearRoiZones() {
        if (!roi.zones.length) { toast('当前没有排除区域', 'ok'); return; }
        roi.zones = [];
        await saveRoiZones();
    }

    /* ---------- 多宫格预览（v1.2.21） ---------- */
    var grid = { tiles: [], timer: null, tourTimer: null, page: 0 };

    function gridCells() {
        var n = parseInt($('gridLayout').value, 10) || 2;
        return n * n;
    }

    function gridOnlineDids() {
        return (state.devices || []).filter(function (c) { return c.online; }).map(function (c) { return String(c.did); });
    }

    function openGrid() {
        var dids = gridOnlineDids();
        if (!dids.length) { toast('当前没有在线摄像头', 'err'); return; }
        stopGridOnly();
        $('gridModal').hidden = false;
        $('gridHint').hidden = true;
        $('gridLoading').hidden = false;
        grid.page = 0;
        grid.dids = dids;
        enterOverlayHistory('grid');
        renderGridPage();
    }

    function renderGridPage() {
        var cells = gridCells();
        var cols = Math.sqrt(cells);
        var dids = grid.dids;
        var pageCount = Math.max(1, Math.ceil(dids.length / cells));
        if (grid.page >= pageCount) grid.page = 0;
        var slice = dids.slice(grid.page * cells, grid.page * cells + cells);
        $('gridLoading').hidden = true;
        $('gridTitle').textContent = '多宫格预览 · ' + (grid.dids.length) + ' 路在线'
            + (pageCount > 1 ? '（第 ' + (grid.page + 1) + '/' + pageCount + ' 页）' : '');
        var body = $('gridBody');
        body.style.setProperty('--grid-cols', String(cols));
        body.innerHTML = slice.map(function (did) {
            var c = findDevice(did) || {};
            return '<div class="live-tile" data-griddid="' + esc(did) + '">' +
                '<img alt="' + esc(c.name || did) + '" loading="lazy">' +
                '<span class="tile-name">' + esc(c.name || did) + '</span>' +
                '<span class="tile-go" title="进入高清预览">›</span>' +
                '</div>';
        }).join('');
        grid.tiles = slice.map(function (did) {
            return { did: did, img: body.querySelector('.live-tile[data-griddid="' + did + '"] img') };
        });
        grid.tiles.forEach(function (t, i) { refreshTile(t, i); });
        if (grid.timer) clearInterval(grid.timer);
        grid.timer = setInterval(function () {
            if ($('gridModal').hidden) return;
            grid.tiles.forEach(function (t, i) { refreshTile(t, i); });
        }, 1000);
        setupGridTour(pageCount);
    }

    function refreshTile(t, i) {
        if (!t.img) return;
        // 错峰：第 i 路偏移 i*120ms，避免每秒集中请求
        setTimeout(function () {
            if ($('gridModal').hidden || !t.img) return;
            t.img.src = withToken('api/frame/' + encodeURIComponent(t.did)) + '&t=' + Date.now();
        }, i * 120);
    }

    function setupGridTour(pageCount) {
        if (grid.tourTimer) { clearInterval(grid.tourTimer); grid.tourTimer = null; }
        var sec = parseInt($('gridTour').value, 10) || 0;
        if (!sec || pageCount <= 1) return;
        grid.tourTimer = setInterval(function () {
            if ($('gridModal').hidden) return;
            grid.page = (grid.page + 1) % pageCount;
            renderGridPage();
        }, sec * 1000);
    }

    function stopGridOnly() {
        if (grid.timer) { clearInterval(grid.timer); grid.timer = null; }
        if (grid.tourTimer) { clearInterval(grid.tourTimer); grid.tourTimer = null; }
        grid.tiles = [];
        grid.dids = [];
        $('gridModal').hidden = true;
        $('gridBody').innerHTML = '';
    }

    /** 应用内关闭宫格：退历史条目并停止取帧。 */
    function closeGrid() {
        var wasOpen = !$('gridModal').hidden;
        stopGridOnly();
        if (wasOpen) {
            leaveOverlayHistory();
            loadDevices(false);
        }
    }

    /* ---------- 录像回放 ---------- */
    function openPlayer(name) {
        // 从录像文件弹层点「播放」：关闭弹层并复用已压的弹层历史条目（换类型），
        // 不再叠加新条目——避免「播放→返回」出现一层按不掉的死记录
        if (!$('filesModal').hidden) {
            closeFilesModalQuiet();
            if (overlayHistoryDepth) { switchOverlayKind('player'); }
            else { enterOverlayHistory('player'); }
        } else {
            enterOverlayHistory('player');
        }
        $('playerTitle').textContent = '录像回放 · ' + name;
        $('playerModal').hidden = false;
        var v = $('playerVideo');
        v.src = withToken('api/recordings/' + encodeURIComponent(name));
        v.play().catch(function () {});
    }
    function stopPlayerOnly() {
        $('playerModal').hidden = true;
        var v = $('playerVideo');
        // v1.2.22：与预览一致——先退原生全屏，刚退出时延迟销毁，避免僵尸全屏播放层
        if (exitNativeFullscreen(v)) {
            setTimeout(function () { teardownVideoEl(v); }, 400);
        } else {
            teardownVideoEl(v);
        }
    }
    function closePlayer() {
        if ($('playerModal').hidden && overlayKind !== 'player') return;
        stopPlayerOnly();
        if (overlayKind === 'player') leaveOverlayHistory();
    }

    /* ---------- 时间轴回放 ---------- */
    function pad2(x) { return String(x).padStart(2, '0'); }
    function localDateStr(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
    function hms(ms) { var d = new Date(ms); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()); }

    /** 回放页时间轴静默刷新周期：让新封段的分段与“录制中”块的增长及时反映出来。 */
    var PB_LIVE_MS = 15000;

    /** v2.2.5 双 video 无缝接续：活动（正在播放/展示）与待命（预载下一段）元素。 */
    function pbV() {
        return document.getElementById(state.pb.activeB ? 'pbVideoB' : 'pbVideo');
    }
    function pbIdle() {
        return document.getElementById(state.pb.activeB ? 'pbVideo' : 'pbVideoB');
    }
    /** 清空待命元素的旧预载（换段/换摄像头后缓冲已失效），并清掉来源标记。 */
    function pbResetIdle() {
        var idle = pbIdle();
        try { idle.removeAttribute('src'); idle.load(); } catch (e) { /* 忽略 */ }
        idle.dataset.srcName = '';
    }

    /** v2.0.3 回放倍速（>4 倍自动静音）：作用于当前活动元素（v2.2.5 双 video 后取 pbV()）。 */
    function applyPbRate() {
        var v = pbV();
        if (!v) return;
        var r = parseFloat($('pbSpeed').value) || 1;
        v.playbackRate = r;
        v.muted = r > 4;
    }

    function startPbLive() {
        var pb = state.pb;
        if (pb.liveTimer) return;
        pb.liveTimer = setInterval(refreshTimelineLive, PB_LIVE_MS);
    }

    function stopPbLive() {
        var pb = state.pb;
        if (pb.liveTimer) { clearInterval(pb.liveTimer); pb.liveTimer = null; }
    }

    function enterPlayback() {
        renderPbCam();
        var pb = state.pb;
        var today = localDateStr(new Date());
        $('pbDate').max = today;
        if (!pb.dateStr) { $('pbDate').value = today; pb.dateStr = today; }
        else { $('pbDate').value = pb.dateStr; }
        if (pb.did) { $('pbCam').value = pb.did; }
        else if (state.devices.length) { pb.did = String(state.devices[0].did); $('pbCam').value = pb.did; }
        setZoomUi();
        startPbLive();
        // v2.3.1 每次进入回放页都自动播放（米家式，不只首次）：
        // - 首次进入：自动续播最近一段录像，让一进回放就看到刚录的画面；
        //   带事件跳转待办进来时不自动播，交给 applyPbPending 精确定位；
        // - 再次进入：离开回放页时视频会被 pausePb 暂停，直接恢复播放（保留上次进度）；
        //   若上次已播到当天最后一段的末尾，则重新定位到最新一段续播。
        if (!pb.inited) { pb.inited = true; loadTimeline(!state.pbPending); return; }
        if (state.pbPending) { return; } // 事件跳转待办由 applyPbPending 负责定位起播
        var v = pbV();
        if (pb.idx >= 0 && v.src) {
            if (v.ended) { loadTimeline(true); }
            else {
                // v2.3.1 第二十一轮：续播不再静默吞拒——重试一次，仍被浏览器拦截
                // （自动播放策略/后台页节流）时明确提示用户点画面继续，杜绝
                // 「切菜单回来回放悄悄停了却看不出原因」的回归无从排查
                var p = v.play();
                if (p && p.catch) {
                    p.catch(function (err) {
                        try { console.warn('[PB] 续播被拒：' + (err && err.name || err)); } catch (e1) { /* 忽略 */ }
                        setTimeout(function () {
                            var p2 = v.play();
                            if (p2 && p2.catch) {
                                p2.catch(function () { toast('回放未自动续播，点一下画面即可继续', 'err'); });
                            }
                        }, 300);
                    });
                }
            }
        }
    }

    function renderPbCam() {
        var sel = $('pbCam');
        var cur = sel.value;
        sel.innerHTML = state.devices.map(function (c) {
            return '<option value="' + esc(c.did) + '">' + esc(cameraLabel(c.did)) + '</option>';
        }).join('');
        if (cur) sel.value = cur;
    }

    function pausePb() {
        var el = pbV();
        if (el && !el.paused) { el.pause(); }
    }

    /**
     * 拉取时间轴并重绘。autoLatest=true（v2.3.1，仅首次进入回放页）时自动从最后一段
     * 的末尾往前 15 秒开始播放——米家式「一进回放就看到最近的画面」，不再黑屏等待点选。
     */
    async function loadTimeline(autoLatest) {
        var pb = state.pb;
        pb.did = $('pbCam').value || pb.did;
        pb.dateStr = $('pbDate').value || localDateStr(new Date());
        if (!pb.did) { $('tlBlocks').innerHTML = '<div class="tl-no">请先选择摄像头</div>'; return; }
        pb.dayStart = new Date(pb.dateStr + 'T00:00:00').getTime();
        pb.idx = -1; pb.curStart = 0;
        pb.waitingNext = false; pb.swapping = false; // 换摄像头/日期：取消任何进行中的自动接续
        try {
            var data = await api('api/timeline/' + encodeURIComponent(pb.did) + '?date=' + encodeURIComponent(pb.dateStr));
            pb.segs = data.segments || [];
        } catch (e) { pb.segs = []; toast(e.message, 'err'); }
        // v1.2.21 变动时段高亮：拉当天该摄像头的画面变动事件时刻（失败不阻塞时间轴）
        pb.motionMs = [];
        try {
            var mv = await api('api/events?did=' + encodeURIComponent(pb.did)
                + '&type=motion&date=' + encodeURIComponent(pb.dateStr) + '&limit=1000');
            pb.motionMs = (mv.list || []).map(function (e) { return e.createdAt; })
                .filter(function (t) { return typeof t === 'number' && t > 0; });
        } catch (e2) { /* 高亮不可用时仅少一个提示层 */ }
        computeWin(true);
        renderTimeline();
        if (autoLatest && pb.segs.length && pb.idx < 0) {
            // v2.3.1 起始点再前移：进回放从「上一段末尾 30 秒」起播，跨过换段边界
            // 无缝连续播到最新（原先只从最后一段末尾前 15 秒起，起点到不了前一段）；
            // 只有一段时从段首起播。loadSeg 内部会自动起播。
            if (pb.segs.length >= 2) {
                var prev = pb.segs[pb.segs.length - 2];
                loadSeg(pb.segs.length - 2, Math.max(0, prev.durMs / 1000 - 30));
            } else {
                loadSeg(0, 0);
            }
        }
    }

    /** 分段集合的轻量签名（名称/起止/时长/录制态），用于判断静默刷新是否真有变化。 */
    function tlSig(segs) {
        return segs.map(function (s) {
            return s.name + '@' + s.startMs + ':' + s.durMs + (s.recording ? 'r' : '');
        }).join('|');
    }

    /**
     * 回放页停留期间的静默刷新：重新拉取当天时间轴，让“新封段的分段”出现、
     * “录制中”块随时间增长。不重置日期 / 缩放 / 窗口，也不打断正在播放的视频；
     * 当前播放段按文件名重新定位（避免新段插入导致索引漂移）。
     */
    async function refreshTimelineLive() {
        var pb = state.pb;
        if (pb.liveBusy || !pb.did) return;
        pb.liveBusy = true;
        try {
            var data = await api('api/timeline/' + encodeURIComponent(pb.did)
                + '?date=' + encodeURIComponent(pb.dateStr || localDateStr(new Date())));
            var segs = data.segments || [];
            if (tlSig(segs) === tlSig(pb.segs)) return;
            var curName = (pb.idx >= 0 && pb.segs[pb.idx]) ? pb.segs[pb.idx].name : null;
            pb.segs = segs;
            if (curName) {
                var ni = -1;
                for (var k = 0; k < segs.length; k++) { if (segs[k].name === curName) { ni = k; break; } }
                if (ni >= 0) {
                    pb.idx = ni;
                    pb.curStart = segs[ni].startMs;
                } else {
                    // 当前段已不在（如刚被保留策略清理），停止播放并复位选中
                    pb.idx = -1;
                    var pv0 = pbV();
                    pv0.pause(); pv0.removeAttribute('src'); pv0.load();
                    pbResetIdle();
                    $('pbEmpty').hidden = false;
                }
            }
            renderTimeline();
        } catch (e) {
            // 静默失败，不打扰正在观看；门禁失效走全局 401 处理
            if (e.status === 401) { scheduleGateRelock(); }
        } finally {
            pb.liveBusy = false;
        }
    }

    function computeWin(resetAnchor) {
        var pb = state.pb;
        pb.winDur = pb.zoom * 3600 * 1000;
        var dayEnd = pb.dayStart + 86400000;
        if (pb.zoom === 24) { pb.winStart = pb.dayStart; return; }
        var anchor;
        if (resetAnchor) {
            anchor = pb.idx >= 0 ? pb.curStart
                : (pb.dateStr === localDateStr(new Date()) ? Date.now() : pb.dayStart + 9 * 3600 * 1000);
        } else { anchor = pb.winStart + pb.winDur / 2; }
        var start = anchor - pb.winDur / 2;
        pb.winStart = Math.max(pb.dayStart, Math.min(start, dayEnd - pb.winDur));
    }

    function setZoomUi() {
        document.querySelectorAll('#pbZoom [data-zoom]').forEach(function (b) {
            b.classList.toggle('primary', String(state.pb.zoom) === b.getAttribute('data-zoom'));
        });
    }

    function pct(ms) { var pb = state.pb; return (ms - pb.winStart) / pb.winDur * 100; }

    function renderTimeline() {
        var pb = state.pb;
        var steps = pb.zoom === 24 ? { tick: 3600, every: 2 }
            : pb.zoom === 6 ? { tick: 900, every: 2 }   // 15 分钟细线，30 分钟标签
            : { tick: 300, every: 3 };                // 5 分钟细线，15 分钟标签（1 小时窗）
        var ruler = $('tlRuler');
        var winEnd = pb.winStart + pb.winDur;
        var tickMs = steps.tick * 1000;
        // v2.3.1 第二十二轮：手机窄屏防标签重叠——按主刻度实际像素间距动态抽稀标签
        // （细线保留、只稀疏文字），且抽稀激活时改用 HH:MM 短标签（宽度减半，
        // 「HH:MM:SS」8 字符在 ~60px 间距下已顶满相邻刻度，iPhone 真机仍显拥挤）。
        // 桌面宽度下维持原密度与完整秒级时间。
        var trackW = ($('tlTrack').clientWidth || 640);
        var majorMs = tickMs * steps.every;
        var majorSpacingPx = majorMs / pb.winDur * trackW;
        var needThin = majorSpacingPx < 76;
        var labelEvery = needThin ? steps.every * Math.ceil(76 / majorSpacingPx) : steps.every;
        var first = Math.ceil(pb.winStart / tickMs) * tickMs;
        var html = '';
        for (var t = first; t < winEnd; t += tickMs) {
            var nth = Math.round(t / tickMs);
            var major = (nth % labelEvery === 0);
            var lab = '';
            if (major) {
                var d2 = new Date(t);
                lab = needThin ? (pad2(d2.getHours()) + ':' + pad2(d2.getMinutes()))
                    : (pad2(d2.getHours()) + ':' + pad2(d2.getMinutes()) + ':' + pad2(d2.getSeconds()));
            }
            html += '<div class="tl-tick' + (major ? ' major' : '') + '" style="left:' + pct(t).toFixed(3) + '%">'
                + (major ? '<span class="tl-lab">' + lab + '</span>' : '') + '</div>';
        }
        ruler.innerHTML = html;

        var blocks = $('tlBlocks');
        if (!pb.segs.length) {
            blocks.innerHTML = '<div class="tl-no">该摄像头当天无录像</div>';
        } else {
            // v2.2.5 段块内直接显示开始时间（块足够宽时才放得下），不再只靠悬停 title
            var tw = $('tlTrack').clientWidth || 0;
            blocks.innerHTML = pb.segs.map(function (s, i) {
                var l = Math.max(0, pct(s.startMs));
                var r = Math.min(100, pct(s.startMs + s.durMs));
                var w = Math.max(0.4, r - l);
                var cls = 'tl-block' + (s.recording ? ' rec' : '') + (i === pb.idx ? ' cur' : '');
                var title = hms(s.startMs) + ' ~ ' + hms(s.startMs + s.durMs) + (s.recording ? '（录制中）' : '');
                var lab = (w / 100 * tw >= 34)
                    ? '<span class="tl-blklab">' + hms(s.startMs).slice(0, 5) + '</span>' : '';
                return '<div class="' + cls + '" data-i="' + i + '" style="left:' + l.toFixed(3) + '%;width:' + w.toFixed(3) + '%" title="' + esc(title) + '">' + lab + '</div>';
            }).join('');
        }
        updateCursor();
        renderMotionMarks();
        updateMeta();
    }

    /** v1.2.21 时间轴变动时段高亮：绿色刻度标出画面变动事件时刻（点击时间轴任意处本就精确 seek）。 */
    function renderMotionMarks() {
        var pb = state.pb;
        var layer = $('tlMotion');
        var ms = pb.motionMs || [];
        if (!ms.length) { layer.innerHTML = ''; return; }
        // 视窗内相邻刻度去重：同一像素点只画一个
        var marks = [];
        var lastPct = -1;
        ms.slice().sort(function (a, b) { return a - b; }).forEach(function (t) {
            var x = pct(t);
            if (x < 0 || x > 100) return;
            if (x - lastPct < 0.4) return;
            lastPct = x;
            marks.push('<div class="tl-motion-mark" style="left:' + x.toFixed(3) + '%" title="'
                + esc(hms(t) + ' 画面变动') + '"></div>');
        });
        layer.innerHTML = marks.join('');
    }

    function updateCursor() {
        var pb = state.pb;
        var cur = $('tlCursor');
        var v = pbV();
        if (pb.idx < 0 || !v.src) { cur.hidden = true; return; }
        var x = pct(pb.curStart + (v.currentTime || 0) * 1000);
        if (x < 0 || x > 100) { cur.hidden = true; return; }
        cur.hidden = false;
        cur.style.left = x.toFixed(3) + '%';
    }

    function updateMeta() {
        var pb = state.pb;
        var v = pbV();
        if (pb.idx >= 0 && v.src) {
            $('pbNow').textContent = hms(pb.curStart + (v.currentTime || 0) * 1000)
                + (pb.waitingNext ? ' · 接续下一段…' : v.paused ? ' 已暂停' : ' 播放中');
            var s = pb.segs[pb.idx];
            // v2.2.5 元信息显示真实起止时间点
            $('pbSeg').textContent = '分段 ' + (pb.idx + 1) + '/' + pb.segs.length + ' · '
                + hms(s.startMs) + ' ~ ' + hms(s.startMs + s.durMs)
                + (s.recording ? ' · 录制中' : '');
        } else {
            $('pbNow').textContent = pb.segs.length ? ('当天共 ' + pb.segs.length + ' 段录像，点击时间轴回放') : '当天无录像';
            $('pbSeg').textContent = '';
        }
    }

    function seekTo(tMs) {
        var pb = state.pb;
        if (!pb.segs.length) { toast('该时刻没有录像', 'err'); return; }
        var i = -1, k;
        for (k = 0; k < pb.segs.length; k++) { if (pb.segs[k].startMs <= tMs) i = k; else break; }
        var offset = 0;
        if (i < 0) { i = 0; }
        else {
            var s = pb.segs[i];
            if (tMs <= s.startMs + s.durMs) {
                offset = (tMs - s.startMs) / 1000;
            } else {
                var j = -1;
                for (k = i + 1; k < pb.segs.length; k++) { if (pb.segs[k].startMs >= tMs) { j = k; break; } }
                if (j < 0) { toast('该时刻之后没有录像', 'err'); return; }
                i = j;
            }
        }
        loadSeg(i, offset);
        if (pb.zoom !== 24) { computeWin(true); }
        renderTimeline();
    }

    function loadSeg(i, offsetSec) {
        var pb = state.pb;
        if (i < 0 || i >= pb.segs.length) return;
        pb.swapping = false; // 手动换段：放弃任何进行中的自动交接
        pb.waitingNext = false; // 同时取消末帧等待交接（waitThenSwap 轮询会自行退出）
        pb.idx = i;
        var s = pb.segs[i];
        pb.curStart = s.startMs;
        pb.pendingOffset = offsetSec || 0;
        pbResetIdle(); // 手动换段后待命元素里的旧预载缓冲已失效，清掉防误用
        var v = pbV();
        $('pbEmpty').hidden = true;
        v.dataset.fsResume = ''; // v2.3.1 第二十二轮：新段重新给予 iOS 内联恢复机会
        delete v.dataset.fsGiveUp;
        v.src = withToken('api/recordings/' + encodeURIComponent(s.name));
        v.play().catch(function () {});
        // v2.3.1 录制段提示每次会话只弹一次：自动接续/循环进入录制段时不再反复打扰
        if (s.recording && !pb.recToastShown) {
            pb.recToastShown = true;
            toast('该段正在录制，播放已写入的部分（到当前进度为止）；看实时画面请用“实时预览”', '');
        }
        renderTimeline();
    }

    /**
     * v2.2.5 预载下一段：当前段剩余 < 8 秒时把下一段装进待命 video 预缓冲，
     * 播到边界直接交换显示，消除“一段播完再加载”的可见卡顿（米家式连续回放）。
     * 待命元素就绪标记存在 dataset.srcName（来源段文件名），readyState>=2 即可无缝切换。
     * v2.3.1 两个黑屏根因修复：
     * 1) 同一段已开始预载时不再重设 src——原实现每个 timeupdate（约 4 次/秒）都重设，
     *    缓冲被反复打断、readyState 永远到不了 2，边界只能走整段重载兜底（黑屏）；
     * 2) 待命元素显式 preload='auto'——Chrome/WebView 默认 preload=metadata，
     *    不 play() 就只拉元数据不缓冲数据，边界起播必然等待。
     */
    function maybePreloadNext() {
        var pb = state.pb;
        if (pb.idx < 0 || pb.loop === 'seg') return;
        var v = pbV();
        if (!v.duration || !isFinite(v.duration)) return;
        // v2.3.1 预载起点再前移并按倍速折算：保留约 10 秒壁钟时间缓冲下一段
        // （1x 时媒体剩余 10s 触发、2x 时 20s 触发），高倍速下也能缓冲到位，
        // 避免“本段放完了下一段还没进来”的等待。
        if ((v.duration - (v.currentTime || 0)) * 1000 > 10000 * (v.playbackRate || 1)) return;
        var ni = pb.idx + 1;
        if (ni >= pb.segs.length) ni = pb.loop === 'day' ? 0 : -1;
        if (ni < 0) return;
        var idle = pbIdle();
        var s = pb.segs[ni];
        if (idle.dataset.srcName === s.name) return; // 已在预载或已就绪：绝不重设 src 打断缓冲
        idle.preload = 'auto';
        idle.dataset.srcName = s.name;
        idle.playbackRate = v.playbackRate || 1; // 预载时同步倍速/静音，切换后即刻生效
        idle.muted = v.muted;
        idle.src = withToken('api/recordings/' + encodeURIComponent(s.name));
    }

    /** v2.3.1 边界提前交接：本段只剩 <0.4s（按倍速折算）且下一段已缓冲就绪时立即
     *  换段，不等 ended 事件——ended 之后浏览器可能先清帧，等待事件派发也会空转。 */
    function maybeSwapEarly() {
        var pb = state.pb;
        if (pb.idx < 0 || pb.loop === 'seg' || pb.swapping) return;
        var v = pbV();
        if (!v.duration || !isFinite(v.duration)) return;
        var remain = (v.duration - (v.currentTime || 0)) * 1000;
        var rate = v.playbackRate || 1;
        if (remain <= 0 || remain > 400 * rate) return;
        var ni = pb.idx + 1;
        if (ni >= pb.segs.length) {
            if (pb.loop === 'day' && pb.segs.length > 0) { ni = 0; } else { return; }
        }
        var idle = pbIdle();
        if (idle.dataset.srcName !== pb.segs[ni].name || idle.readyState < 2) return;
        swapTo(ni);
    }

    /** 无缝切换：两个 video 交换角色。
     *  v2.3.1 出帧感知交接：先暂停旧元素（保留最后一帧作为过渡底图）、起播待命元素，
     *  待其真正渲染出新帧（requestVideoFrameCallback / timeupdate）再隐藏旧元素——
     *  原实现同步互切透明度，待命元素尚未出帧时两帧之间露出 stage 黑底（黑屏闪现根因）。 */
    function swapTo(ni) {
        var pb = state.pb;
        if (pb.swapping) return;
        pb.swapping = true;
        pb.waitingNext = false; // 等待交接被 swapTo 接管（轮询检测到标记清除后自行退出）
        var cur = pbV(), idle = pbIdle(); // 先取旧活动元素 / 待命元素
        pb.activeB = !pb.activeB; // 交换活动/待命角色（pbV/pbIdle 据此取元素）
        cur.pause(); // pause 后最后一帧继续显示，作为交接期间的底图
        pb.idx = ni;
        pb.curStart = pb.segs[ni].startMs;
        applyPbRate();
        if (pb.zoom !== 24) { computeWin(true); }
        renderTimeline();
        // 待命元素位于旧元素下层（DOM 顺序靠前），此时显示不影响画面
        idle.classList.remove('pb-off');
        idle.play().catch(function () {});
        var hidden = false;
        var hide = function () {
            if (hidden) return;
            hidden = true;
            pb.swapping = false;
            cur.classList.add('pb-off');
            // 淡出完成后释放旧元素缓冲（双 video 不会同时占两份完整缓冲）
            setTimeout(function () {
                try { cur.removeAttribute('src'); cur.load(); } catch (e) { /* 忽略 */ }
                cur.dataset.srcName = '';
            }, 500);
        };
        if (typeof idle.requestVideoFrameCallback === 'function') {
            idle.requestVideoFrameCallback(hide);
        } else {
            idle.addEventListener('timeupdate', hide, { once: true });
        }
        setTimeout(hide, 500); // 兜底：出帧回调异常时也能完成交接
    }

    function pbNextSeg() {
        var pb = state.pb;
        // v2.1.0 循环播放：单段=重放当前段；当天=最后一段后回到第一段
        if (pb.loop === 'seg' && pb.idx >= 0) {
            loadSeg(pb.idx, 0);
            return;
        }
        var ni = pb.idx + 1;
        if (ni >= pb.segs.length) {
            if (pb.loop === 'day' && pb.segs.length > 0) {
                ni = 0;
            } else {
                // v2.3.1 播到尾端先静默刷新时间轴再定论：播放期间新封的段可能还没
                // 进入时间轴（静默刷新 15 秒一轮），刷新后有新段就无缝续播；
                // 当前段仍在录制且时长已增长时，从断点继续播放已写入部分。
                var endedAt = pbV().currentTime || 0;
                var oldDur = (pb.segs[pb.idx] || {}).durMs || 0;
                refreshTimelineLive().then(function () {
                    if (pb.idx < 0) { return; }
                    if (pb.idx + 1 < pb.segs.length) { pbNextSeg(); return; }
                    var cur = pb.segs[pb.idx];
                    if (cur && cur.recording && cur.durMs > oldDur && endedAt > 0) {
                        loadSeg(pb.idx, Math.max(0, endedAt - 0.5));
                        return;
                    }
                    toast('已到当天最后一段', 'ok');
                    updateMeta();
                });
                return;
            }
        }
        // v2.2.5 下一段已在待命元素缓冲就绪 → 直接交换显示（无加载）；
        // v2.3.1 未就绪时不再整段重载（黑屏根因），改为冻结当前末帧等待缓冲完成后无缝交接
        var idle = pbIdle();
        if (idle.dataset.srcName === pb.segs[ni].name && idle.readyState >= 2) {
            swapTo(ni);
            return;
        }
        waitThenSwap(ni);
        if (pb.zoom !== 24) { computeWin(true); renderTimeline(); }
    }

    /** v2.3.1 末帧等待交接：播到边界时下一段还没缓冲好——暂停当前元素冻结末帧作底图，
     *  必要时补起待命元素的预载，轮询就绪后走 swapTo 无缝交接；期间时间轴显示
     *  “接续下一段…”。手动换段（loadSeg）会取消等待，不会与自动交接抢元素。 */
    function waitThenSwap(ni) {
        var pb = state.pb;
        if (pb.waitingNext) return;
        pb.waitingNext = true;
        var cur = pbV();
        cur.pause(); // ended 后本就停了，显式 pause 确保末帧保留作过渡底图
        var s = pb.segs[ni];
        var idle = pbIdle();
        if (idle.dataset.srcName !== s.name) {
            idle.preload = 'auto';
            idle.dataset.srcName = s.name;
            idle.playbackRate = cur.playbackRate || 1;
            idle.muted = cur.muted;
            idle.src = withToken('api/recordings/' + encodeURIComponent(s.name));
        }
        updateMeta();
        var poll = setInterval(function () {
            if (!pb.waitingNext || pb.idx < 0) { clearInterval(poll); return; }
            var idl = pbIdle();
            if (idl.dataset.srcName === pb.segs[ni].name && idl.readyState >= 2) {
                clearInterval(poll);
                pb.waitingNext = false;
                if (!pb.swapping) { swapTo(ni); }
            }
        }, 150);
    }

    function autoFollow() {
        var pb = state.pb;
        if (pb.zoom === 24 || pb.idx < 0) return;
        var ms = pb.curStart + (pbV().currentTime || 0) * 1000;
        if (ms < pb.winStart || ms > pb.winStart + pb.winDur) {
            computeWin(true);
            renderTimeline();
        }
    }

    function setZoom(z) {
        state.pb.zoom = z;
        setZoomUi();
        computeWin(true);
        renderTimeline();
    }

    function pbPan(dir) {
        var pb = state.pb;
        if (pb.zoom === 24) {
            var d = new Date(pb.dateStr + 'T00:00:00');
            d.setDate(d.getDate() + dir);
            var ds = localDateStr(d);
            if (ds > localDateStr(new Date())) { return; }
            $('pbDate').value = ds;
            loadTimeline();
        } else {
            var dayEnd = pb.dayStart + 86400000;
            var ns = pb.winStart + dir * pb.winDur;
            ns = Math.max(pb.dayStart, Math.min(ns, dayEnd - pb.winDur));
            if (ns === pb.winStart) { return; }
            pb.winStart = ns;
            renderTimeline();
        }
    }

    /* ---------- 问题反馈与诊断（v2.3.1） ---------- */
    /** 记录一条前端错误（环形，最多 50 条，随诊断报告上报；已由 api()/全局捕获调用）。 */
    function noteErr(msg) {
        state.errLog.push({ t: Date.now(), msg: String(msg || '').slice(0, 200) });
        if (state.errLog.length > 50) { state.errLog.shift(); }
    }

    function loadFeedback() {
        api('api/diagnostics/summary').then(function (d) {
            var up = d.uptimeMs ? Math.floor(d.uptimeMs / 3600000) + ' 小时 '
                + Math.floor(d.uptimeMs % 3600000 / 60000) + ' 分钟' : '-';
            $('fbSummary').textContent = 'v' + d.version + ' · ' + d.os + ' · Java ' + d.java
                + ' · 已运行 ' + up + ' · ' + d.deviceCount + ' 台摄像头'
                + (d.logAvailable ? ' · 日志可用' : ' · 无日志文件');
            $('fbGiteeState').textContent = d.tokens.gitee ? '已配置 ✓' : '未配置';
            $('fbGithubState').textContent = d.tokens.github ? '已配置 ✓' : '未配置';
        }).catch(function (e) {
            $('fbSummary').textContent = '诊断概览读取失败：' + e.message;
        });
        loadUpdateStatus(false);
    }

    function loadUpdateStatus(refresh) {
        $('fbUpdate').textContent = '检测中…';
        api('api/diagnostics/update-check' + (refresh ? '?refresh=true' : '')).then(function (d) {
            renderUpdate(d);
        }).catch(function (e) {
            $('fbUpdate').textContent = '更新检测失败：' + e.message;
        });
    }

    function renderUpdate(d) {
        var el = $('fbUpdate');
        if (!d.latest) {
            el.textContent = '更新检测失败（无法访问 Gitee/GitHub）' + (d.error ? '：' + d.error : '');
            return;
        }
        if (d.hasUpdate) {
            el.innerHTML = '<span class="fb-up-new">发现新版本 ' + esc(d.latest) + '</span>'
                + '（当前 v' + esc(d.current) + '，来源 ' + esc(d.source) + '）：'
                + '<a href="' + esc(d.url) + '" target="_blank" rel="noopener">前往下载</a>';
        } else {
            el.textContent = '已是最新版本 v' + d.current + (d.source ? '（与远端 ' + esc(d.source) + ' 一致）' : '');
        }
    }

    async function submitIssue(platform, btn) {
        var old = btn.textContent;
        btn.disabled = true;
        btn.textContent = '生成报告并上报…';
        try {
            var r = await api('api/diagnostics/issue', {
                method: 'POST',
                body: JSON.stringify({
                    platform: platform,
                    description: $('fbDesc').value || '',
                    errors: state.errLog
                })
            });
            if (r.error === 'no_token') {
                await feedbackFallback(platform);
                return;
            }
            $('fbStatus').innerHTML = '已上报到 ' + (platform === 'gitee' ? 'Gitee' : 'GitHub')
                + '：<a href="' + esc(r.url) + '" target="_blank" rel="noopener">查看 Issue</a>'
                + '（报告已自动脱敏）';
            toast('上报成功', 'ok');
        } catch (e) {
            $('fbStatus').textContent = '上报失败：' + e.message;
            toast('上报失败，可改用「复制诊断报告」手动反馈', 'err');
        } finally {
            btn.disabled = false;
            btn.textContent = old;
        }
    }

    /** 无令牌降级：拉取完整报告复制到剪贴板，再打开对应平台的 Issue 新建页。 */
    async function feedbackFallback(platform) {
        try {
            var res = await api('api/diagnostics/export');
            var text = await res.text();
            copyText(text); // copyText 自带 toast
            var pf = await api('api/diagnostics/prefill');
            var url = pf[platform] || '';
            window.open(url, '_blank');
            $('fbStatus').textContent = '未配置上报令牌：完整报告已复制到剪贴板，已打开 Issue 新建页，'
                + '粘贴到正文即可（也可先在下方配置令牌实现一键上报）';
        } catch (e2) {
            $('fbStatus').textContent = '降级上报失败：' + e2.message;
        }
    }

    async function exportDiagnostics() {
        try {
            var res = await api('api/diagnostics/export');
            var text = await res.text();
            var blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'ainvr-diagnostics-' + fileStamp() + '.txt';
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(function () { URL.revokeObjectURL(a.href); }, 2000);
            toast('诊断文件已导出', 'ok');
        } catch (e) {
            toast('导出失败：' + e.message, 'err');
        }
    }

    function saveReportToken(platform, inputId, stateId) {
        var token = $(inputId).value.trim();
        api('api/diagnostics/tokens', { method: 'PUT', body: JSON.stringify({ platform: platform, token: token }) })
            .then(function () {
                $(inputId).value = '';
                $(stateId).textContent = token ? '已配置 ✓' : '未配置';
                toast(token ? '令牌已保存' : '令牌已清除', 'ok');
            })
            .catch(function (e) { toast('保存失败：' + e.message, 'err'); });
    }

    /* ---------- 复制 ---------- */
    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function () { toast('已复制', 'ok'); },
                function () { fallbackCopy(text); });
        } else { fallbackCopy(text); }
    }
    function fallbackCopy(text) {
        var ta = document.createElement('textarea');
        ta.value = text; document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); toast('已复制', 'ok'); }
        catch (e) { toast('复制失败，请手动选择', 'err'); }
        ta.remove();
    }

    /* ---------- 事件绑定 ---------- */
    function bind() {
        $('btnLogin').addEventListener('click', startLogin);
        $('password').addEventListener('keydown', function (e) { if (e.key === 'Enter') startLogin(); });
        $('username').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('password').focus(); });
        $('btnCaptcha').addEventListener('click', submitCaptcha);
        $('captchaCode').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitCaptcha(); });
        $('btnVerify').addEventListener('click', submitVerify);
        $('verifyCode').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitVerify(); });
        $('captchaImg').addEventListener('click', function () {
            if (state.loginToken) this.src = 'api/login/' + state.loginToken + '/captcha?t=' + Date.now();
        });

        $('btnLogout').addEventListener('click', async function () {
            if (!confirm('确认登出米家账号？本机访问密码仍保留，重新登录后无需再设访问密码。')) return;
            try { await api('api/logout', { method: 'POST' }); } catch (e) {}
            await refreshGate();
        });
        $('btnLock').addEventListener('click', function () { lockConsole(); });
        $('btnSetup').addEventListener('click', submitSetup);
        $('btnUnlock').addEventListener('click', submitUnlock);
        $('btnGenLink').addEventListener('click', genUnlockLink);
        $('btnCopyLink').addEventListener('click', copyUnlockLink);
        $('setupPw1').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('setupPw2').focus(); });
        $('setupPw2').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitSetup(); });
        $('lockPw').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitUnlock(); });
        $('btnRefresh').addEventListener('click', function () {
            loadDevices(true); loadRecordings(); loadCamEvents();
        });
        $('btnRecRefresh').addEventListener('click', function () { loadRecordings(true); });
        $('recFilter').addEventListener('change', function () {
            // 换筛选条件后清空勾选，避免删到当前列表里看不见的文件
            state.recSelected.clear();
            loadRecordings(true);
        });

        // v1.2.2 录像批量操作：表头全选、行勾选、批量删除 / 打包下载
        $('recSelAll').addEventListener('change', function (e) {
            recSelectableNames().forEach(function (n) {
                if (e.target.checked) state.recSelected.add(n);
                else state.recSelected.delete(n);
            });
            syncRecChecks();
            updateBatchUi();
        });
        $('recList').addEventListener('change', function (e) {
            var cb = e.target.closest && e.target.closest('input[data-rsel]');
            if (!cb) return;
            var name = cb.getAttribute('data-rsel');
            if (cb.checked) state.recSelected.add(name);
            else state.recSelected.delete(name);
            var tr = cb.closest('tr');
            if (tr) tr.classList.toggle('rec-selected', cb.checked);
            updateBatchUi();
        });
        $('btnRecClearSel').addEventListener('click', function () {
            state.recSelected.clear(); syncRecChecks(); updateBatchUi();
        });
        $('btnRecBatchDel').addEventListener('click', recBatchDelete);
        $('btnRecBatchZip').addEventListener('click', recBatchZip);

        // v1.2.8 事件告警页：筛选、批量、快照查看
        $('btnEvRefresh').addEventListener('click', function () { loadEvents(true); });
        $('btnEvMore').addEventListener('click', function () { loadEvents(false); });
        $('btnEvReset').addEventListener('click', function () {
            $('evFilterDid').value = '';
            $('evFilterType').value = '';
            $('evFilterHandled').value = '';
            $('evFilterDate').value = '';
            updateEvLockChip();
            loadEvents(true);
        });
        $('evLockChip').addEventListener('click', function () {
            $('evFilterDid').value = '';
            updateEvLockChip();
            loadEvents(true);
        });
        ['evFilterDid', 'evFilterType', 'evFilterHandled', 'evFilterDate'].forEach(function (id) {
            $(id).addEventListener('change', function () {
                if (id === 'evFilterDid') { updateEvLockChip(); }
                loadEvents(true);
            });
        });
        $('evSelAll').addEventListener('change', function (e) {
            state.evRows.forEach(function (r) {
                if (e.target.checked) state.evSelected.add(r.id);
                else state.evSelected.delete(r.id);
            });
            Array.prototype.forEach.call(document.querySelectorAll('.ev-check'), function (cb) {
                cb.checked = e.target.checked;
            });
            updateEvBatchUi();
        });
        $('evList').addEventListener('change', function (e) {
            var cb = e.target.closest && e.target.closest('.ev-check');
            if (!cb) return;
            var id = Number(cb.getAttribute('data-evid'));
            if (cb.checked) state.evSelected.add(id);
            else state.evSelected.delete(id);
            updateEvBatchUi();
        });
        $('evList').addEventListener('click', function (e) {
            var b = e.target.closest && e.target.closest('[data-evact]');
            if (!b) return;
            var id = Number(b.getAttribute('data-evid'));
            var act = b.getAttribute('data-evact');
            if (act === 'snapshot') openEvSnapshot(id);
            else if (act === 'replay') openEvReplay(id);
            else if (act === 'archive') evOpenArchive(id);
            else if (act === 'handled') evSingle(id, 'handled', b.getAttribute('data-evval'));
            else if (act === 'del') evSingle(id, 'del');
        });
        // 快照文件被清理 / 损坏时降级成占位块，避免列表里出现破图 alt 文本
        $('evList').addEventListener('error', function (e) {
            var img = e.target;
            if (!img.classList || !img.classList.contains('ev-thumb') || !img.parentNode) return;
            var ph = document.createElement('div');
            ph.className = 'ev-nothumb';
            ph.textContent = '快照不可用';
            img.parentNode.replaceChild(ph, img);
        }, true);
        $('btnEvBatchHandled').addEventListener('click', evBatchHandled);
        $('btnEvBatchDel').addEventListener('click', evBatchDelete);
        $('btnEvPurgeBroken').addEventListener('click', evPurgeBroken);
        $('btnEvClearFiltered').addEventListener('click', function () { evClear(true); });
        $('btnEvClearAll').addEventListener('click', function () { evClear(false); });
        $('btnEvClearSel').addEventListener('click', function () {
            state.evSelected.clear();
            Array.prototype.forEach.call(document.querySelectorAll('.ev-check'), function (cb) { cb.checked = false; });
            updateEvBatchUi();
        });
        $('evSnapshotClose').addEventListener('click', closeEvSnapshot);
        $('evSnapshotCloseBtn').addEventListener('click', closeEvSnapshot);
        $('btnEvSnapshotReplay').addEventListener('click', function () {
            if (state.evSnapshotId) openEvReplay(state.evSnapshotId);
        });
        $('evSnapshotModal').addEventListener('click', function (e) {
            if (e.target === this) closeEvSnapshot();
        });
        $('btnSaveMotion').addEventListener('click', saveMotionSettings);
        $('btnMotionStatus').addEventListener('click', showMotionStatus);
        $('btnSaveNotify').addEventListener('click', saveNotifySettings);
        $('btnTestNotifyWebhook').addEventListener('click', function () { testNotify('webhook'); });
        $('btnTestNotifyMail').addEventListener('click', function () { testNotify('mail'); });

        // v1.2.19 检测排除区域（ROI）编辑器
        $('btnOpenRoi').addEventListener('click', openRoiEditor);
        $('roiClose').addEventListener('click', closeRoiEditor);
        $('roiCancel').addEventListener('click', closeRoiEditor);
        $('roiModal').addEventListener('click', function (e) { if (e.target === this) closeRoiEditor(); });
        $('btnRoiSave').addEventListener('click', saveRoiZones);
        $('btnRoiClear').addEventListener('click', clearRoiZones);
        $('roiZones').addEventListener('click', function (e) {
            var b = e.target.closest('[data-roidel]');
            if (!b) return;
            roi.zones.splice(parseInt(b.getAttribute('data-roidel'), 10), 1);
            renderRoiZones();
            drawRoi();
        });
        (function () {
            var cv = $('roiCanvas');
            function roiPos(e) {
                var rect = cv.getBoundingClientRect();
                var x = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1);
                var y = Math.min(Math.max((e.clientY - rect.top) / rect.height, 0), 1);
                return { x: x, y: y };
            }
            cv.addEventListener('pointerdown', function (e) {
                if (!roi.did || !$('roiImg').complete) return;
                var p = roiPos(e);
                roi.drag = { ox: p.x, oy: p.y, cur: { x: p.x, y: p.y, w: 0, h: 0 } };
                try { cv.setPointerCapture(e.pointerId); } catch (err) { /* 旧 WebView 无此 API */ }
                e.preventDefault();
            });
            cv.addEventListener('pointermove', function (e) {
                if (!roi.drag) return;
                var p = roiPos(e), d = roi.drag;
                d.cur = {
                    x: Math.min(d.ox, p.x), y: Math.min(d.oy, p.y),
                    w: Math.abs(p.x - d.ox), h: Math.abs(p.y - d.oy)
                };
                drawRoi();
            });
            cv.addEventListener('pointerup', function () {
                if (!roi.drag) return;
                var cur = roi.drag.cur;
                roi.drag = null;
                if (roi.zones.length >= 8) {
                    toast('最多框选 8 个区域', 'err');
                } else if (cur.w >= 0.02 && cur.h >= 0.02) {
                    roi.zones.push(cur);
                    renderRoiZones();
                }
                drawRoi();
            });
        })();
        $('setMotionAllDids').addEventListener('change', function () {
            if (this.checked) { $('setMotionDids').innerHTML = ''; }
            else { renderMotionDids([], false); }
        });
        $('setMotionSchedule').addEventListener('change', function () {
            $('setMotionScheduleRow').hidden = !this.checked;
        });

        // 设置：存储路径 / 分段 / 端口
        $('btnBrowseDir').addEventListener('click', function () { browseDirFlow('recording'); });
        $('btnSaveDir').addEventListener('click', function () { saveSettings(false); });
        $('btnResetDir').addEventListener('click', function () {
            if (confirm('确认恢复为默认存储目录？\n当前正在录制的分段会在封段后写入默认目录。')) saveSettings(true);
        });
        $('btnRefreshDir').addEventListener('click', loadSettings);
        // v2.3.1 快照归档设置
        $('btnBrowseArchiveDir').addEventListener('click', function () { browseDirFlow('archive'); });
        $('btnSaveArchiveDir').addEventListener('click', saveSnapshotArchive);
        $('btnTestArchiveDir').addEventListener('click', verifyArchiveDir);
        $('btnSaveMqtt').addEventListener('click', saveMqtt);
        $('btnTestMqtt').addEventListener('click', testMqtt);
        $('btnSaveSegment').addEventListener('click', saveSegment);
        $('btnSaveRtsp').addEventListener('click', saveRtsp);
        $('btnSaveRtspToken').addEventListener('click', saveRtspToken);
        $('btnSaveRetention').addEventListener('click', saveRetention);
        $('btnSaveWater').addEventListener('click', saveWater);
        $('btnSaveHls').addEventListener('click', saveHls);
        $('btnSaveShare').addEventListener('click', saveShare);
        $('btnSavePw').addEventListener('click', changeAccessPassword);
        $('btnSaveAi').addEventListener('click', saveAi);

        /* v2.1.0 本地 AI 检测设置 */
        async function saveAi() {
            var btn = $('btnSaveAi');
            btn.disabled = true;
            try {
                var body = JSON.stringify({
                    enabled: $('setAiEnabled').checked,
                    minScore: parseFloat($('setAiMinScore').value) || 0.5
                });
                await api('api/settings/ai', { method: 'PUT', body: body });
                toast('AI 检测设置已保存', 'ok');
                await loadSettings();
            } catch (e) {
                if (e.status === 401) { scheduleGateRelock(); return; }
                toast('保存失败：' + e.message, 'err');
                await loadSettings();
            } finally {
                btn.disabled = false;
            }
        }

        $('btnAiDownload').addEventListener('click', async function () {
            var btn = $('btnAiDownload');
            btn.disabled = true;
            try {
                await api('api/settings/ai-model/download', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url: $('setAiModelUrl').value.trim() })
                });
                toast('模型下载已开始', 'ok');
                await loadSettings();
            } catch (e) {
                if (e.status === 401) { scheduleGateRelock(); return; }
                toast('触发下载失败：' + e.message, 'err');
                btn.disabled = false;
            }
        });

        /* v2.1.0 备份与恢复 */
        $('btnBackupExport').addEventListener('click', function () {
            // 用 <a download> 下载；withToken 满足门禁签名
            var a = document.createElement('a');
            a.href = withToken('api/settings/export');
            a.download = '';
            document.body.appendChild(a); a.click(); a.remove();
            var st = $('backupStatus');
            if (st) { st.textContent = '已开始下载备份文件（含敏感信息，请妥善保管）。'; }
        });
        $('backupImportFile').addEventListener('change', async function () {
            var file = this.files && this.files[0];
            if (!file) return;
            var st = $('backupStatus');
            if (!confirm('导入将覆盖当前全部应用设置、共享看板与访问密码凭据（导入后访问密码以备份文件中的为准），确定继续？')) {
                this.value = '';
                return;
            }
            try {
                var text = await file.text();
                var data = JSON.parse(text);
                var res = await api('api/settings/import', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (st) { st.textContent = '导入成功，设置已生效。'; }
                toast('配置导入成功', 'ok');
                await loadSettings();
                loadDevices(false);
            } catch (e) {
                if (e.status === 401) { scheduleGateRelock(); return; }
                toast('导入失败：' + e.message, 'err');
                if (st) { st.textContent = '导入失败：' + e.message; }
            } finally {
                this.value = '';
            }
        });

        // 按设备清晰度 / 云台命令格式
        $('btnRefreshQuality').addEventListener('click', loadSettings);
        $('btnSavePtzMode').addEventListener('click', savePtzMode);
        $('setQualityList').addEventListener('click', function (e) {
            var up = e.target.closest('button[data-q-up]');
            if (up) { moveDevice(up.getAttribute('data-q-up'), -1); return; }
            var down = e.target.closest('button[data-q-down]');
            if (down) { moveDevice(down.getAttribute('data-q-down'), 1); return; }
            var b = e.target.closest('button[data-q-save]');
            if (!b) return;
            var did = b.getAttribute('data-q-save');
            var sel = $('setQualityList').querySelector('select[data-q-did="' + CSS.escape(did) + '"]');
            if (sel) saveQuality(did, sel.value);
        });
        // 双摄第二镜头开关（切换即保存）
        $('setQualityList').addEventListener('change', function (e) {
            var sel = e.target.closest('select[data-dl-did]');
            if (!sel) return;
            saveDualLens(sel.getAttribute('data-dl-did'), sel.value);
        });
        $('ptzPad').addEventListener('click', function (e) {
            var b = e.target.closest('button[data-dir]');
            if (b) ptzMove(b.getAttribute('data-dir'));
        });
        // v2.3.1 全屏悬浮控制：方向键 / 喊话 / 全屏切换（事件委托与 ptzPad 同构）
        $('fsPtz').addEventListener('click', function (e) {
            var b = e.target.closest('button[data-dir]');
            if (b) ptzMove(b.getAttribute('data-dir'));
        });
        $('btnTalkFs').addEventListener('click', function () {
            var did = state.previewDid;
            if (!did) { toast('请先打开一路预览', 'err'); return; }
            talkStart(did);
        });
        $('btnFullscreen').addEventListener('click', togglePreviewFullscreen);
        $('btnFsExit').addEventListener('click', function () { exitPreviewFullscreen(false); });
        document.addEventListener('fullscreenchange', guardNativeVideoFs);
        document.addEventListener('webkitfullscreenchange', guardNativeVideoFs);
        // v2.3.1 第二十二轮：iOS 原生全屏兜底策略（MSE 路径不会触发；仅旧 iOS 走到）。
        // 第二十一轮的「退回内联」有两大缺陷：① webkitbeginfullscreen 事件触发时
        // webkitDisplayingFullscreen 可能尚未置位，带守卫的退出变成空操作 → 用户仍在
        // 系统全屏里；② 退出后系统播放器会暂停视频，我们从不恢复 → 用户回到内联看到的
        // 是一张永久黑屏（真机实测「全屏后黑屏、后续预览全黑」）。
        // 现在改为：无条件退出 → 延迟 300ms 补刀 → 恢复内联播放；恢复次数限 2 次，
        // 超限说明该 WebView 根本不支持内联播放（配置禁用），放弃对抗、让系统播放器
        // 正常播（用户可点「完成」退出）——有画面永远比黑屏强。事件上报 client-log
        // 便于远程确认劫持是否仍在发生。
        (function () {
            var pv = $('previewVideo');
            function exitNativeFs() {
                try { if (pv.webkitDisplayingFullscreen) { pv.webkitExitFullscreen(); } } catch (e) { /* 忽略 */ }
                try { if (pv.webkitExitFullscreen) { pv.webkitExitFullscreen(); } } catch (e) { /* 忽略 */ }
            }
            function resumeInlineOnce() {
                if (iosFsGuard.gaveUp || !pv.src || !pv.paused) { return; }
                if (iosFsGuard.resume >= 2) {
                    iosFsGuard.gaveUp = true;
                    reportClientDiag('ios_fs_giveup', 'preview：连续 3 次内联恢复失败，交还系统播放器');
                    return;
                }
                iosFsGuard.resume++;
                var p = pv.play(); if (p && p.catch) { p.catch(function () { /* 忽略 */ }); }
            }
            pv.addEventListener('webkitbeginfullscreen', function () {
                try { window.localStorage.setItem('ainvr_ios_fs_hijack', String(Date.now())); } catch (e) { /* 忽略 */ }
                reportClientDiag('ios_fs_begin', 'preview：系统播放器全屏劫持');
                if (iosFsGuard.gaveUp) { return; } // 已放弃对抗：让系统播放器播，用户可点「完成」退出
                exitNativeFs();
                setTimeout(function () {
                    if (!$('previewModal') || $('previewModal').hidden) { return; }
                    exitNativeFs();
                    resumeInlineOnce();
                }, 300);
            });
            pv.addEventListener('webkitendfullscreen', function () {
                reportClientDiag('ios_fs_end', 'preview：系统全屏结束');
                resumeInlineOnce();
            });
        })();
        $('btnChaseLive').addEventListener('click', function () {
            if (!chaseLiveEdge(false)) { toast('当前已是最新画面', 'ok'); }
            updateLiveBadge();
        });
        $('previewQuality').addEventListener('change', function () {
            var did = state.previewDid;
            if (!did) return;
            talkStop(true); // 清晰度切换会重建取流会话，对讲通道随之失效
            setPtzStatus('正在应用清晰度并重建取流（画面会短暂中断）…');
            saveQuality(did, $('previewQuality').value);
        });
        $('previewPtzMode').addEventListener('change', function () {
            setPtzStatus('云台格式已切为 ' + $('previewPtzMode').value
                + '，点方向键即可试用；确认有效后可到设置页保存为全局默认值。', 'warn');
        });

        // 开放接口 API 密钥
        $('btnApiGen').addEventListener('click', generateApiKey);
        $('btnApiSave').addEventListener('click', saveApiKey);
        $('btnApiDelete').addEventListener('click', deleteApiKey);
        $('btnApiCopy').addEventListener('click', function () { copyText($('apiKeyFreshText').value); });
        $('apiKeyCustomInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') saveApiKey(); });

        // 目录选择弹窗
        $('dirClose').addEventListener('click', closeDirModal);
        $('dirCancel').addEventListener('click', closeDirModal);
        $('dirConfirm').addEventListener('click', confirmDir);
        $('dirUp').addEventListener('click', function () { if (dirData.parent) loadDir(dirData.parent); });
        $('dirReload').addEventListener('click', function () { loadDir(dirData.current); });
        $('dirGo').addEventListener('click', goManualDir);
        $('dirSysPick').addEventListener('click', sysPickDir);
        // v2.3.1 内置权限面板：直达 fnOS 应用授权页（应用设置 → 存储管理）
        $('btnOpenAppPerm').addEventListener('click', function () {
            whenTrimHost(function (t) {
                if (t && typeof t.openAppSetting === 'function') {
                    try { t.openAppSetting(); } catch (e) { /* 忽略 */ }
                }
            });
        });
        $('dirManualPath').addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); goManualDir(); } });
        $('dirModal').addEventListener('click', function (e) { if (e.target === this) closeDirModal(); });

        // 时间轴回放
        $('pbCam').addEventListener('change', loadTimeline);
        $('pbDate').addEventListener('change', loadTimeline);
        $('pbRefresh').addEventListener('click', loadTimeline);
        document.querySelectorAll('#pbZoom [data-zoom]').forEach(function (b) {
            b.addEventListener('click', function () { setZoom(parseInt(b.getAttribute('data-zoom'), 10)); });
        });
        $('pbPrev').addEventListener('click', function () { pbPan(-1); });
        $('pbNext').addEventListener('click', function () { pbPan(1); });
        $('tlTrack').addEventListener('click', function (e) {
            var rect = this.getBoundingClientRect();
            var x = e.clientX - rect.left;
            var pb = state.pb;
            var tMs = pb.winStart + (x / rect.width) * pb.winDur;
            // 优先命中视觉上的录像块：高缩放档下短段只有几像素、会被最小宽度撑宽，纯按时间
            // 反推会落到段数据范围之外（最后一段后面无段可跳，表现为点录制块无反应）。直接取
            // 被点中的块索引最稳；点在空白处再走“跳到最近段”的 seekTo 逻辑。用 elementsFromPoint
            // 遍历叠层，避免红色游标等装饰层（pointer-events:none 之外的兜底）挡住块。
            var stack = document.elementsFromPoint ? document.elementsFromPoint(e.clientX, e.clientY) : [document.elementFromPoint(e.clientX, e.clientY)];
            var block = null;
            for (var k = 0; k < stack.length; k++) {
                if (stack[k].classList && stack[k].classList.contains('tl-block')) { block = stack[k]; break; }
            }
            if (block && block.getAttribute('data-i') != null && $('tlBlocks').contains(block)) {
                var bi = parseInt(block.getAttribute('data-i'), 10);
                var bs = pb.segs[bi];
                if (bs) {
                    var boff = Math.max(0, (tMs - bs.startMs) / 1000);
                    // 录制中段末尾约一个 GOP(~3s) 尚未封成分片、不可 seek，留余量
                    var bcap = bs.durMs / 1000 - (bs.recording ? 3 : 0.2);
                    boff = Math.min(boff, Math.max(0, bcap));
                    loadSeg(bi, boff);
                    if (pb.zoom !== 24) { computeWin(true); }
                    return;
                }
            }
            seekTo(tMs);
        });
        /* v2.2.5 拖动/悬停时间气泡（米家式）：鼠标或手指在时间轴上移动时提示对应时刻与有无录像 */
        (function () {
            var bub = $('tlBubble');
            var hideTimer = null;
            function showTlBubble(e) {
                var pb = state.pb;
                if (!pb.segs.length) return;
                var rect = $('tlTrack').getBoundingClientRect();
                var cx = (e.touches && e.touches.length) ? e.touches[0].clientX : e.clientX;
                var x = Math.max(0, Math.min(rect.width, cx - rect.left));
                var t = pb.winStart + (x / rect.width) * pb.winDur;
                var inSeg = false;
                for (var k = 0; k < pb.segs.length; k++) {
                    if (pb.segs[k].startMs <= t && t <= pb.segs[k].startMs + pb.segs[k].durMs) { inSeg = true; break; }
                }
                bub.textContent = hms(t) + (inSeg ? '' : '（无录像）');
                bub.style.left = Math.max(26, Math.min(rect.width - 26, x)) + 'px';
                bub.hidden = false;
                if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
            }
            $('tlTrack').addEventListener('mousemove', showTlBubble);
            $('tlTrack').addEventListener('mouseleave', function () { bub.hidden = true; });
            $('tlTrack').addEventListener('touchstart', showTlBubble, { passive: true });
            $('tlTrack').addEventListener('touchmove', showTlBubble, { passive: true });
            $('tlTrack').addEventListener('touchend', function () {
                hideTimer = setTimeout(function () { bub.hidden = true; }, 800);
            });
        })();
        (function () {
            /** v2.2.5 两个 video 都挂同一组监听：A/B 交替后事件继续生效。 */
            function bindPbVideo(el) {
                el.addEventListener('loadedmetadata', function () {
                    var pb = state.pb;
                    if (el === pbV()) {
                        if (pb.pendingOffset != null) {
                            el.currentTime = Math.min(pb.pendingOffset, Math.max(0, (el.duration || 0) - 0.05));
                            pb.pendingOffset = null;
                        }
                        applyPbRate(); // 换段（含自动接续）会重置倍率，每次元数据就绪后重新应用
                    }
                });
                el.addEventListener('timeupdate', function () {
                    maybeSwapEarly(); // v2.3.1 剩余 <0.4s 且下一段就绪：不等 ended 提前交接
                    maybePreloadNext(); // v2.2.5 剩余 <8 秒时把下一段预载进待命元素
                    updateCursor(); updateMeta(); autoFollow();
                });
                el.addEventListener('ended', pbNextSeg);
                // v2.3.1 原生 controls 已移除：播放/暂停状态同步到自定义控制按钮
                el.addEventListener('play', syncPbPlayBtn);
                el.addEventListener('pause', syncPbPlayBtn);
                // 点画面切换播放/暂停（待命元素 pb-off 有 pointer-events:none，不会误触）
                el.addEventListener('click', togglePbPlay);
                // v2.3.1 第二十一轮：手机回放黑屏诊断——加载错误不再无声，直接提示；
                // iOS 强制全屏事件只记录不拦截（预览侧已验证「退回内联」策略）
                el.addEventListener('error', function () {
                    if (!el.src) return;
                    var code = el.error ? el.error.code : 0;
                    toast('回放视频加载失败（code=' + code + '），点时间轴其他区段重试', 'err');
                });
                el.addEventListener('webkitbeginfullscreen', function () {
                    try { window.localStorage.setItem('ainvr_ios_fs_hijack', String(Date.now())); } catch (e2) { /* 忽略 */ }
                    reportClientDiag('ios_fs_begin', 'pb：回放系统全屏劫持');
                    // v2.3.1 第二十二轮：与预览同款兜底——退出 + 恢复内联播放（限 2 次），
                    // 超限放弃对抗让系统播放器播（MP4 + playsinline 通常不会走到这里）
                    if (el.dataset.fsGiveUp) { return; }
                    try { if (el.webkitExitFullscreen) { el.webkitExitFullscreen(); } } catch (e2) { /* 忽略 */ }
                    setTimeout(function () {
                        try { if (el.webkitDisplayingFullscreen) { el.webkitExitFullscreen(); } } catch (e2) { /* 忽略 */ }
                        if (!el.src || !el.paused || el.dataset.fsGiveUp) { return; }
                        var n = parseInt(el.dataset.fsResume || '0', 10);
                        if (n >= 2) { el.dataset.fsGiveUp = '1'; reportClientDiag('ios_fs_giveup', 'pb：内联恢复超限'); return; }
                        el.dataset.fsResume = String(n + 1);
                        var p = el.play(); if (p && p.catch) { p.catch(function () { /* 忽略 */ }); }
                    }, 300);
                });
                el.addEventListener('webkitendfullscreen', function () {
                    if (!el.src || !el.paused || el.dataset.fsGiveUp) { return; }
                    var n = parseInt(el.dataset.fsResume || '0', 10);
                    if (n >= 2) { el.dataset.fsGiveUp = '1'; return; }
                    el.dataset.fsResume = String(n + 1);
                    var p = el.play(); if (p && p.catch) { p.catch(function () { /* 忽略 */ }); }
                });
            }
            bindPbVideo($('pbVideo'));
            bindPbVideo($('pbVideoB'));

            /* v2.3.1 自定义播放控制（原生 controls 移除后，进度以底部全天时间轴为准） */
            function syncPbPlayBtn() {
                var v = pbV();
                $('btnPbPlay').textContent = (v && !v.paused && v.src) ? '⏸ 暂停' : '▶ 播放';
                updateMeta();
            }
            function togglePbPlay() {
                var v = pbV();
                if (!v || !v.src) {
                    // 还没选段：自动播最后一段（与进页自动续播一致）
                    if (state.pb.segs.length) { loadSeg(state.pb.segs.length - 1, 0); }
                    return;
                }
                if (v.paused) { v.play().catch(function () {}); } else { v.pause(); }
            }
            $('btnPbPlay').addEventListener('click', togglePbPlay);
            $('btnPbMute').addEventListener('click', function () {
                var v = pbV(), idle = pbIdle();
                var mute = !(v && v.muted);
                [v, idle].forEach(function (el) { if (el) { el.muted = mute; } });
                this.textContent = mute ? '🔇 静音' : '🔊 声音开';
            });

            $('pbSpeed').addEventListener('change', function () { applyPbRate(); });
            $('pbLoop').addEventListener('change', function () {
                state.pb.loop = this.value;
                toast('循环：' + (this.value === 'seg' ? '单段循环' : this.value === 'day' ? '当天循环' : '关闭'), 'ok');
            });

            /* v2.1.0 回放键盘快捷键：仅在回放页可见且焦点不在输入控件时生效。
               空格=播放/暂停；←/→=±5 秒（段内裁剪，到段首/尾则切相邻段）；Home/End=跳到当天第一/最后一段。 */
            function pbKeysActive() {
                var a = document.activeElement;
                if (a && (a.tagName === 'INPUT' || a.tagName === 'SELECT' || a.tagName === 'TEXTAREA' || a.isContentEditable)) return false;
                return $('tab-playback').hidden === false;
            }
            document.addEventListener('keydown', function (e) {
                if (!pbKeysActive()) return;
                var v2 = pbV();
                var pb = state.pb;
                function seekDelta(delta) {
                    if (pb.idx < 0 || !v2.src) return;
                    var t = (v2.currentTime || 0) + delta;
                    if (t < 0) {
                        if (pb.idx > 0) { loadSeg(pb.idx - 1, Math.max(0, (pb.segs[pb.idx - 1].durMs / 1000) - 0.5)); }
                        return;
                    }
                    var dur = v2.duration || 0;
                    if (dur && t > dur - 0.3) { pbNextSeg(); return; }
                    v2.currentTime = t;
                }
                switch (e.key) {
                    case ' ':
                        e.preventDefault();
                        if (pb.idx < 0 || !v2.src) return;
                        if (v2.paused) { v2.play().catch(function () {}); } else { v2.pause(); }
                        break;
                    case 'ArrowLeft': e.preventDefault(); seekDelta(-5); break;
                    case 'ArrowRight': e.preventDefault(); seekDelta(5); break;
                    case 'Home':
                        e.preventDefault();
                        if (pb.segs.length) { loadSeg(0, 0); }
                        break;
                    case 'End':
                        e.preventDefault();
                        if (pb.segs.length) { loadSeg(pb.segs.length - 1, 0); }
                        break;
                }
            });
        })();

        /** v2.0.1 一级导航收敛为 4 个：摄像头 / 回放 / 事件告警 / 设置（看板并入设置、录像文件并入回放）。 */
    function gotoTab(name) {
        document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });
        // v2.3.0 侧栏与底部导航承载同名页签，两处同步高亮
        document.querySelectorAll('.tab[data-tab="' + name + '"]').forEach(function (b) { b.classList.add('active'); });
        // v2.3.0 顶栏标题跟随当前页
        var titles = { cameras: '摄像头', events: '事件告警', playback: '回放', settings: '系统设置' };
        var tt = $('topbarTitle');
        if (tt) { tt.textContent = titles[name] || ''; }
        $('tab-cameras').hidden = name !== 'cameras';
        $('tab-events').hidden = name !== 'events';
        $('tab-playback').hidden = name !== 'playback';
        $('tab-settings').hidden = name !== 'settings';
        if (name === 'events') {
            loadEvFilters();
            // v1.2.14：首页卡片「查看全部」带过来的摄像头筛选，先于列表加载设置好
            if (state.evPendingDid) {
                var sel = $('evFilterDid');
                var has = Array.prototype.some.call(sel.options, function (o) {
                    return o.value === state.evPendingDid;
                });
                if (!has) {
                    var opt = document.createElement('option');
                    opt.value = state.evPendingDid;
                    opt.textContent = cameraName(state.evPendingDid);
                    sel.appendChild(opt);
                }
                sel.value = state.evPendingDid;
                state.evPendingDid = '';
            }
            updateEvLockChip();
            loadEvents(true);
        }
        if (name === 'playback') {
            enterPlayback();
            // v1.2.19：事件页「看回放」带来的定位待办（摄像头 + 日期 + 时刻）
            applyPbPending();
        } else {
            pausePb(); stopPbLive();
        }
        if (name === 'settings') {
            // v2.3.1 切页卡顿优化：30 秒内重复进入设置页不再全量重拉（设置页接口多、
            // 重复渲染开销大）；保存操作后各分组会自行刷新，需要强制最新时用页内刷新按钮
            if (Date.now() - (state.settingsLoadedAt || 0) > 30000) { loadSettings(); loadBoards(); }
        }
    }

    /** v2.2.3 设置页分组切换：只显示 data-sgroup 匹配的卡片，导航高亮同步并记忆选择。 */
    function showSettingsGroup(name) {
        if (!$('settingsNav')) { return; }
        document.querySelectorAll('#tab-settings .set-card[data-sgroup]').forEach(function (c) {
            c.hidden = c.getAttribute('data-sgroup') !== name;
        });
        // v2.3.1 热修：进入存储分组即刷新权限面板——不再限定飞牛宿主，
        // 独立浏览器也要能看到存储授权状态与指引
        if (name === 'storage') {
            refreshPermStatus();
        }
        // v2.3.1 进入实时画面分组时填充米家设备控制的摄像头下拉
        if (name === 'stream') {
            refreshMiotDevSelect();
        }
        // v2.3.1 进入帮助与反馈分组时刷新诊断概览与令牌状态
        if (name === 'feedback') {
            loadFeedback();
        }
        document.querySelectorAll('#settingsNav .settings-nav-item').forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-sgroup') === name);
        });
        try { localStorage.setItem('ainvr_set_group', name); } catch (e2) { /* 存储不可用忽略 */ }
    }

    document.querySelectorAll('.tab').forEach(function (t) {
            t.addEventListener('click', function () { gotoTab(t.getAttribute('data-tab')); });
        });
        // v2.3.1 统计卡点击直达对应页面
        document.querySelectorAll('#dashStats .stat-link').forEach(function (b) {
            b.addEventListener('click', function () { gotoTab(b.getAttribute('data-goto')); });
        });
        // v2.3.1 摄像头筛选 chips
        document.querySelectorAll('#devFilter .chip').forEach(function (b) {
            b.addEventListener('click', function () {
                state.deviceFilter = b.getAttribute('data-filter');
                renderDevices();
            });
        });

        // v2.2.3 设置页分组导航（DSM 控制面板式）：点击左分类只显示对应卡片组，记忆上次选择
        document.querySelectorAll('#settingsNav .settings-nav-item').forEach(function (b) {
            b.addEventListener('click', function () { showSettingsGroup(b.getAttribute('data-sgroup')); });
        });
        // v2.3.1 帮助与反馈：诊断导出 / 双平台 Issue 上报 / 令牌保存
        $('btnFbGitee').addEventListener('click', function () { submitIssue('gitee', this); });
        $('btnFbGithub').addEventListener('click', function () { submitIssue('github', this); });
        $('btnFbCopy').addEventListener('click', async function () {
            try {
                var res = await api('api/diagnostics/export');
                copyText(await res.text());
            } catch (e) { toast('复制失败：' + e.message, 'err'); }
        });
        $('btnFbExport').addEventListener('click', exportDiagnostics);
        $('btnFbSaveGitee').addEventListener('click', function () { saveReportToken('gitee', 'setFbGiteeToken', 'fbGiteeState'); });
        $('btnFbSaveGithub').addEventListener('click', function () { saveReportToken('github', 'setFbGithubToken', 'fbGithubState'); });
        $('btnFbCheckUpdate').addEventListener('click', function () { loadUpdateStatus(true); });
        try {
            showSettingsGroup(localStorage.getItem('ainvr_set_group') || 'storage');
        } catch (e) { showSettingsGroup('storage'); }

        // v2.3.0 主题切换：侧栏快捷按钮 + 设置页单选；快捷按钮仅深/浅两态（跟随系统只在设置页）
        $('btnTheme').addEventListener('click', function () {
            applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
        });

        // v2.3.1 米家设备控制：选摄像头即读取；控制行由 JS 整表渲染，
        // 「设置」按钮走容器事件委托（行随读取结果重建，不能逐按钮绑定）
        $('miotDevSelect').addEventListener('change', function () { loadMiotProps(this.value); });
        $('btnMiotReload').addEventListener('click', function () { loadMiotProps($('miotDevSelect').value); });
        $('miotRows').addEventListener('click', function (e) {
            var b = e.target && e.target.closest ? e.target.closest('[data-miotset]') : null;
            if (b && !b.disabled) {
                setMiotProp($('miotDevSelect').value, b.getAttribute('data-miotset'));
            }
        });
        $('btnMiotReboot').addEventListener('click', function () {
            var did = $('miotDevSelect').value;
            var name = cameraName(did);
            if (!confirm('确认重启「' + name + '」？设备约 30~60 秒恢复，期间预览与录像会中断。')) return;
            api('api/devices/' + encodeURIComponent(did) + '/reboot', { method: 'POST' })
                .then(function () { toast('重启指令已下发', 'ok'); $('miotMsg').textContent = '重启指令已下发，约 30~60 秒恢复'; })
                .catch(function (e) {
                    if (e.status === 401) { scheduleGateRelock(); return; }
                    toast('重启失败：' + e.message, 'err');
                });
        });
        document.querySelectorAll('input[name="uiTheme"]').forEach(function (r) {
            r.addEventListener('change', function () { if (r.checked) { applyTheme(r.value); } });
        });
        applyTheme(storedTheme());

        // v2.3.0 摄像头卡快照开关
        var snapCb = $('setCardSnap');
        if (snapCb) {
            snapCb.checked = cardSnapOn();
            snapCb.addEventListener('change', function () {
                try { localStorage.setItem(CARD_SNAP_KEY, snapCb.checked ? 'on' : 'off'); } catch (e2) { /* 存储不可用忽略 */ }
                applyCardSnapPref();
                var hint = $('setCardSnapHint');
                if (hint) { hint.textContent = snapCb.checked ? '已开启：卡片顶部显示最近快照，每 30 秒自动刷新。' : '已关闭：卡片不再请求快照。'; }
            });
        }

        // v2.3.1 录像文件弹层：卡片「录像文件」锁定单摄像头；设置页入口看全部；× 关闭
        $('filesClose').addEventListener('click', closeFilesModal);
        $('recLockChip').addEventListener('click', function () {
            $('recFilter').value = '';
            $('filesModal').classList.remove('files-locked');
            $('recLockChip').hidden = true;
            state.filesLockedDid = '';
            state.recSelected.clear();
            loadRecordings();
        });
        $('btnFilesAll').addEventListener('click', function () { openFilesModal(null); });
        $('filesModal').addEventListener('click', function (e) {
            if (e.target === this) { closeFilesModal(); }
        });

        // 设备卡片按钮：一次性事件委托（轮询只改属性、不重建按钮，监听持续有效）
        $('deviceGrid').addEventListener('click', function (e) {
            var b = e.target.closest('button[data-act]');
            if (!b) return;
            var did = b.getAttribute('data-did');
            var act = b.getAttribute('data-act');
            if (act === 'preview') openPreview(did);
            else if (act === 'recstart') startRecord(did);
            else if (act === 'recstop') stopRecord(did);
            else if (act === 'probe') runProbe(did, false);
            else if (act === 'deepprobe') runProbe(did, true);
            else if (act === 'wake') toggleSleep(did, true);
            else if (act === 'sleep') toggleSleep(did, false);
            else if (act === 'probeclose') {
                var box = b.closest('.cam-card').querySelector('.probe-box');
                if (box) { box.remove(); }
            }
            else if (act === 'evsnap') openEvSnapshot(Number(b.getAttribute('data-evid')));
            else if (act === 'evmore') gotoCamEvents(did);
            else if (act === 'files') openFilesModal(did);
        });

        // 首页卡片缩略图：快照文件已清理/损坏时降级成占位块，不显示破图
        $('deviceGrid').addEventListener('error', function (e) {
            var btn = e.target && e.target.parentNode;
            if (!btn || !btn.classList || !btn.classList.contains('cam-shot') || !btn.parentNode) return;
            var ph = document.createElement('div');
            ph.className = 'cam-events-empty';
            ph.textContent = '快照不可用';
            btn.parentNode.replaceChild(ph, btn);
        }, true);

        // 共享看板：卡片操作事件委托
        $('boardGrid').addEventListener('click', function (e) {
            var btn = e.target.closest('button[data-bact]');
            if (btn) handleBoardAction(btn);
        });
        $('btnBoardNew').addEventListener('click', function () { openBoardModal(null); });
        $('btnBoardRefresh').addEventListener('click', loadBoards);
        $('btnBoardSave').addEventListener('click', saveBoard);
        $('boardClose').addEventListener('click', closeBoardModal);
        $('boardCancel').addEventListener('click', closeBoardModal);
        $('boardModal').addEventListener('click', function (e) {
            if (e.target === this) closeBoardModal();
        });
        // 布局切换：保留同槽位已选摄像头，重新渲染槽位下拉
        document.querySelectorAll('input[name="boardLayout"]').forEach(function (r) {
            r.addEventListener('change', function () {
                var prev = Array.from(document.querySelectorAll('.board-slot-sel')).map(function (s) { return s.value; });
                renderBoardSlots(r.value, prev);
            });
        });

        $('previewClose').addEventListener('click', closePreview);
        $('btnPreviewBack').addEventListener('click', closePreview);
        $('btnTalk').addEventListener('click', function () {
            var did = state.previewDid;
            if (!did) { toast('请先打开一路预览', 'err'); return; }
            talkStart(did);
        });
        $('btnGotoPreset').addEventListener('click', function () {
            var did = state.previewDid;
            if (!did) { return; }
            gotoPreset(did);
        });
        /* v1.2.21 多宫格 */
        $('btnGridPreview').addEventListener('click', openGrid);
        $('gridClose').addEventListener('click', closeGrid);
        $('btnGridBack').addEventListener('click', closeGrid);
        $('gridLayout').addEventListener('change', function () {
            if (!$('gridModal').hidden) renderGridPage();
        });
        $('gridTour').addEventListener('change', function () {
            if (!$('gridModal').hidden) setupGridTour(Math.max(1, Math.ceil(grid.dids.length / gridCells())));
        });
        $('gridBody').addEventListener('click', function (e) {
            var tile = e.target.closest('.live-tile');
            if (!tile) return;
            var did = tile.getAttribute('data-griddid');
            if (!did) return;
            // 从宫格进入单路高清预览：关宫格、复用同一条弹层历史记录（避免栈叠两层）
            stopGridOnly();
            switchOverlayKind('preview');
            openPreview(did);
        });
        $('playerClose').addEventListener('click', closePlayer);
        $('btnPlayerBack').addEventListener('click', closePlayer);
        $('previewModal').addEventListener('click', function (e) { if (e.target === this) closePreview(); });
        $('playerModal').addEventListener('click', function (e) { if (e.target === this) closePlayer(); });
        $('btnCopyRtsp').addEventListener('click', function () { copyText($('previewRtsp').textContent); });
        // v1.2.3：外网可播链接 + 画面模式切换 + 移动 WebView 起播兜底
        $('btnGenLiveLink').addEventListener('click', function () { genLiveShare(false); });
        $('btnCopyLiveLink').addEventListener('click', copyLiveShare);
        $('btnRevokeLiveLink').addEventListener('click', revokeLiveShare);
        $('previewMode').addEventListener('change', function () {
            cancelPtzCompat(); // 用户手动选模式后，不再自动回切，避免抢占用户选择
            state.forceCompat = false;
            var did = state.previewDid;
            if (!did) { return; }
            var c = findDevice(did);
            if (c) { applyPreviewPlayback(did, withToken(c.hlsUrl)); }
        });
        $('previewModal').addEventListener('pointerdown', function () {
            var v = $('previewVideo');
            if (v && !v.hidden && v.paused) { v.play().catch(function () {}); }
        }, { passive: true });

        var pv = $('previewVideo');
        pv.addEventListener('playing', function () { $('previewLoading').hidden = true; });
        pv.addEventListener('loadeddata', function () { $('previewLoading').hidden = true; });
        // v2.3.1 第十六轮热修：预览原生控件默认关闭，仅在「精确指针 + 无触摸能力」的
        // 桌面环境开启。此前依赖 pointer:coarse 媒体查询做开关，部分手机内核（X5/QQ
        // 浏览器旧版）不识别该查询 → 原生控制条仍在 → 点一下视频即弹控制条，误触全屏
        // 键后走 <video> 元素级原生全屏，应用的全屏浮层在 DOM 里不可见，表现为
        // 「随便点一下就全屏 + 全屏后没有操作的地方」。controls 已从 HTML 属性移除，
        // 这里反向开关；style.css 再按 coarse 指针 CSS 级兜底隐藏 webkit 媒体控件。
        var coarsePtr = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
        var hasTouch = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0);
        pv.controls = !coarsePtr && !hasTouch;
        // v2.3.1 第十八轮：禁画中画 + 禁长按菜单，进一步堵住手机内核的接管入口
        try { pv.disablePictureInPicture = true; } catch (e4) { /* 旧内核无此属性 */ }
        try { pv.setAttribute('controlslist', 'nodownload noplaybackrate nofullscreen'); } catch (e5) { /* 忽略 */ }
    }

    /* ---------- 初始化 ---------- */
    async function init() {
        bind();
        // 页脚版本号在锁屏页也要显示，故放在全局初始化里，而不是解锁后的 showApp()
        loadAbout();
        // 带 #view=... 重新加载（分享链接 / bfcache 恢复）时不自动重开取流，只清标记
        clearOverlayHash();
        // 一键解锁链接回跳：/?access_token=xxx，落盘令牌后清理地址栏（不依赖 Cookie，
        // iframe 中同样有效）
        var qs = new URLSearchParams(location.search);
        var accessToken = qs.get('access_token');
        if (accessToken) {
            setToken(accessToken);
            if (qs.get('remember')) { setRemember(qs.get('remember')); }
            history.replaceState(null, '', location.pathname);
        }
        await refreshGate();
    }
    init();
})();
