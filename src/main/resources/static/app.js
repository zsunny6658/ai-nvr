/* aiNVR Web 单页逻辑：登录分步交互、设备、HLS 预览、录像管理 */
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    var state = {
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
        recCollapsed: new Set(), recCounts: {},
        // v1.2.8：事件告警页（选中集 / 已加载行 / 分页游标）
        evSelected: new Set(), evRows: [], evOffset: 0, evTotal: 0, evBusy: false,
        // v1.2.15：全量事件数 / 删除或清空操作的重入保护
        evAllTotal: 0, evDeleting: false,
        // v1.2.13：事件按摄像头分组（折叠状态 / 各设备事件数，来自 /api/events/stats）
        evCollapsed: new Set(), evCounts: {},
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
            motionMs: [],
            liveTimer: null, liveBusy: false,
            loop: 'off' // v2.1.0 循环播放：off | seg（单段）| day（当天）
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
    var TOKEN_KEY = 'invr_access_token';
    function getToken() {
        try { return localStorage.getItem(TOKEN_KEY) || state.memToken || ''; }
        catch (e) { return state.memToken || ''; }
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
       避免预览页按返回后又要重新输入访问密码。主动「锁定」即清除，等价于用户主动退出。 */
    var REMEMBER_KEY = 'invr_access_remember';
    function getRemember() {
        try { var v = localStorage.getItem(REMEMBER_KEY); if (v) return v; }
        catch (e) { /* 分区存储不可读时继续试 Cookie */ }
        // 兜底：部分 WebView / 跨站 iframe 会重建 localStorage 分区，而同源 Cookie 仍在
        try {
            var m = document.cookie.match(/(?:^|;\s*)invr_access_remember=([^;]*)/);
            return m ? decodeURIComponent(m[1]) : '';
        } catch (e) { return ''; }
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
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + '__invr_t=' + encodeURIComponent(t);
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
        var tk = getToken();
        if (tk && !opts.headers['Authorization']) {
            opts.headers['Authorization'] = 'Bearer ' + tk;
        }
        var res = await fetch(path, opts);
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
                throw err;
            }
            return data;
        }
        if (!res.ok) {
            if (res.status === 401 && !opts.noRelock) { scheduleGateRelock(); }
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
            if (location.hash.indexOf(OVERLAY_HASH) === 0 && history.state && history.state.invrOverlay) {
                // 异常路径遗留的同名条目：直接覆盖，避免历史栈无限增长
                history.replaceState({ invrOverlay: kind }, '', url);
            } else {
                history.pushState({ invrOverlay: kind }, '', url);
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
            history.replaceState({ invrOverlay: kind }, '', overlayBaseUrl() + OVERLAY_HASH + '=' + kind);
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
        }
    }
    window.addEventListener('popstate', onOverlayHistoryBack);
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
        stopPoll();
        if (!$('gridModal').hidden) stopGridOnly();
        if (!$('previewModal').hidden) stopPreviewOnly();
        if (!$('playerModal').hidden) stopPlayerOnly();
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
        $('userChip').textContent = '已登录 · ' + userId + '（' + region + '）';
        resetLoginPanels();
        loadDevices(true);
        loadRecordings();
        loadBoards();
        loadDashboard(); // 内含卡片最近快照刷新（v1.2.14）
        loadAbout();
        state.pollTimer = setInterval(function () { loadDevices(false); }, 5000);
        // 概览含录像目录扫描，30 秒一次即可，不跟着 5 秒设备轮询走
        state.dashTimer = setInterval(loadDashboard, 30000);
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
        finally { $('btnUnlock').disabled = false; }
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
            var dev = d.devices || {}, ev = d.events || {}, rec = d.recordings || {},
                st = d.streams || {}, mo = d.motion || {};
            statSet('dsDevTotal', 'dsDevSub', dev.total == null ? '-' : dev.total,
                (dev.recording || 0) + ' 路录像中');
            statSet('dsOnline', 'dsOnlineSub', dev.online == null ? '-' : dev.online,
                (dev.offline || 0) + ' 台离线', dev.offline ? 'warn' : 'ok');
            statSet('dsToday', 'dsTodaySub', ev.today == null ? 0 : ev.today,
                mo.enabled ? '运动侦测已开启' : '运动侦测未开启');
            statSet('dsUnhandled', 'dsUnhandledSub', ev.unhandled == null ? 0 : ev.unhandled,
                '累计 ' + (ev.total || 0) + ' 条', ev.unhandled ? 'warn' : 'ok');
            statSet('dsRecFiles', 'dsRecSub', rec.files == null ? 0 : rec.files,
                fmtSize(rec.bytes));
            statSet('dsStreams', 'dsStreamsSub', st.activeUpstreams == null ? 0 : st.activeUpstreams,
                (st.activeFrameSources || 0) + ' 路兼容帧');
        } catch (e) {
            // 概览失败不打断主流程（门禁失效由 api() 统一处理）
        }
        loadCamEvents(); // 与概览同频（30 秒）刷新卡片快照，内部有签名判重
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
            var list = data.devices || [];
            var structureChanged = list.length !== state.devices.length ||
                list.some(function (c) {
                    var old = state.devices.find(function (o) { return String(o.did) === String(c.did); });
                    return !old || old.name !== c.name || old.model !== c.model || old.localIp !== c.localIp;
                });
            state.devices = list;
            renderRecFilter();
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

    function statusBadges(c) {
        if (c.previewing) return '<span class="badge live">预览中</span>' +
            (c.recording ? '<span class="badge rec">录像中</span>' : '');
        if (c.recording) return '<span class="badge rec">录像中</span>';
        return '<span class="badge off">空闲</span>';
    }

    function onlineBadge(c) {
        return '<span class="net ' + (c.online ? 'online' : 'offline') + '"><span class="dot"></span>'
            + (c.online ? '在线' : '离线') + '</span>';
    }

    function cardHtml(c) {
        var offlineDis = c.online ? '' : ' disabled title="设备离线，暂不可用"';
        // 录像中的“停止录像”始终可用（本地操作）；离线时仅禁用“开始录像”。
        var recBtn = c.recording
            ? '<button class="btn rec-on" data-act="recstop" data-did="' + esc(c.did) + '">停止录像</button>'
            : '<button class="btn" data-act="recstart" data-did="' + esc(c.did) + '"' + offlineDis + '>开始录像</button>';
        return '<div class="cam-card' + (c.online ? '' : ' offline') + '" data-did="' + esc(c.did) + '">' +
            '<div class="cam-top"><div>' +
            '<div class="cam-name"><span class="nm">' + esc(c.name) + '</span>' + onlineBadge(c) + '</div>' +
            '<div class="cam-model">' + esc(c.model) + '</div>' +
            '<div class="cam-ip">' + esc(c.localIp || '未知IP') + '</div>' +
            '</div><div class="badges">' + statusBadges(c) + '</div></div>' +
            '<div class="cam-actions">' +
            '<button class="btn primary" data-act="preview" data-did="' + esc(c.did) + '"' + offlineDis + '>实时预览</button>' +
            recBtn +
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

    function renderDevices() {
        var grid = $('deviceGrid');
        $('camCount').textContent = state.devices.length + ' 台摄像头';
        grid.dataset.count = String(state.devices.length);
        if (!state.devices.length) {
            grid.innerHTML = '<div class="empty">账号下未发现摄像头</div>';
            return;
        }
        grid.innerHTML = state.devices.map(cardHtml).join('');
        renderCamEvents(); // 卡片重建后用缓存快照重绘，不必重新请求
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
            var list = state.camRecent[did] || [];
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

    /** 「查看全部」：切到事件告警页并按该摄像头筛选（用 pending 避免与 tab 自带加载打架）。 */
    function gotoCamEvents(did) {
        state.evPendingDid = String(did);
        var tab = document.querySelector('.tab[data-tab="events"]');
        if (tab) tab.click();
    }

    /** 轮询只更新状态徽标、在线态与录像按钮，不重建卡片（避免按钮闪烁 / 点击中断）。 */
    function updateDeviceStates() {
        var grid = $('deviceGrid');
        state.devices.forEach(function (c) {
            var card = grid.querySelector('.cam-card[data-did="' + CSS.escape(c.did) + '"]');
            if (!card) { renderDevices(); return; }
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
        var net = card.querySelector('.net');
        if (net) {
            net.className = 'net ' + (c.online ? 'online' : 'offline');
            net.innerHTML = '<span class="dot"></span>' + (c.online ? '在线' : '离线');
        }
        var pb = card.querySelector('button[data-act="preview"]');
        if (pb) {
            pb.disabled = !c.online;
            pb.title = c.online ? '' : '设备离线，暂不可用';
        }
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
            loadDevices(false); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }
    async function stopRecord(did) {
        try {
            var r = await api('api/record/' + did + '/stop', { method: 'POST' });
            toast('已停止，本段录像已保存（共 ' + (r.segments || 0) + ' 段）', 'ok');
            loadDevices(false); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }

    async function loadRecordings() {
        var filter = $('recFilter').value || '';
        var url = 'api/recordings' + (filter ? '?did=' + encodeURIComponent(filter) : '');
        try {
            // 确保设备名已加载，录像列表才能显示“名称（DID）”而非裸 DID
            if (!state.devices.length) { try { await loadDevices(false); } catch (e2) { /* 忽略，退回显示 DID */ } }
            var list = await api(url);
            state.recRows = new Map((list || []).map(function (r) {
                return [r.name, { size: r.size || 0, recording: !!r.recording }];
            }));
            // 刷新后剔除已被删除 / 已不在当前筛选结果里的勾选，避免误删看不见的文件
            Array.from(state.recSelected).forEach(function (n) {
                if (!state.recRows.has(n)) state.recSelected.delete(n);
            });
            renderRecordings(list);
        } catch (e) {
            $('recList').innerHTML = '<tr><td colspan="6" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
            state.recRows = new Map();
            updateBatchUi();
        }
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
            '<button class="btn tiny soft-danger" data-ract="del" data-name="' + esc(r.name) + '">删除</button>' +
            '</td></tr>';
    }

    /** v1.2.13 录像按摄像头分组：每组一个可折叠标题行（设备名 / 文件数 / 占用空间）。 */
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
        tb.innerHTML = keys.map(function (did) {
            var rows = groups.get(did);
            var collapsed = state.recCollapsed.has(did);
            var bytes = rows.reduce(function (s, r) { return s + (r.size || 0); }, 0);
            var head = '<tr class="rec-group" data-recgroup="' + esc(did) + '"><td colspan="6">' +
                '<button type="button" class="cam-group-toggle" aria-expanded="' + (!collapsed) + '">' +
                    '<span class="cam-caret">' + (collapsed ? '▸' : '▾') + '</span>' +
                    '<span class="cam-group-name">' + esc(cameraName(did)) + '</span>' +
                    '<span class="cam-group-did">' + esc(did) + '</span>' +
                    '<span class="cam-group-count">' + rows.length + ' 个文件 · ' + fmtSize(bytes) + '</span>' +
                '</button></td></tr>';
            return head + rows.map(function (r) { return recRowHtml(r, collapsed); }).join('');
        }).join('');
        tb.querySelectorAll('button[data-ract]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var name = btn.getAttribute('data-name');
                var act = btn.getAttribute('data-ract');
                if (act === 'play') openPlayer(name);
                else if (act === 'del') delRecording(name);
            });
        });
        tb.querySelectorAll('.rec-group').forEach(function (tr) {
            tr.querySelector('.cam-group-toggle').addEventListener('click', function () {
                var did = tr.getAttribute('data-recgroup');
                var nowCollapsed = !state.recCollapsed.has(did);
                if (nowCollapsed) state.recCollapsed.add(did); else state.recCollapsed.delete(did);
                this.querySelector('.cam-caret').textContent = nowCollapsed ? '▸' : '▾';
                this.setAttribute('aria-expanded', String(!nowCollapsed));
                tb.querySelectorAll('tr[data-recgroup-row]').forEach(function (row) {
                    if (row.getAttribute('data-recgroup-row') === did) {
                        row.classList.toggle('is-collapsed', nowCollapsed);
                    }
                });
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
            await loadRecordings();
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
            var count = parseInt(res.headers.get('X-aiNVR-Zip-Files') || res.headers.get('X-Invr-Zip-Files') || names.length, 10);
            var skipped = parseInt(res.headers.get('X-aiNVR-Zip-Skipped') || res.headers.get('X-Invr-Zip-Skipped') || '0', 10);
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
            toast('已删除', 'ok'); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }

    /* ---------- 系统设置（存储路径 / 分段 / 端口 / 目录选择） ---------- */
    var FOLDER_SVG = '<svg class="dir-ico" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
        '<path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>';

    function setKv(k, v) {
        return '<div><span>' + esc(k) + '</span><b>' + esc(v) + '</b></div>';
    }

    async function loadSettings() {
        try {
            var s = await api('api/settings');
            state.settings = s;
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
            // 分段时长
            $('setSegmentInput').value = s.segmentSec;
            $('setSegmentHint').textContent = (s.segmentCustomized ? '当前为自定义值，默认 ' + s.defaultSegmentSec + ' 秒。' : '当前为默认值。')
                + '保存后正在录制的分段会封段并按新时长续录。';
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
        box.innerHTML = list.map(function (d) {
            var reach = d.lanReachable === true ? '<span class="q-state ok">局域网可达</span>'
                : d.lanReachable === false ? '<span class="q-state bad">局域网不可达</span>'
                    : '<span class="q-state">探测中</span>';
            var stream = d.streamState === '有画面' ? '<span class="q-state ok">有画面</span>'
                : '<span class="q-state">' + esc(d.streamState || '未取流') + '</span>';
            var sel = '<select data-q-did="' + esc(d.did) + '">' + opts.map(function (o) {
                return '<option value="' + esc(o.value) + '"' + (o.value === d.quality ? ' selected' : '')
                    + '>' + esc(o.label) + '</option>';
            }).join('') + '</select>';
            return '<div class="q-row"><div>' +
                '<div class="q-name">' + esc(d.name || d.did) + reach + stream + '</div>' +
                '<div class="q-meta">' + esc(d.did) + ' · ' + esc(d.model)
                + ' · 型号默认档位 ' + esc(d.modelDefault) + '</div>' +
                '</div><div class="set-edit">' + sel +
                '<button class="btn primary" type="button" data-q-save="' + esc(d.did) + '">应用</button>' +
                '</div></div>';
        }).join('');
    }

    async function saveQuality(did, value) {
        try {
            var s = await api('api/settings/quality', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ did: did, quality: value })
            });
            state.settings = s;
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

    /* ---------- 服务器目录选择弹窗 ---------- */
    var dirData = { current: null, parent: null, roots: [], entries: [] };
    var dirPicked = null;

    function openDirModal() {
        dirPicked = null;
        $('dirConfirm').disabled = true;
        $('dirSelected').textContent = '未选择';
        $('dirModal').hidden = false;
        // 从当前存储目录开始浏览，缺省从盘符/根开始
        var start = (state.settings && state.settings.recordingDir) ? state.settings.recordingDir : '';
        loadDir(start);
    }

    function closeDirModal() {
        $('dirModal').hidden = true;
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
        $('setDirInput').value = dirPicked;
        $('btnSaveDir').disabled = false;
        closeDirModal();
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
                if (segCount(txt) >= 3 || tries >= 40) { finish(); }
                else { setTimeout(once, 300); }
            }).catch(function () { if (tries >= 40) { finish(); } else { setTimeout(once, 300); } });
        }
        once();
    }

    /* Chromium 内核（Edge/Chrome/内置浏览器）：其原生 MediaFoundation HLS 在长期运行遇停滞时
       会产生缓冲空洞且无法自愈，故优先 hls.js(MSE)；Safari 等保留原生优先。 */
    function isChromium() {
        return /Chrome\//.test(navigator.userAgent);
    }

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
            enableWorker: true
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

    /* 按浏览器能力选择路径并挂载：hls.js 优先（Chromium），原生降级；返回 hls 实例 / null（原生）/ undefined（不支持） */
    function mountPreviewPlayer(video, url) {
        if (window.Hls && Hls.isSupported() && isChromium()) {
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
        stopPreviewOnly();
        leaveOverlayHistory();
        loadDevices(false);
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
        var body = {
            enabled: $('setMotionEnabled').checked,
            autoRecord: $('setAutoRecord').checked,
            threshold: Number($('setMotionThreshold').value),
            recordTailSec: Number($('setRecordTail').value),
            scheduleEnabled: $('setMotionSchedule').checked,
            activeStartMin: hhmmToMinute($('setMotionActiveStart').value),
            activeEndMin: hhmmToMinute($('setMotionActiveEnd').value),
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
    function minuteToHHMM(min) {
        if (min == null || min < 0) return '';
        var h = Math.floor(min / 60), m = min % 60;
        return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
    }

    function hhmmToMinute(v) {
        if (!v) return -1;
        var p = v.split(':');
        var n = (Number(p[0]) || 0) * 60 + (Number(p[1]) || 0);
        return (n >= 0 && n <= 1439) ? n : -1;
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
        $('playerTitle').textContent = '录像回放 · ' + name;
        $('playerModal').hidden = false;
        var v = $('playerVideo');
        v.src = withToken('api/recordings/' + encodeURIComponent(name));
        v.play().catch(function () {});
        enterOverlayHistory('player');
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
        if (!pb.inited) { pb.inited = true; loadTimeline(); }
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
        var el = (typeof $ === 'function') ? $('pbVideo') : null;
        if (el && !el.paused) { el.pause(); }
    }

    async function loadTimeline() {
        var pb = state.pb;
        pb.did = $('pbCam').value || pb.did;
        pb.dateStr = $('pbDate').value || localDateStr(new Date());
        if (!pb.did) { $('tlBlocks').innerHTML = '<div class="tl-no">请先选择摄像头</div>'; return; }
        pb.dayStart = new Date(pb.dateStr + 'T00:00:00').getTime();
        pb.idx = -1; pb.curStart = 0;
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
                    var pv0 = $('pbVideo');
                    pv0.pause(); pv0.removeAttribute('src'); pv0.load();
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
        var first = Math.ceil(pb.winStart / tickMs) * tickMs;
        var html = '';
        for (var t = first; t < winEnd; t += tickMs) {
            var nth = Math.round(t / tickMs);
            var major = (nth % steps.every === 0);
            html += '<div class="tl-tick' + (major ? ' major' : '') + '" style="left:' + pct(t).toFixed(3) + '%">'
                + (major ? '<span class="tl-lab">' + hms(t) + '</span>' : '') + '</div>';
        }
        ruler.innerHTML = html;

        var blocks = $('tlBlocks');
        if (!pb.segs.length) {
            blocks.innerHTML = '<div class="tl-no">该摄像头当天无录像</div>';
        } else {
            blocks.innerHTML = pb.segs.map(function (s, i) {
                var l = Math.max(0, pct(s.startMs));
                var r = Math.min(100, pct(s.startMs + s.durMs));
                var w = Math.max(0.4, r - l);
                var cls = 'tl-block' + (s.recording ? ' rec' : '') + (i === pb.idx ? ' cur' : '');
                var title = hms(s.startMs) + ' ~ ' + hms(s.startMs + s.durMs) + (s.recording ? '（录制中）' : '');
                return '<div class="' + cls + '" data-i="' + i + '" style="left:' + l.toFixed(3) + '%;width:' + w.toFixed(3) + '%" title="' + esc(title) + '"></div>';
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
        var v = $('pbVideo');
        if (pb.idx < 0 || !v.src) { cur.hidden = true; return; }
        var x = pct(pb.curStart + (v.currentTime || 0) * 1000);
        if (x < 0 || x > 100) { cur.hidden = true; return; }
        cur.hidden = false;
        cur.style.left = x.toFixed(3) + '%';
    }

    function updateMeta() {
        var pb = state.pb;
        var v = $('pbVideo');
        if (pb.idx >= 0 && v.src) {
            $('pbNow').textContent = hms(pb.curStart + (v.currentTime || 0) * 1000) + ' 播放中';
            var s = pb.segs[pb.idx];
            $('pbSeg').textContent = '分段 ' + (pb.idx + 1) + '/' + pb.segs.length + ' · ' + hms(s.startMs)
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
        pb.idx = i;
        var s = pb.segs[i];
        pb.curStart = s.startMs;
        pb.pendingOffset = offsetSec || 0;
        var v = $('pbVideo');
        $('pbEmpty').hidden = true;
        v.src = withToken('api/recordings/' + encodeURIComponent(s.name));
        v.play().catch(function () {});
        if (s.recording) {
            toast('该段正在录制，播放已写入的部分（到当前进度为止）；看实时画面请用“实时预览”', '');
        }
        renderTimeline();
    }

    function pbNextSeg() {
        var pb = state.pb;
        // v2.1.0 循环播放：单段=重放当前段；当天=最后一段后回到第一段
        if (pb.loop === 'seg' && pb.idx >= 0) {
            loadSeg(pb.idx, 0);
            return;
        }
        if (pb.idx + 1 < pb.segs.length) {
            loadSeg(pb.idx + 1, 0);
            if (pb.zoom !== 24) { computeWin(true); renderTimeline(); }
        } else if (pb.loop === 'day' && pb.segs.length > 0) {
            loadSeg(0, 0);
            if (pb.zoom !== 24) { computeWin(true); renderTimeline(); }
        } else {
            toast('已到当天最后一段', 'ok');
            updateMeta();
        }
    }

    function autoFollow() {
        var pb = state.pb;
        if (pb.zoom === 24 || pb.idx < 0) return;
        var ms = pb.curStart + ($('pbVideo').currentTime || 0) * 1000;
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
        $('btnRecRefresh').addEventListener('click', loadRecordings);
        $('recFilter').addEventListener('change', function () {
            // 换筛选条件后清空勾选，避免删到当前列表里看不见的文件
            state.recSelected.clear();
            loadRecordings();
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
            loadEvents(true);
        });
        ['evFilterDid', 'evFilterType', 'evFilterHandled', 'evFilterDate'].forEach(function (id) {
            $(id).addEventListener('change', function () { loadEvents(true); });
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
        $('btnBrowseDir').addEventListener('click', openDirModal);
        $('btnSaveDir').addEventListener('click', function () { saveSettings(false); });
        $('btnResetDir').addEventListener('click', function () {
            if (confirm('确认恢复为默认存储目录？\n当前正在录制的分段会在封段后写入默认目录。')) saveSettings(true);
        });
        $('btnRefreshDir').addEventListener('click', loadSettings);
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
            var b = e.target.closest('button[data-q-save]');
            if (!b) return;
            var did = b.getAttribute('data-q-save');
            var sel = $('setQualityList').querySelector('select[data-q-did="' + CSS.escape(did) + '"]');
            if (sel) saveQuality(did, sel.value);
        });
        $('ptzPad').addEventListener('click', function (e) {
            var b = e.target.closest('button[data-dir]');
            if (b) ptzMove(b.getAttribute('data-dir'));
        });
        $('btnChaseLive').addEventListener('click', function () {
            if (!chaseLiveEdge(false)) { toast('当前已是最新画面', 'ok'); }
            updateLiveBadge();
        });
        $('previewQuality').addEventListener('change', function () {
            var did = state.previewDid;
            if (!did) return;
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
        (function () {
            var pv = $('pbVideo');
            /** v2.0.3 回放倍速：>4 倍时自动静音（音频快放无意义且部分浏览器会丢帧）。 */
            function applyPbRate() {
                var r = parseFloat($('pbSpeed').value) || 1;
                pv.playbackRate = r;
                pv.muted = r > 4;
            }
            pv.addEventListener('loadedmetadata', function () {
                var pb = state.pb;
                if (pb.pendingOffset != null) {
                    pv.currentTime = Math.min(pb.pendingOffset, Math.max(0, (pv.duration || 0) - 0.05));
                    pb.pendingOffset = null;
                }
                applyPbRate(); // 换段（含自动接续）会重置倍率，每次元数据就绪后重新应用
            });
            pv.addEventListener('timeupdate', function () { updateCursor(); updateMeta(); autoFollow(); });
            pv.addEventListener('ended', pbNextSeg);
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
                var v2 = $('pbVideo');
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
        var btn = document.querySelector('.tab[data-tab="' + name + '"]');
        if (btn) btn.classList.add('active');
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
            loadEvents(true);
        }
        if (name === 'playback') {
            enterPlayback();
            // v1.2.19：事件页「看回放」带来的定位待办（摄像头 + 日期 + 时刻）
            applyPbPending();
            setPbPane(state.pbPane || 'timeline');
        } else {
            pausePb(); stopPbLive();
        }
        if (name === 'settings') { loadSettings(); loadBoards(); }
    }

    /** 回放页二级窗格：timeline=时间轴回放（原录像回放内容），files=录像文件列表。 */
    function setPbPane(pane) {
        state.pbPane = pane;
        $('pbTimelinePane').hidden = pane !== 'timeline';
        $('tab-recordings').hidden = pane !== 'files';
        $('pbPaneTimeline').classList.toggle('active', pane === 'timeline');
        $('pbPaneFiles').classList.toggle('active', pane === 'files');
        if (pane === 'files') {
            pausePb(); stopPbLive();
            loadRecordings();
        }
    }

    document.querySelectorAll('.tab').forEach(function (t) {
            t.addEventListener('click', function () { gotoTab(t.getAttribute('data-tab')); });
        });

        // v2.0.1 回放页内二级切换：时间轴回放 / 录像文件
        $('pbPaneTimeline').addEventListener('click', function () { setPbPane('timeline'); });
        $('pbPaneFiles').addEventListener('click', function () { setPbPane('files'); });

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
            else if (act === 'probeclose') {
                var box = b.closest('.cam-card').querySelector('.probe-box');
                if (box) { box.remove(); }
            }
            else if (act === 'evsnap') openEvSnapshot(Number(b.getAttribute('data-evid')));
            else if (act === 'evmore') gotoCamEvents(did);
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
