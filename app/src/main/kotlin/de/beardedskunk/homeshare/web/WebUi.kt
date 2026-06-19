package de.beardedskunk.homeshare.web

/**
 * Eingebettete Single-Page-Web-UI fuer den PC-Browser. Bewusst abhaengigkeitsfrei
 * (Vanilla JS), spricht die JSON-API von [WebServer] an. Unterstuetzt u. a. das
 * Einfuegen eines Screenshots aus der Windows-Zwischenablage (paste-Event).
 */
val WEB_UI_HTML: String = """
<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>HomeShare</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; background: #fafafa; color: #222; }
  header { background: #1565c0; color: #fff; padding: 10px 16px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  header select, header input, header button { font-size: 14px; padding: 6px; }
  main { max-width: 820px; margin: 0 auto; padding: 12px; }
  .post { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 10px; margin: 8px 0; }
  .post.conflict { border-color: #c62828; background: #fff3f3; }
  .post img { max-height: 160px; border-radius: 6px; margin-right: 6px; vertical-align: top; }
  .meta { color: #888; font-size: 12px; }
  .row { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
  textarea { width: 100%; min-height: 60px; box-sizing: border-box; padding: 8px; }
  button { cursor: pointer; }
  .composer { position: sticky; bottom: 0; background: #fafafa; padding: 8px 0; border-top: 1px solid #ddd; }
  .pending { color: #1565c0; font-size: 13px; }
</style>
</head>
<body>
<header>
  <strong>HomeShare</strong>
  <select id="feed" onchange="loadPosts()"></select>
  <button onclick="newFeed()">+ Feed</button>
  <input id="q" placeholder="Suchen..." oninput="onSearch()">
</header>
<main>
  <div id="posts"></div>
  <div class="composer">
    <textarea id="text" placeholder="Nachricht... (Bild mit Strg+V einfuegen)"></textarea>
    <div class="row">
      <input type="file" id="file" accept="image/*" multiple onchange="onFiles()">
      <span class="pending" id="pending"></span>
      <span style="flex:1"></span>
      <button onclick="send()">Senden</button>
    </div>
  </div>
</main>
<script>
var pending = [];

function api(path, opts) { return fetch(path, opts).then(function(r){ return r; }); }
function jpost(path, obj) {
  return fetch(path, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(obj) });
}
function feedId() { return document.getElementById('feed').value; }

function loadFeeds() {
  fetch('/api/feeds').then(function(r){return r.json();}).then(function(list){
    var sel = document.getElementById('feed');
    var cur = sel.value;
    sel.innerHTML = '';
    list.forEach(function(f){
      var o = document.createElement('option'); o.value = f.id; o.textContent = f.name; sel.appendChild(o);
    });
    if (cur) sel.value = cur;
    loadPosts();
  });
}

function loadPosts() {
  var fid = feedId(); if (!fid) { document.getElementById('posts').innerHTML = ''; return; }
  var q = document.getElementById('q').value.trim();
  var url = q ? ('/api/search?feed=' + encodeURIComponent(fid) + '&q=' + encodeURIComponent(q))
              : ('/api/posts?feed=' + encodeURIComponent(fid));
  fetch(url).then(function(r){return r.json();}).then(render);
}

function render(posts) {
  var c = document.getElementById('posts'); c.innerHTML = '';
  posts.forEach(function(p){
    var div = document.createElement('div');
    div.className = 'post' + (p.conflicted ? ' conflict' : '');
    var imgs = (p.images || []).map(function(s){
      return '<a href="/blob/' + s + '" target="_blank"><img src="/thumb/' + s + '"></a>';
    }).join('');
    var when = new Date(p.created).toLocaleString();
    var actions = '';
    if (p.conflicted) {
      actions = '<button onclick="resolve(\'' + p.postId + '\')">Konflikt aufloesen</button>';
    } else {
      actions = '<button onclick="editPost(\'' + p.postId + '\')">Bearbeiten</button>'
              + ' <button onclick="del(\'' + p.postId + '\')">Loeschen</button>';
    }
    div.innerHTML = imgs + '<div>' + escapeHtml(p.deleted ? '(geloescht)' : p.text) + '</div>'
                  + '<div class="meta">' + when + '</div><div class="row">' + actions + '</div>';
    c.appendChild(div);
  });
  window.scrollTo(0, document.body.scrollHeight);
}

function escapeHtml(s){ var d=document.createElement('div'); d.textContent=s||''; return d.innerHTML; }

function newFeed() {
  var name = prompt('Name des neuen Feeds:'); if (!name) return;
  jpost('/api/feeds', {name:name}).then(loadFeeds);
}

function onSearch() { loadPosts(); }

function fileToB64(file) {
  return new Promise(function(res){
    var fr = new FileReader();
    fr.onload = function(){ var s = fr.result; res(s.substring(s.indexOf(',')+1)); };
    fr.readAsDataURL(file);
  });
}

function onFiles() {
  var files = document.getElementById('file').files;
  var proms = []; for (var i=0;i<files.length;i++) proms.push(fileToB64(files[i]));
  Promise.all(proms).then(function(b64s){ pending = pending.concat(b64s); updatePending(); });
}

function updatePending(){ document.getElementById('pending').textContent = pending.length ? (pending.length + ' Bild(er) angehaengt') : ''; }

document.addEventListener('paste', function(e){
  var items = (e.clipboardData || {}).items || [];
  for (var i=0;i<items.length;i++){
    if (items[i].type && items[i].type.indexOf('image') === 0) {
      var blob = items[i].getAsFile();
      fileToB64(blob).then(function(b64){ pending.push(b64); updatePending(); });
    }
  }
});

function send() {
  var fid = feedId(); if (!fid) { alert('Erst einen Feed waehlen/anlegen.'); return; }
  var text = document.getElementById('text').value;
  if (!text.trim() && pending.length === 0) return;
  jpost('/api/post', {feed:fid, text:text, imagesB64:pending}).then(function(){
    document.getElementById('text').value=''; pending=[]; updatePending(); loadPosts();
  });
}

function editPost(id) {
  var t = prompt('Neuer Text:'); if (t === null) return;
  jpost('/api/post/edit', {feed:feedId(), postId:id, text:t}).then(loadPosts);
}

function del(id) {
  if (!confirm('Beitrag loeschen?')) return;
  jpost('/api/post/delete', {feed:feedId(), postId:id}).then(loadPosts);
}

function resolve(id) {
  fetch('/api/conflict?post=' + encodeURIComponent(id)).then(function(r){return r.json();}).then(function(c){
    var msg = 'Gemeinsame Basis:\n' + c.base + '\n\n';
    c.heads.forEach(function(h, i){
      msg += '[' + i + '] ' + (h.deleted ? '(geloescht)' : h.text) + '\n';
    });
    msg += '\nNummer der zu behaltenden Fassung eingeben:';
    var pick = prompt(msg, '0');
    if (pick === null) return;
    var h = c.heads[parseInt(pick, 10)];
    if (!h) return;
    jpost('/api/post/resolve', {feed:feedId(), postId:id, text:h.text, images:h.images, deleted:h.deleted}).then(loadPosts);
  });
}

loadFeeds();
setInterval(loadPosts, 5000);
</script>
</body>
</html>
""".trimIndent()
