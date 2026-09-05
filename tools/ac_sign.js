// 本地验证抖音反爬挑战：在沙箱里跑挑战页的 acrawler JS，产出 __ac_signature
// 用法: node ac_sign.js <challenge.html> <url> [__ac_nonce]
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
const targetUrl = process.argv[3] || 'https://www.douyin.com/';
const blocks = [];
{
  let p = -1;
  while ((p = html.indexOf('<script>', p + 1)) >= 0) {
    const e = html.indexOf('</script>', p);
    if (e < 0) break;
    blocks.push(html.slice(p + 8, e));
    p = e;
  }
}
if (!blocks.length) { console.error('NO_SCRIPT'); process.exit(1); }

const cookies = [];
function setCookie(v) {
  const kv = String(v).split(';')[0];
  const name = (kv.split('=')[0] || '').trim();
  if (!name) return;
  const past = /expires=Thu, 01 Jan 1970|expires=Mon, 20 Sep 1970/i.test(String(v));
  const val = kv.split('=').slice(1).join('=');
  const i = cookies.findIndex(c => c.startsWith(name + '='));
  if (past || val === '') { if (i >= 0) cookies.splice(i, 1); return; }
  const pair = name + '=' + val;
  if (i >= 0) cookies[i] = pair; else cookies.push(pair);
}
const nonceArg = process.argv[4];
if (nonceArg) cookies.push('__ac_nonce=' + nonceArg);

const sb = function () {}; // 占位
const win = {};
win.window = win;
Object.defineProperty(win, 'document', { value: {} });
const doc = win.document;
Object.defineProperty(doc, 'cookie', { get: () => cookies.join('; '), set: (v) => setCookie(v) });
doc.referrer = '';
win.location = { protocol: 'https:', href: targetUrl, search: '', hostname: 'www.douyin.com', reload: () => {} };
win.navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36' };
win.sessionStorage = (() => { const s = {}; return { setItem: (k, v) => s[k] = v, getItem: k => s[k] || null }; })();
win.localStorage = { setItem: () => {}, getItem: () => null };
win.performance = { timing: { navigationStart: Date.now() } };
win.setTimeout = setTimeout; win.clearTimeout = clearTimeout; win.Math = Math; win.Date = Date;
win.Reflect = Reflect; win.Proxy = Proxy; win.Function = Function; win.Object = Object;
win.String = String; window = win;
global.window = win;
global.document = doc;
global.location = win.location;
global.navigator = win.navigator;
global.sessionStorage = win.sessionStorage;
global.localStorage = win.localStorage;
global.performance = win.performance;

for (const script of blocks) {
  try {
    eval(script);
  } catch (e) {
    console.error('EVAL_ERR: ' + e.message);
    process.exit(2);
  }
}
console.log('COOKIES: ' + cookies.join('; '));
