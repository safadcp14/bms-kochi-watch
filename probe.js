// Probe: does a GitHub Actions runner (datacenter IP) get a real page from BMS?
// Positive control: EVMK, which we know has 17 Dec shows on sale.
// Negative control: PMCE / SCKO, which currently have none.

const { chromium } = require('playwright');

const VENUES = [
  { code: 'PMCE', label: 'Padma (target)', url: 'https://in.bookmyshow.com/cinemas/kochi/padma-cinema-kochi/buytickets/PMCE' },
  { code: 'SCKO', label: 'Sarita (target)', url: 'https://in.bookmyshow.com/cinemas/kochi/sarita-cinema-rgb-laser-4k-3d-atmos-kochi/buytickets/SCKO' },
  { code: 'EVMK', label: 'EVM (POSITIVE CONTROL - has 17 Dec)', url: 'https://in.bookmyshow.com/cinemas/kochi/evm-dolby-cinema-fort-kochi/buytickets/EVMK' },
];

const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36';

const CHALLENGE_MARKERS = [
  'Just a moment',
  'Access Denied',
  'Attention Required',
  'cf-browser-verification',
  'Enable JavaScript and cookies to continue',
  'captcha',
  'unusual traffic',
];

function classify(html, markers) {
  const found = markers.filter(m => html.toLowerCase().includes(m.toLowerCase()));
  return found.length ? found : null;
}

async function rawFetchProbe(url) {
  try {
    const res = await fetch(url, { headers: { 'user-agent': UA, accept: 'text/html' } });
    const html = await res.text();
    return {
      status: res.status,
      bytes: html.length,
      server: res.headers.get('server'),
      markers: classify(html, CHALLENGE_MARKERS),
      hasState: html.includes('__INITIAL_STATE__'),
    };
  } catch (e) {
    return { error: String(e).slice(0, 200) };
  }
}

async function browserProbe(browser, v) {
  const ctx = await browser.newContext({
    userAgent: UA,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    viewport: { width: 1366, height: 900 },
  });
  const page = await ctx.newPage();
  const out = { code: v.code, label: v.label };

  try {
    const resp = await page.goto(v.url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    out.httpStatus = resp ? resp.status() : null;
    out.finalUrl = page.url();

    // give the SPA a moment to hydrate, but don't depend on it
    try {
      await page.waitForFunction(
        () => window.__INITIAL_STATE__?.venueShowtimesNew?.showDates?.length > 0,
        { timeout: 20000 }
      );
    } catch { /* fall through and report what we have */ }

    out.page = await page.evaluate(() => {
      const st = window.__INITIAL_STATE__;
      const vn = st?.venueShowtimesNew;
      return {
        title: document.title,
        hasState: !!st,
        codes: vn?.showDates ? vn.showDates.map(d => d.DateCode) : null,
        dateStrip: vn?.showDates ? vn.showDates.map(d => d.DispDate) : null,
        bodyBytes: document.body.innerHTML.length,
        bodyStart: document.body.innerText.replace(/\s+/g, ' ').slice(0, 160),
      };
    });

    const html = await page.content();
    out.challenge = classify(html, CHALLENGE_MARKERS);
  } catch (e) {
    out.error = String(e).slice(0, 300);
  } finally {
    await ctx.close();
  }
  return out;
}

(async () => {
  const report = { when: new Date().toISOString(), egressIp: null, rawFetch: [], browser: [] };

  try {
    const ip = await fetch('https://api.ipify.org?format=json').then(r => r.json());
    report.egressIp = ip.ip;
  } catch { /* optional */ }

  for (const v of VENUES) report.rawFetch.push({ code: v.code, ...(await rawFetchProbe(v.url)) });

  const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  for (const v of VENUES) report.browser.push(await browserProbe(browser, v));
  await browser.close();

  console.log('\n===== BMS EGRESS PROBE =====');
  console.log(JSON.stringify(report, null, 2));
  console.log('===== END =====\n');
})();
