const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY } });
  const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
  try { await p.goto(process.argv[2], { waitUntil: 'networkidle', timeout: 60000 }); } catch (e) {}
  await p.waitForTimeout(2000);
  const hrefs = await p.$$eval('a[href]', as => as.map(a => a.getAttribute('href')));
  const re = new RegExp(process.argv[3]);
  console.log([...new Set(hrefs.filter(h => h && re.test(h)))].join('\n'));
  await b.close();
})();
