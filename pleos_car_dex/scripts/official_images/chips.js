const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY } });
  const out = {};
  for (const url of process.argv.slice(2)) {
    const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
    const chips = new Set();
    p.on('request', r => { const u = r.url();
      if (/vr360\/.+\/exterior\/.+\/colorchip-exterior\.png/.test(u) || /configurator\/[^/]+\/colorchip\/exterior\/[a-z0-9]+\.png/i.test(u)) chips.add(u); });
    try { await p.goto(url, { waitUntil: 'networkidle', timeout: 60000 }); } catch (e) {}
    for (let i = 0; i < 25; i++) { await p.mouse.wheel(0, 900); await p.waitForTimeout(450); }
    await p.waitForTimeout(1000);
    out[url] = [...chips];
    console.error(url, chips.size);
    await p.close();
  }
  console.log(JSON.stringify(out, null, 1));
  await b.close();
})();
