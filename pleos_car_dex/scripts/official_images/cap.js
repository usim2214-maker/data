const { chromium } = require('playwright');
(async () => {
  const url = process.argv[2];
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY } });
  const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
  const seen = new Set();
  p.on('request', r => { const u = r.url(); if (/\.(png|jpe?g|webp)(\?|$)/i.test(u) || /api|json/i.test(u)) seen.add(r.method()+' '+u); });
  try { await p.goto(url, { waitUntil: 'networkidle', timeout: 60000 }); } catch (e) { console.log('ERR', e.message.slice(0,200)); }
  for (let i = 0; i < 25; i++) { await p.mouse.wheel(0, 900); await p.waitForTimeout(600); }
  await p.waitForTimeout(2000);
  [...seen].filter(u => !/favicon|adobedtm|google|facebook|doubleclick/.test(u)).forEach(u => console.log(u));
  await b.close();
})();
