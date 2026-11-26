// Qibla map tools that do not depend on the phone's compass (the user picks one in the settings to try).
// Uses globals from qibla_map.html: map, pos, bearing, applyBearing, KAABA, rad, deg, gc, path, me, line.
(function () {
  var T = {}, active = null, layers = [], timer = null, gpsPos = null, manual = false;
  var bar = document.createElement('div'); bar.id = 'toolBar'; document.body.appendChild(bar);
  var arrow = document.createElement('div'); arrow.id = 'bigArrow';
  arrow.innerHTML = '<svg viewBox="0 0 100 100"><path d="M50 6 L74 40 L58 40 L58 92 L42 92 L42 40 L26 40Z" fill="#4CAF50" stroke="#fff" stroke-width="3"/></svg><div id="bigLab"></div>';
  document.body.appendChild(arrow);
  var ruler = document.createElement('div'); ruler.id = 'rulerView'; document.body.appendChild(ruler);
  var st = document.createElement('style');
  st.textContent = '#toolBar{position:fixed;left:66px;right:10px;top:10px;background:#E9C46Af2;color:#2a1a00;border-radius:16px;padding:9px 12px;font:bold 14.5px sans-serif;line-height:1.55;z-index:1002;display:none;box-shadow:0 3px 12px #0007}' +
    '#toolBar b{font-size:16px}#toolBar .r{display:flex;gap:6px;margin-top:7px;flex-wrap:wrap}#toolBar button{border:0;border-radius:11px;padding:7px 11px;font:bold 13.5px sans-serif;background:#2a1a00;color:#E9C46A}#toolBar button.l{background:#fff9;color:#2a1a00}' +
    '#bigArrow{position:fixed;left:50%;top:50%;width:150px;height:150px;margin:-75px 0 0 -75px;z-index:997;display:none;pointer-events:none;filter:drop-shadow(0 3px 6px #000)}#bigArrow svg{width:100%;height:100%}' +
    '#bigLab{position:absolute;left:50%;top:100%;transform:translateX(-50%);white-space:nowrap;background:#000b;color:#fff;font:bold 15px sans-serif;border-radius:10px;padding:2px 10px}' +
    '#rulerView{position:fixed;inset:0;background:#0f1419;z-index:1005;display:none;color:#fff;font:bold 16px sans-serif}' +
    '.tlab{background:#000c;color:#fff;font:bold 12px sans-serif;border-radius:7px;padding:1px 6px;white-space:nowrap}';
  document.head.appendChild(st);

  function brg(a, b) { var f1 = rad(a[0]), f2 = rad(b[0]), dl = rad(b[1] - a[1]);
    return (deg(Math.atan2(Math.sin(dl) * Math.cos(f2), Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl))) + 360) % 360; }
  function dist(a, b) { var f1 = rad(a[0]), f2 = rad(b[0]), df = f2 - f1, dl = rad(b[1] - a[1]);
    var h = Math.sin(df / 2) * Math.sin(df / 2) + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2); return 6371000 * 2 * Math.asin(Math.sqrt(h)); }
  function dest(p, b, m) { var d = m / 6371000, f1 = rad(p[0]), l1 = rad(p[1]), t = rad(b);
    var f2 = Math.asin(Math.sin(f1) * Math.cos(d) + Math.cos(f1) * Math.sin(d) * Math.cos(t));
    var l2 = l1 + Math.atan2(Math.sin(t) * Math.sin(d) * Math.cos(f1), Math.cos(d) - Math.sin(f1) * Math.sin(f2)); return [deg(f2), deg(l2)]; }
  function norm(a) { a = ((a % 360) + 540) % 360 - 180; return a; }
  function q() { return pos ? brg(pos, KAABA) : 248; }
  function side(a) { var n = Math.round(Math.abs(a)); return n === 0 ? 'أمامك بالضبط' : (n + '° ' + (a > 0 ? 'يمين' : 'يسار')); }
  function add(l) { l.addTo(map); layers.push(l); return l; }
  function clear() { layers.forEach(function (l) { map.removeLayer(l); }); layers = []; if (timer) { clearInterval(timer); timer = null; } }
  function say(html) { bar.innerHTML = html; bar.style.display = 'block'; }
  function showArrow(screenDeg, lab) { arrow.style.display = 'block'; arrow.querySelector('svg').style.transform = 'rotate(' + screenDeg + 'deg)'; document.getElementById('bigLab').textContent = lab || ''; }
  function hideArrow() { arrow.style.display = 'none'; }
  function label(p, text) { return add(L.marker(p, { icon: L.divIcon({ className: '', html: '<span class="tlab">' + text + '</span>', iconSize: null }) })); }

  // Screen point -> map position, undoing the map's rotation (Leaflet's own conversion doesn't know about it).
  window.screenToLatLng = function (x, y) {
    var m = document.getElementById('map'), D = m.offsetWidth, a = rad(bearing);
    var dx = x - innerWidth / 2, dy = y - innerHeight / 2;
    var lx = dx * Math.cos(a) + dy * Math.sin(a), ly = -dx * Math.sin(a) + dy * Math.cos(a);
    var ll = map.containerPointToLatLng(L.point(D / 2 + lx, D / 2 + ly)); return [ll.lat, ll.lng];
  };

  // ---- sun and moon (enough accuracy for a direction: about half a degree) ----
  function jd(date) { return date.getTime() / 86400000 + 2440587.5 - 2451545.0; }
  function horiz(ra, dec, d, p) { var gmst = (280.46061837 + 360.98564736629 * d) % 360, H = rad(gmst + p[1]) - ra, f = rad(p[0]);
    var alt = Math.asin(Math.sin(f) * Math.sin(dec) + Math.cos(f) * Math.cos(dec) * Math.cos(H));
    var az = Math.atan2(-Math.sin(H), Math.tan(dec) * Math.cos(f) - Math.sin(f) * Math.cos(H)); return { az: (deg(az) + 360) % 360, alt: deg(alt) }; }
  function sun(p) { var d = jd(new Date()), g = rad((357.529 + 0.98560028 * d) % 360), qq = (280.459 + 0.98564736 * d) % 360;
    var Lm = rad(qq + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)), e = rad(23.439 - 0.00000036 * d);
    return horiz(Math.atan2(Math.cos(e) * Math.sin(Lm), Math.cos(Lm)), Math.asin(Math.sin(e) * Math.sin(Lm)), d, p); }
  function moon(p) { var d = jd(new Date()), Lm = rad(218.316 + 13.176396 * d), M = rad(134.963 + 13.064993 * d), F = rad(93.272 + 13.229350 * d);
    var l = Lm + rad(6.289) * Math.sin(M), b = rad(5.128) * Math.sin(F), e = rad(23.4397);
    var ra = Math.atan2(Math.sin(l) * Math.cos(e) - Math.tan(b) * Math.sin(e), Math.cos(l)), dec = Math.asin(Math.sin(b) * Math.cos(e) + Math.cos(b) * Math.sin(e) * Math.sin(l));
    return horiz(ra, dec, d, p); }

  // 1 — wall ruler: two taps on a wall/street; then a screen arrow at the qibla angle from that wall.
  T.ruler = {
    start: function () { this.pts = []; say('<b>📐 مسطرة الجدار</b><br>قرّب الخريطة لين تشوف المبنى، واضغط على <b>طرفين جدار</b> أو حافة الشارع.'); },
    tap: function (p) {
      var me_ = this; if (this.pts.length >= 2) { clear(); this.pts = []; }
      this.pts.push(p); add(L.circleMarker(p, { radius: 8, color: '#fff', weight: 2, fillColor: '#00E5FF', fillOpacity: 1 }));
      label(p, this.pts.length === 1 ? '١' : '٢');
      if (this.pts.length < 2) { say('<b>📐 مسطرة الجدار</b><br>الحين اضغط على <b>الطرف الثاني</b> لنفس الجدار.'); return; }
      add(L.polyline(this.pts, { color: '#00E5FF', weight: 6 }));
      var w = brg(this.pts[0], this.pts[1]); this.a = norm(q() - w);
      say('<b>القبلة ' + side(this.a) + ' من هالجدار</b><br>ضع <b>جنب الجوال الأيسر</b> على نفس الجدار في الواقع، و<b>رأس الجوال باتجاه النقطة ٢</b>، واضغط «المسطرة».' +
        '<div class="r"><button onclick="QT.ruler.open()">▶ المسطرة</button><button class="l" onclick="QT.ruler.start();QT.clear()">↺ جدار ثاني</button></div>');
    },
    open: function () {
      ruler.innerHTML = '<div style="position:absolute;left:0;top:0;bottom:0;width:16px;background:#00E5FF"></div>' +
        '<div style="position:absolute;left:26px;top:22px;color:#00E5FF">◀ هذا الجنب على الجدار، والرأس باتجاه النقطة ٢</div>' +
        '<svg viewBox="0 0 100 100" style="position:absolute;left:50%;top:50%;width:70vmin;height:70vmin;margin:-35vmin 0 0 -35vmin;transform:rotate(' + this.a + 'deg)"><path d="M50 4 L72 34 L57 34 L57 96 L43 96 L43 34 L28 34Z" fill="#4CAF50" stroke="#fff" stroke-width="2"/></svg>' +
        '<div style="position:absolute;left:0;right:0;bottom:120px;text-align:center;font-size:22px">🕋 القبلة ' + side(this.a) + ' من الجدار</div>' +
        '<div style="position:absolute;left:0;right:0;bottom:60px;text-align:center;color:#aaa;font-size:14px">بدون بوصلة — الحديد ما يأثر. حط سجادتك على اتجاه السهم.</div>' +
        '<button onclick="QT.ruler.close()" style="position:absolute;top:16px;right:16px;border:0;border-radius:12px;padding:10px 16px;font:bold 16px sans-serif;background:#E9C46A">✕ رجوع</button>';
      ruler.style.display = 'block';
    },
    close: function () { ruler.style.display = 'none'; }
  };

  // 2 — walk: GPS track of a straight walk gives the direction you face; the map turns to it.
  T.walk = {
    start: function () { this.trk = null; say('<b>🚶 امشِ واعرف اتجاهك</b><br>اطلع مكان مكشوف (الحوش أو الشارع)، اضغط «ابدأ»، وامشِ <b>مستقيم ١٥–٢٠ خطوة</b>.<div class="r"><button onclick="QT.walk.go()">▶ ابدأ</button></div>'); },
    go: function () { if (!gpsPos) { say('<b>🚶</b> بانتظار الـ GPS… حاول برا المبنى.'); return; }
      this.trk = [gpsPos.slice()]; this.poly = add(L.polyline(this.trk, { color: '#00E676', weight: 6, dashArray: '1 12', lineCap: 'round' }));
      say('<b>🚶 امشِ مستقيم…</b> <span id="wd">٠ م</span><div class="r"><button class="l" onclick="QT.walk.start();QT.clear()">↺ من جديد</button></div>'); },
    gps: function (p) { if (!this.trk) return; this.trk.push(p); this.poly.setLatLngs(this.trk);
      var d = dist(this.trk[0], p), el = document.getElementById('wd'); if (el) el.textContent = Math.round(d) + ' م';
      if (d < 8) return;
      var b = brg(this.trk[0], p), a = norm(q() - b); bearing = -b; applyBearing(); map.setView(p, map.getZoom(), { animate: false });
      showArrow(a, '🕋 ' + side(a));
      say('<b>اتجاه مشيك: ' + Math.round(b) + '°</b> — القبلة ' + side(a) + ' من اتجاه مشيك. السهم الأخضر يأشر لها.<div class="r"><button class="l" onclick="QT.walk.start();QT.clear()">↺ من جديد</button></div>'); }
  };

  // 3 — I'm here: long-press puts you exactly where you are (GPS is often off indoors).
  T.here = {
    start: function () { say('<b>📍 أنا هنا</b><br>اضغط <b>مطوّلاً</b> على مكانك بالضبط في الخريطة، والخط الأصفر يطلع من هناك.' + (manual ? '<div class="r"><button class="l" onclick="QT.backToGps()">↩ رجوع للـ GPS</button></div>' : '')); },
    hold: function (p) { manual = true; window.setMe(p[0], p[1], 2, false, true);
      say('<b>📍 تم تحديد مكانك</b><br>الخط الأصفر الحين من النقطة اللي اخترتها.<div class="r"><button class="l" onclick="QT.backToGps()">↩ رجوع للـ GPS</button></div>'); }
  };

  // 4 — sun / moon: point the phone's top at the sun (or the moon at night).
  T.sun = {
    start: function () { var me_ = this; this.draw(); timer = setInterval(function () { me_.draw(); }, 30000); },
    draw: function () { clear2(); if (!pos) return say('بانتظار تحديد موقعك…');
      var s = sun(pos), body = s, name = '☀️ الشمس', ic = '☀️';
      if (s.alt < 2) { var m = moon(pos); if (m.alt > 3) { body = m; name = '🌙 القمر'; ic = '🌙'; } else return say('<b>☀️/🌙</b> الشمس والقمر تحت الأفق الحين. جرّب أداة ثانية.'); }
      add2(L.polyline([pos, dest(pos, body.az, 80)], { color: '#FFB300', weight: 5, dashArray: '8 8' })); add2(L.marker(dest(pos, body.az, 80), { icon: L.divIcon({ className: '', html: '<span style="font-size:30px">' + ic + '</span>', iconSize: null }) }));
      var a = norm(q() - body.az);
      say('<b>' + name + ' الحين على ' + Math.round(body.az) + '° (ارتفاعها ' + Math.round(body.alt) + '°)</b><br>وجّه <b>رأس الجوال</b> ' + (ic === '☀️' ? 'للشمس' : 'للقمر') + ' (أو لاتجاه ضوئها من الشباك)، والقبلة تكون <b>' + side(a) + '</b>.'); }
  };
  var tmp = []; function add2(l) { l.addTo(map); tmp.push(l); layers.push(l); } function clear2() { tmp.forEach(function (l) { map.removeLayer(l); }); tmp = []; }

  // 5 — match the map to what you see: turn it with two fingers; the green arrow points to the qibla on screen.
  T.align = {
    start: function () { say('<b>🔄 طابق الخريطة بالواقع</b><br>امسك الجوال قدامك، ولف الخريطة بإصبعين لين <b>الشارع والمباني تطابق اللي تشوفه قدامك</b>. السهم الأخضر يأشر للقبلة.'); this.upd(); },
    upd: function () { showArrow(norm(q() + bearing), '🕋 القبلة'); }
  };

  // 6 — nearby mosques: their qibla walls show the direction; compare your building with them.
  T.mosques = {
    start: function () { if (!pos) return say('بانتظار تحديد موقعك…'); say('<b>🕌 المساجد القريبة</b><br>جاري البحث عن المساجد حولك…');
      var qu = '[out:json][timeout:25];(way["amenity"="place_of_worship"]["religion"="muslim"](around:1500,' + pos[0] + ',' + pos[1] + ');node["amenity"="place_of_worship"]["religion"="muslim"](around:1500,' + pos[0] + ',' + pos[1] + '););out geom;';
      fetch('https://overpass-api.de/api/interpreter?data=' + encodeURIComponent(qu)).then(function (r) { return r.json(); }).then(function (j) {
        var n = 0; (j.elements || []).forEach(function (el) { n++;
          if (el.type === 'way' && el.geometry) add(L.polygon(el.geometry.map(function (g) { return [g.lat, g.lon]; }), { color: '#00E676', weight: 3, fillOpacity: .25 }));
          var c = el.type === 'way' && el.geometry ? [el.geometry[0].lat, el.geometry[0].lon] : [el.lat, el.lon];
          label(c, '🕌 ' + ((el.tags && el.tags.name) || 'مسجد')); });
        say('<b>🕌 ' + n + ' مسجد قريب</b><br>جدار المحراب في المسجد يقابل القبلة. قارن اتجاه مبناك مع أقرب مسجد تعرفه.');
      }).catch(function () { say('<b>🕌</b> تعذر جلب المساجد — تحتاج إنترنت.'); }); }
  };

  // 7 — parallel qibla lines across the whole building, every 5 m.
  T.grid = {
    start: function () { if (!pos) return say('بانتظار تحديد موقعك…'); var qq = q();
      for (var k = -30; k <= 30; k++) { var c = dest(pos, qq + 90, k * 5);
        add(L.polyline([dest(c, qq + 180, 250), dest(c, qq, 250)], { color: k === 0 ? '#FFD54F' : '#FFD54F99', weight: k === 0 ? 5 : 2 })); }
      say('<b>▦ خطوط القبلة المتوازية</b><br>كل الخطوط باتجاه القبلة. أي غرفة في المبنى، شوف الخطوط فوقها وقارنها بجدرانها.'); }
  };

  // 8 — pen shadow: stand a pen up in the sun; its shadow points away from the sun.
  T.shadow = {
    start: function () { var me_ = this; this.draw(); timer = setInterval(function () { me_.draw(); }, 30000); },
    draw: function () { clear2(); if (!pos) return say('بانتظار تحديد موقعك…'); var s = sun(pos);
      if (s.alt < 3) return say('<b>🖊 ظل القلم</b><br>الشمس مو طالعة الحين — هذي الأداة للنهار.');
      var sh = (s.az + 180) % 360, len = Math.min(8, 1 / Math.tan(rad(s.alt))), a = norm(q() - sh);
      add2(L.polyline([pos, dest(pos, sh, 25)], { color: '#424242', weight: 8, opacity: .8 }));
      say('<b>🖊 ظل القلم</b><br>اوقف قلم على الأرض بمكان فيه شمس (طول الظل ≈ ' + len.toFixed(1) + ' × طول القلم). وجّه <b>رأس الجوال باتجاه الظل</b>، والقبلة <b>' + side(a) + '</b>.'); }
  };

  // 9 — Pole star at night: it marks true north.
  T.polaris = {
    start: function () { if (!pos) return say('بانتظار تحديد موقعك…'); var s = sun(pos), a = norm(q());
      add(L.polyline([pos, dest(pos, 0, 80)], { color: '#90CAF9', weight: 4, dashArray: '6 8' })); label(dest(pos, 0, 80), '⭐ نجم القطب');
      say('<b>⭐ نجم القطب</b>' + (s.alt > -8 ? ' (يبان بعد المغرب بساعة)' : '') + '<br>نجم ثابت في الشمال، ارتفاعه ' + Math.round(pos[0]) + '° فوق الأفق. وجّه <b>رأس الجوال له</b>، والقبلة <b>' + side(a) + '</b>.'); }
  };

  // 10 — correct the compass by walking: GPS course vs compass while you walk straight (done in the app).
  T.walkcal = {
    start: function () { say('<b>🚶🧭 صحّح البوصلة بالمشي</b><br>امسك الجوال قدامك ورأسه للأمام، اضغط «ابدأ»، وامشِ <b>مستقيم ٢٠ خطوة</b> برا المبنى. التطبيق يقارن اتجاه مشيك من الـ GPS مع البوصلة ويصحّحها.<div class="r"><button onclick="Q.walkCalib(true)">▶ ابدأ</button></div>'); },
    progress: function (n) { say('<b>🚶🧭 امشِ مستقيم…</b> (' + n + ' / ٨ قراءات)<div class="r"><button class="l" onclick="Q.walkCalib(false);QT.walkcal.start()">إلغاء</button></div>'); },
    done: function (c) { say('<b>✓ تم تصحيح البوصلة</b><br>كانت منحرفة ' + Math.round(Math.abs(c)) + '°. الحين «تتبّع الاتجاه» والبوصلة صاروا أدق في هالمكان.'); }
  };

  window.QT = {
    ruler: T.ruler, walk: T.walk, walkcal: T.walkcal, clear: clear,
    start: function (id) { clear(); hideArrow(); ruler.style.display = 'none'; active = T[id] || null; if (active) active.start(); else bar.style.display = 'none'; },
    stop: function () { clear(); hideArrow(); bar.style.display = 'none'; active = null; },
    backToGps: function () { manual = false; if (gpsPos) window.setMe(gpsPos[0], gpsPos[1], gpsPos[2] || 10, false); if (active === T.here) T.here.start(); },
    gps: function (lat, lon, acc) { gpsPos = [lat, lon, acc]; if (active && active.gps) active.gps([lat, lon]); return manual; },
    tap: function (x, y) { if (active && active.tap) active.tap(screenToLatLng(x, y)); },
    hold: function (x, y) { if (active && active.hold) active.hold(screenToLatLng(x, y)); },
    turned: function () { if (active && active.upd) active.upd(); }
  };
})();
