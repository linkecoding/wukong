const buttons=[...document.querySelectorAll('[data-view]')];function setView(view){document.body.classList.toggle('show-model',view==='model');for(const b of buttons){const active=b.dataset.view===view;b.classList.toggle('selected',active);b.setAttribute('aria-pressed',String(active));}}for(const button of buttons)button.addEventListener('click',()=>setView(button.dataset.view));if(new URLSearchParams(location.search).get('view')==='model')setView('model');document.documentElement.dataset.ready='true';
if(new URLSearchParams(location.search).has('qa')){
 const original=document.body.classList.contains('show-model')?'model':'home';
 setView('model');const modelSwitch=document.body.classList.contains('show-model')&&document.querySelector('[data-view="model"]').getAttribute('aria-pressed')==='true';
 setView('home');const homeSwitch=!document.body.classList.contains('show-model');setView(original);
 const clipped=[...document.querySelectorAll('button,.primary,.sample,.preview-tag,.app-nav>span')].filter(el=>el.getClientRects().length&&el.scrollWidth>el.clientWidth+1).map(el=>el.textContent.trim());
 const report={viewport:innerWidth,documentWidth:document.documentElement.scrollWidth,overflow:document.documentElement.scrollWidth>innerWidth,clipped,modelSwitch,homeSwitch,images:[...document.images].every(img=>img.complete&&img.naturalWidth>0)};
 const node=document.createElement('script');node.type='application/json';node.id='qa-report';node.textContent=JSON.stringify(report);document.body.append(node);
}
