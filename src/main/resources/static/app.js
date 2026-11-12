/* iNvr Web 单页逻辑：登录分步交互、设备、HLS 预览、录像管理 */
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
        // 门禁令牌（内存兜底；主存 localStorage，跨站 iframe 中为分区存储仍可读写）
        memToken: '',
        // 时间轴回放状态
        pb: {
            inited: false, did: null, dateStr: null,
            zoom: 24, winStart: 0, dayStart: 0, winDur: 86400000,
            segs: [], idx: -1, curStart: 0, pendingOffset: null,
            liveTimer: null, liveBusy: false
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
    /** 给无法加自定义头的 URL（<video> 媒体、<a download> 下载）追加令牌查询参数。 */
    function withToken(path) {
        var t = getToken();
        if (!t) { return path; }
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + '__invr_t=' + encodeURIComponent(t);
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
                if (res.status === 401 && data.code === 'ACCESS_LOCKED') { scheduleGateRelock(); }
                throw err;
            }
            return data;
        }
        if (!res.ok) {
            if (res.status === 401) { scheduleGateRelock(); }
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
    function fmtTime(ms) {
        var d = new Date(ms);
        function p(x) { return String(x).padStart(2, '0'); }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
            ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    /* ---------- 视图切换 ---------- */
    function stopPoll() {
        if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
    }
    function hideAll() {
        $('loginView').hidden = true;
        $('setupView').hidden = true;
        $('lockView').hidden = true;
        $('appView').hidden = true;
        $('topRight').hidden = true;
        stopPoll();
        if (!$('previewModal').hidden) stopPreviewOnly();
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
        state.pollTimer = setInterval(function () { loadDevices(false); }, 5000);
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
            var r = await Promise.all([api('api/session'), api('api/access/status')]);
            var s = r[0], a = r[1];
            state.loggedIn = !!s.loggedIn;
            state.userId = s.userId || a.userId || null;
            state.region = s.region || a.region || null;
            state.accessConfigured = !!a.configured;
            state.unlocked = !!a.unlocked;
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
            await api('api/access/setup', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p1 })
            });
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
            await api('api/access/unlock', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p })
            });
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
    function renderRecordings(list) {
        var tb = $('recList');
        if (!list || !list.length) {
            tb.innerHTML = '<tr><td colspan="6" class="empty">暂无录像文件</td></tr>';
            updateBatchUi();
            return;
        }
        tb.innerHTML = list.map(function (r) {
            var sel = state.recSelected.has(r.name);
            // 录制中的分段由后端跳过，前端直接禁用勾选并说明原因，避免选了却没被处理
            var disabled = r.recording ? ' disabled title="该分段正在录制中，封段后可操作"' : '';
            return '<tr' + (sel ? ' class="rec-selected"' : '') + '>' +
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
        }).join('');
        tb.querySelectorAll('button[data-ract]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var name = btn.getAttribute('data-name');
                var act = btn.getAttribute('data-ract');
                if (act === 'play') openPlayer(name);
                else if (act === 'del') delRecording(name);
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
            a.download = 'invr-recordings-' + fileStamp() + '.zip';
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
            var count = parseInt(res.headers.get('X-Invr-Zip-Files') || names.length, 10);
            var skipped = parseInt(res.headers.get('X-Invr-Zip-Skipped') || '0', 10);
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
            state.devices.map(function (c) {
                return '<option value="' + esc(c.did) + '">' + esc(c.name) + ' (' + esc(c.did) + ')</option>';
            }).join('');
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
            // RTSP 端口
            $('setRtspInput').value = s.rtspPort;
            $('setRtspHint').textContent = '当前为' + (s.rtspCustomized ? '自定义' : '默认') + '端口，默认 ' + s.defaultRtspPort
                + '。保存后立即切换，现有 RTSP 播放器会短暂断开，需用新地址重连。';
            // 录像保留天数
            $('setRetentionInput').value = s.retentionDays;
            $('setRetentionHint').textContent = (s.retentionDays > 0)
                ? '当前保留最近 ' + s.retentionDays + ' 天，超期录像每小时自动清理（'
                    + (s.retentionCustomized ? '自定义值' : '默认值') + '，默认 ' + s.defaultRetentionDays + ' 天）。'
                : '当前为永久保存，不自动清理（0 表示永久；默认值为 ' + s.defaultRetentionDays + ' 天）。';
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

    async function loadDir(path) {
        var box = $('dirList');
        box.innerHTML = '<div class="dir-empty">加载中…</div>';
        $('dirStatus').textContent = '';
        try {
            var q = path ? ('?path=' + encodeURIComponent(path)) : '';
            dirData = await api('api/settings/browse' + q);
            renderDir();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); closeDirModal(); return; }
            box.innerHTML = '';
            $('dirStatus').textContent = '无法打开该目录：' + e.message;
        }
    }

    function renderDir() {
        var d = dirData;
        $('dirCurrent').textContent = d.current || '此电脑 / 根目录';
        $('dirUp').disabled = !d.parent;
        $('dirRoots').innerHTML = (d.roots || []).map(function (r) {
            return '<button class="btn tiny dir-drive" type="button" data-path="' + esc(r.path) + '">' + esc(r.name) + '</button>';
        }).join('');
        var box = $('dirList');
        if (!d.entries || !d.entries.length) {
            box.innerHTML = '<div class="dir-empty">（此目录下没有子目录）</div>';
        } else {
            box.innerHTML = d.entries.map(function (e) {
                var cls = e.writable ? 'dir-item' : 'dir-item ro';
                return '<div class="' + cls + '" data-path="' + esc(e.path) + '" data-writable="' + (e.writable ? '1' : '0') + '">' +
                    FOLDER_SVG +
                    '<span class="dir-name">' + esc(e.name) + '</span>' +
                    '<span class="dir-flag">' + (e.writable ? '可写' : '不可写') + '</span>' +
                    '</div>';
            }).join('');
        }
        box.querySelectorAll('.dir-item').forEach(function (el) {
            var p = el.getAttribute('data-path');
            var writable = el.getAttribute('data-writable') === '1';
            el.addEventListener('click', function () {
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
            $('dirStatus').textContent = '该目录无权限列出内容。';
        }
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
                if (window.MediaSource && MediaSource.isTypeSupported && MediaSource.isTypeSupported(mime)) return true;
                if (probe.canPlayType(mime)) return true;
            }
            return false;
        } catch (e) { return false; }
    }

    /* ==================== v1.2.3 兼容画面模式 ====================
       摄像头输出为 fMP4 + H.265，PC Edge/Safari 与部分手机可解；Android WebView / 飞牛 App
       多数缺少 HEVC 解码，hls.js 会直接报 codec 不兼容黑屏。这里改用服务端解帧后的
       JPEG 连续帧（/api/frame/{did}），只需 <img>，任何浏览器/WebView 都能出画面。
       代价：低帧率、无声音、服务端需要软解一路；故仅在自动判定不支持或手动选择时启用。 */
    function previewWantsCompat() {
        var mode = $('previewMode') ? ($('previewMode').value || 'auto') : 'auto';
        if (mode === 'compat') { return true; }
        if (mode === 'hls') { return false; }
        return state.forceCompat || !hevcDecodeSupported();
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

    function startCompatMode(did) {
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
        setPtzStatus('兼容画面模式：以 JPEG 连续帧显示（约 2 帧/秒、无声音），不依赖浏览器 HEVC 解码；云台控制仍可用。');
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
    function stopPreviewOnly() {
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
        video.pause();
        video.removeAttribute('src');
        video.load();
        $('previewHint').hidden = true;
        $('previewLoading').hidden = true;
    }
    function closePreview() {
        stopPreviewOnly();
        loadDevices(false);
    }

    /* ---------- 录像回放 ---------- */
    function openPlayer(name) {
        $('playerTitle').textContent = '录像回放 · ' + name;
        $('playerModal').hidden = false;
        var v = $('playerVideo');
        v.src = withToken('api/recordings/' + encodeURIComponent(name));
        v.play().catch(function () {});
    }
    function closePlayer() {
        $('playerModal').hidden = true;
        var v = $('playerVideo');
        v.pause(); v.removeAttribute('src'); v.load();
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
        updateMeta();
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
        if (pb.idx + 1 < pb.segs.length) {
            loadSeg(pb.idx + 1, 0);
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
        $('btnRefresh').addEventListener('click', function () { loadDevices(true); loadRecordings(); });
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

        // 设置：存储路径 / 分段 / 端口
        $('btnBrowseDir').addEventListener('click', openDirModal);
        $('btnSaveDir').addEventListener('click', function () { saveSettings(false); });
        $('btnResetDir').addEventListener('click', function () {
            if (confirm('确认恢复为默认存储目录？\n当前正在录制的分段会在封段后写入默认目录。')) saveSettings(true);
        });
        $('btnRefreshDir').addEventListener('click', loadSettings);
        $('btnSaveSegment').addEventListener('click', saveSegment);
        $('btnSaveRtsp').addEventListener('click', saveRtsp);
        $('btnSaveRetention').addEventListener('click', saveRetention);
        $('btnSaveHls').addEventListener('click', saveHls);
        $('btnSaveShare').addEventListener('click', saveShare);
        $('btnSavePw').addEventListener('click', changeAccessPassword);

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
            pv.addEventListener('loadedmetadata', function () {
                var pb = state.pb;
                if (pb.pendingOffset != null) {
                    pv.currentTime = Math.min(pb.pendingOffset, Math.max(0, (pv.duration || 0) - 0.05));
                    pb.pendingOffset = null;
                }
            });
            pv.addEventListener('timeupdate', function () { updateCursor(); updateMeta(); autoFollow(); });
            pv.addEventListener('ended', pbNextSeg);
        })();

        document.querySelectorAll('.tab').forEach(function (t) {
            t.addEventListener('click', function () {
                document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });
                t.classList.add('active');
                var name = t.getAttribute('data-tab');
                $('tab-cameras').hidden = name !== 'cameras';
                $('tab-boards').hidden = name !== 'boards';
                $('tab-recordings').hidden = name !== 'recordings';
                $('tab-playback').hidden = name !== 'playback';
                $('tab-settings').hidden = name !== 'settings';
                if (name === 'boards') loadBoards();
                if (name === 'recordings') loadRecordings();
                if (name === 'playback') enterPlayback();
                else { pausePb(); stopPbLive(); }
                if (name === 'settings') loadSettings();
            });
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
        });

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
        $('playerClose').addEventListener('click', closePlayer);
        $('previewModal').addEventListener('click', function (e) { if (e.target === this) closePreview(); });
        $('playerModal').addEventListener('click', function (e) { if (e.target === this) closePlayer(); });
        $('btnCopyRtsp').addEventListener('click', function () { copyText($('previewRtsp').textContent); });
        // v1.2.3：外网可播链接 + 画面模式切换 + 移动 WebView 起播兜底
        $('btnGenLiveLink').addEventListener('click', function () { genLiveShare(false); });
        $('btnCopyLiveLink').addEventListener('click', copyLiveShare);
        $('btnRevokeLiveLink').addEventListener('click', revokeLiveShare);
        $('previewMode').addEventListener('change', function () {
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
        // 一键解锁链接回跳：/?access_token=xxx，落盘令牌后清理地址栏（不依赖 Cookie，
        // iframe 中同样有效）
        var qs = new URLSearchParams(location.search);
        var accessToken = qs.get('access_token');
        if (accessToken) {
            setToken(accessToken);
            history.replaceState(null, '', location.pathname);
        }
        await refreshGate();
    }
    init();
})();
