const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY } });
  const out = {};
  for (const slug of process.argv.slice(2)) {
    const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
    const ext = new Set(); const rep = new Set();
    p.on('request', r => { const u = r.url();
      let m = u.match(/^(.*\/configurator\/[^/]+\/trim\/exterior\/.+\/)([a-z0-9]+)_(\d+)\.(png|jpg|webp)/i); if (m) ext.add(m[1] + m[2] + '_|' + m[4]);
      if (/\/vehicles\/represent\//.test(u)) rep.add(u); });
    try { await p.goto(`https://www.kia.com/kr/vehicles/${slug}`, { waitUntil: 'networkidle', timeout: 60000 }); } catch (e) {}
    for (let i = 0; i < 20; i++) { await p.mouse.wheel(0, 900); await p.waitForTimeout(500); }
    await p.waitForTimeout(1500);
    out[slug] = { base: [...ext][0] || null, rep: [...rep].slice(0, 2) };
    console.error(slug, out[slug].base, out[slug].rep.length);
    await p.close();
  }
  console.log(JSON.stringify(out, null, 1));
  await b.close();
})();
