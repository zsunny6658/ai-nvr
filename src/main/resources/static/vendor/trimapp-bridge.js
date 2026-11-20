/**
 * v2.3.1 @trimjs/web-app SDK 桥接（ESM module 加载到全局）。
 *
 * aiNVR 前端是无构建的普通脚本（app.js 为经典 script），而飞牛官方前端 SDK
 * @trimjs/web-app 只发布 ESM 产物，因此用这个 module 壳把默认导出挂到
 * window.TrimApp 并派发 trimapp:ready 事件；app.js 以「事件 + 兜底超时」的
 * 方式惰性接入，独立浏览器访问时模块加载成功但探测不到宿主，全部能力自动
 * 降级隐藏，行为与旧版完全一致。
 *
 * 版本：@trimjs/web-app 0.6.0（npm，2026-11 核对 dist/index.d.ts 能力清单）。
 */
import TrimApp from './trimapp.esm.js';
window.TrimApp = TrimApp;
window.dispatchEvent(new Event('trimapp:ready'));
