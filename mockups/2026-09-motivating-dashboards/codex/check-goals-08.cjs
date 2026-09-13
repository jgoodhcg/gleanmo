// Standalone browser checks; no app server or database is used.
const {chromium}=require('../../../e2e/node_modules/playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
 const page=await browser.newPage({viewport:{width:1440,height:900}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('https://**/*',route=>route.abort());
 await page.route('http://**/*',route=>route.abort());
 await page.goto(pathToFileURL(path.join(__dirname,'mockup-codex-08-goals-dashboard.html')).href);
 assert.equal(await page.locator('#rows tr').count(),12);
 const chart=await page.locator('#chart').boundingBox();assert.ok(chart.y+chart.height<=900,'Complete main graph fits desktop viewport');
 const calculations=await page.evaluate(()=>goals.map(g=>({id:g.id,total:g.total,sum:accumulated(g,g.kind==='weekly'?g.daily.slice(68):g.daily).at(-1),prior:g.previous,priorSum:accumulated(g,g.kind==='weekly'?g.prior.slice(68):g.prior).at(-1)})));
 const ghostTotals=await page.evaluate(()=>goals.flatMap(g=>g.ghosts.map(ghost=>({id:g.id,year:ghost.year,total:ghost.total,actual:accumulated(g,ghost.daily).at(-1)}))));
 for(const ghost of ghostTotals)assert.ok(Math.abs(ghost.total-ghost.actual)<.0001,ghost.id+' '+ghost.year+' reconciles');
 for(const g of calculations){assert.ok(Math.abs(g.total-g.sum)<.0001,g.id+' actual series reconciles');assert.ok(Math.abs(g.prior-g.priorSum)<.0001,g.id+' prior series reconciles')}
 for(const key of ['label','progress','rate','needed','ratio','pace','next']){
 await page.locator(`th[data-key="${key}"] button`).click();const first=await page.locator('#rows tr').evaluateAll(rows=>rows.map(r=>r.dataset.select));
 await page.locator(`th[data-key="${key}"] button`).click();const second=await page.locator('#rows tr').evaluateAll(rows=>rows.map(r=>r.dataset.select));
 assert.notDeepEqual(first,second,key+' direction changes row order');
 }
 for(const id of await page.evaluate(()=>goals.map(g=>g.id))){await page.locator(`tr[data-select="${id}"] .goal-name`).click();assert.ok((await page.locator('#hero h2').textContent()).length);assert.equal(await page.locator('#chart path').count()>0,true);assert.equal(await page.locator('#history .history-row').count(),1);assert.equal(await page.locator('#history .history-row').getAttribute('data-selected-goal'),id);assert.deepEqual(await page.locator('#ghost .ghost-row').evaluateAll(rows=>rows.map(r=>r.dataset.selectedGoal)),[id]);assert.equal(await page.locator('#detail').innerText().then(t=>/NaN|Infinity|undefined/.test(t)),false)}
 await page.locator('[data-year="2024"]').check();await page.locator('[data-year="2023"]').check();
 assert.equal(await page.locator('#chart [data-ghost-year]').count(),3);
 assert.equal(await page.locator('#ghost .ghost-row').count(),3);
 const colors=await page.locator('#chart [data-ghost-year]').evaluateAll(paths=>paths.map(p=>p.getAttribute('stroke')));assert.equal(new Set(colors).size,3);
 for(const year of [2025,2024,2023])assert.equal(await page.locator(`#chart [data-ghost-year="${year}"]`).getAttribute('stroke'),await page.locator(`#ghost [data-year="${year}"] .ghost-bar`).evaluate(el=>el.style.background).then(rgb=>{const values=rgb.match(/\d+/g).map(Number);return '#'+values.map(v=>v.toString(16).padStart(2,'0')).join('')}));
 for(const year of [2025,2024,2023])await page.locator(`#ghost-controls [data-year="${year}"]`).uncheck();
 assert.equal(await page.locator('#chart [data-ghost-year]').count(),0);assert.ok((await page.locator('#ghost-note').innerText()).includes('Select a year'));
 await page.locator('#ghost-controls [data-year="2025"]').focus();await page.keyboard.press('Space');assert.equal(await page.locator('#chart [data-ghost-year]').count(),1);
 await page.locator('#ghost-controls [data-year="2024"]').check();await page.locator('#ghost-controls [data-year="2023"]').check();
 for(const [filter,count] of [['duration',4],['count',4],['weekly',2],['best',2]]){await page.locator(`[data-filter="${filter}"]`).click();assert.equal(await page.locator('#rows tr').count(),count);assert.ok(await page.locator('#rows tr.selected').count());assert.equal(await page.locator('#chart [data-ghost-year]').count(),3);assert.equal(await page.locator('#history .history-row').count(),1);assert.ok(await page.locator('#ghost .ghost-row').evaluateAll(rows=>new Set(rows.map(r=>r.dataset.selectedGoal)).size===1));}
 assert.ok((await page.locator('#legend').innerText()).includes('Target'));
 assert.ok(!(await page.locator('#legend').innerText()).includes('Required from today'));
 await page.screenshot({path:path.join(__dirname,'review-shots/goals-08-achievement.png'),fullPage:true});
 await page.locator('[data-filter="weekly"]').click();await page.locator('tr[data-select="workouts"] .goal-name').click();assert.ok((await page.locator('#hero').innerText()).includes('2'));assert.ok((await page.locator('#chart-title').innerText()).includes('September 7–13'));assert.ok((await page.locator('#stats').innerText()).includes('2 days left'));
 await page.screenshot({path:path.join(__dirname,'review-shots/goals-08-weekly.png'),fullPage:true});
 await page.locator('[data-filter="all"]').click();await page.locator('#search').fill('boulder');assert.equal(await page.locator('#rows tr').count(),4);
 await page.locator('#search').fill('no matching goal');assert.equal(await page.locator('#rows tr').count(),0);assert.ok(await page.locator('#empty').isVisible());assert.ok(await page.locator('#detail').isHidden());
 await page.locator('#search').fill('');await page.locator('th[data-key="pace"] button').click();await page.locator('tr[data-select="reading"] .goal-name').click();
 await page.locator('.table-scroll').evaluate(el=>el.scrollTop=0);
 await page.locator('.cell[tabindex="0"]').first().focus();await page.keyboard.press('ArrowLeft');assert.ok((await page.locator('#history-readout').innerText()).includes('Sep 10'));
 await page.locator('#search').focus();await page.screenshot({path:path.join(__dirname,'review-shots/goals-08-desktop.png'),fullPage:true});
 for(const width of [390,768]){
 await page.setViewportSize({width,height:844});await page.waitForTimeout(150);
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No page overflow at '+width);
 await page.screenshot({path:path.join(__dirname,`review-shots/goals-08-${width===390?'mobile':'tablet'}.png`),fullPage:true});
 }
 assert.deepEqual(errors,[]);
 console.log('PASS: 12 goal fixtures and 36 ghost series reconcile; selected-only panels, multiple years, stable colors, keyboard toggles, selection persistence, sorting, filters, empty state, selection, weekly and achievement views, keyboard history, desktop graph fit, and mobile/tablet overflow.');
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
