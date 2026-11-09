const { chromium } = require('playwright');
(async () => {
  const slugs = process.argv.slice(2);
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY } });
  const out = {};
  for (const slug of slugs) {
    const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
    const ext = new Set(); const chips = new Set();
    p.on('request', r => { const u = r.url();
      let m = u.match(/^(.*\/contents\/vr360\/[^/]+\/exterior\/.+\/)(\d{3})\.(png|jpg|webp)/); if (m) ext.add(m[1]+'|'+m[2]+'|'+m[3]);
      m = u.match(/\/contents\/vr360\/([^/]+)\/exterior\/([^/]+)\/([^/]+)\/colorchip/); if (m) chips.add(m.slice(1,4).join('|')); });
    try { await p.goto(slug.startsWith('http') ? slug : `https://www.hyundai.com/kr/ko/e/vehicles/${slug}/intro`, { waitUntil: 'networkidle', timeout: 60000 }); } catch (e) {}
    for (let i = 0; i < 25; i++) { await p.mouse.wheel(0, 900); await p.waitForTimeout(600); }
    await p.waitForTimeout(1500);
    const frames = [...ext].map(s => s.split('|'));
    out[slug] = { frames: frames.length ? { base: frames[0][0], max: Math.max(...frames.map(f => +f[1])), ext: frames[0][2] } : null, chips: [...chips] };
    console.error(slug, JSON.stringify(out[slug].frames), out[slug].chips.length);
    await p.close();
  }
  console.log(JSON.stringify(out, null, 1));
  await b.close();
})();
