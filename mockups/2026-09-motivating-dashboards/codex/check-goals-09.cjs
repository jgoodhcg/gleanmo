// Standalone mockup checks. External requests are blocked; no app or database is used.
const {chromium}=require('../../../e2e/node_modules/playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
 const page=await browser.newPage({viewport:{width:1440,height:900}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route(/^https?:/,route=>route.abort());
 await page.goto(pathToFileURL(path.join(__dirname,'mockup-codex-09-goals-dashboard.html')).href);
 const shot=name=>page.screenshot({path:path.join(__dirname,`review-shots/goals-09-${name}.png`),fullPage:true});
 assert.equal(await page.locator('#rows tr').count(),15);
 for(const g of await page.evaluate(()=>goals.map(g=>({id:g.id,total:g.total,actual:accumulated(g,g.timing==='weekly'?g.daily.slice(68):g.daily).at(-1),ghosts:g.ghosts.map(h=>({total:h.total,actual:accumulated(g,h.daily).at(-1)}))})))){
 assert.ok(Math.abs(g.total-g.actual)<.0001,g.id+' series reconciles');
 for(const h of g.ghosts)assert.ok(Math.abs(h.total-h.actual)<.0001,g.id+' ghost reconciles');
 }
 for(const id of await page.evaluate(()=>goals.map(g=>g.id))){
 await page.locator(`tr[data-select="${id}"] .goal-name`).click();
 assert.equal(await page.locator('#history .history-row').getAttribute('data-selected-goal'),id);
 assert.ok(!/NaN|Infinity|undefined/.test(await page.locator('#detail').innerText()));
 assert.ok(!/NaN|Infinity/.test(await page.locator('#chart').innerHTML()));
 await page.evaluate(()=>scrollTo(0,0));const box=await page.locator('#chart').boundingBox();if(box.y+box.height>900)await shot('debug');assert.ok(box.y+box.height<=900,id+' chart fits desktop '+JSON.stringify(box));
 }
 await page.locator('[data-timing="open"]').click();assert.equal(await page.locator('#rows tr').count(),2);
 for(const id of ['meditation','readingbook']){
 await page.locator(`tr[data-select="${id}"] .goal-name`).click();
 assert.ok(await page.locator('#ghost-controls').isHidden());
 assert.equal(await page.locator('#chart [data-ghost-year]').count(),0);
 assert.ok((await page.locator('#legend').innerText()).includes('Target'));
 assert.ok(!/Even pace|Required from today/.test(await page.locator('#legend').innerText()));
 assert.ok((await page.locator('#hero').innerText()).includes('since July 1'));
 assert.ok((await page.locator('#stats').innerText()).includes('Still to go'));
 assert.equal(await page.locator(`tr[data-select="${id}"] .tick`).count(),0);
 assert.equal((await page.locator(`tr[data-select="${id}"] td`).nth(3).innerText()).trim(),'—');
 assert.ok((await page.locator('#ghost-note').innerText()).includes('no deadline'));
 const actual=await page.evaluate(()=>{const g=goals.find(g=>g.id===selected);return amount(g,g.daily.slice(-28).reduce((a,b)=>a+b,0))});
 assert.equal(await page.locator('#ghost strong').innerText(),actual);
 await shot(id==='readingbook'?'book':'open');
 }
 await page.locator('[data-filter="count"]').click();assert.equal(await page.locator('#rows tr').count(),0);assert.ok(await page.locator('#detail').isHidden());
 await page.locator('[data-timing="weekly"]').click();assert.equal(await page.locator('#rows tr').count(),3);
 await page.locator('tr[data-select="meditationpractice"] .goal-name').click();
 assert.ok((await page.locator('#hero').innerText()).includes('Loving-kindness'));
 assert.ok((await page.locator('#stats').innerText()).includes('2 days left'));
 await shot('meditation-weekly');
 await page.locator('[data-filter="duration"]').click();assert.equal(await page.locator('#rows tr').count(),1);
 assert.equal(await page.locator('#hero h2').innerText(),'Weekly reading');
 assert.ok((await page.locator('#stats').innerText()).includes('48'));
 await page.locator('[data-year="2024"]').check();await page.locator('[data-year="2023"]').check();
 assert.equal(await page.locator('#chart [data-ghost-year]').count(),3);
 await shot('reading-weekly');
 await page.locator('[data-filter="all"]').click();await page.locator('[data-timing="all"]').click();
 for(const key of ['label','progress','rate','needed','ratio','pace','next']){
 await page.locator(`th[data-key="${key}"] button`).click();const first=await page.locator('#rows tr').evaluateAll(rows=>rows.map(r=>r.dataset.select));
 await page.locator(`th[data-key="${key}"] button`).click();const second=await page.locator('#rows tr').evaluateAll(rows=>rows.map(r=>r.dataset.select));assert.notDeepEqual(first,second);
 }
 await page.locator('#search').fill('Odyssey');assert.equal(await page.locator('#rows tr').count(),1);
 await page.locator('#search').fill('no matching goal');assert.ok(await page.locator('#empty').isVisible());
 await page.locator('#search').fill('');await page.locator('tr[data-select="reading"] .goal-name').click();
 assert.equal(await page.locator('#chart [data-ghost-year]').count(),3,'Year choices survive open goal');
 await shot('dated');
 await page.locator('[data-filter="best"]').click();assert.equal(await page.locator('#rows tr').count(),2);
 assert.ok(!(await page.locator('#legend').innerText()).includes('Required from today'));
 await shot('achievement');
 await page.locator('[data-filter="all"]').click();await page.locator('tr[data-select="meditation"] .goal-name').click();
 await page.locator('th[data-key="label"] button').click();
 if(await page.locator('th[data-key="label"]').getAttribute('aria-sort')!=='ascending')await page.locator('th[data-key="label"] button').click();
 await page.locator('.table-scroll').evaluate(el=>el.scrollTop=0);
 await page.locator('.cell[tabindex="0"]').focus();await page.keyboard.press('ArrowLeft');assert.ok((await page.locator('#history-readout').innerText()).includes('Sep 10'));
 await page.locator('#search').focus();await shot('desktop');
 for(const width of [390,768]){await page.setViewportSize({width,height:844});await page.waitForTimeout(200);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await shot(width===390?'mobile':'tablet');}
 assert.deepEqual(errors,[]);
 console.log('PASS: 15 totals and 45 ghost series reconcile; timing/measurement filters, open-ended semantics, recent activity, weekly duration and counts, scoped goals, sorting, search, empty state, year persistence, keyboard history, desktop fit, mobile/tablet overflow.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
