(function () {
  var root = document.documentElement;
  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
  };

  /* ---- download: 按访问者的系统选安装包，地址在 <html data-dl-mac/-windows/-linux> ---- */
  var OS_NAMES = { mac: 'macOS', windows: 'Windows', linux: 'Linux' };
  var urls = {};
  Object.keys(OS_NAMES).forEach(function (os) {
    var u = (root.getAttribute('data-dl-' + os) || '').trim();
    if (u) urls[os] = u;
  });
  function detectOS() {
    var ua = navigator.userAgent || '';
    var uad = navigator.userAgentData;
    var p = ((uad && uad.platform) || navigator.platform || '') + ' ' + ua;
    if (/iPhone|iPad|iPod|Android/i.test(ua)) return null;
    if (/Mac/i.test(p)) return navigator.maxTouchPoints > 1 ? null : 'mac'; // iPadOS 也报 Macintosh
    if (/Win/i.test(p)) return 'windows';
    if (/Linux|X11|CrOS/i.test(p)) return 'linux';
    return null;
  }
  // 从文件名看架构：arm64 是 Apple 芯片 / ARM，x64 是 Intel / AMD。
  function archOf(u) {
    if (/arm64|aarch64/i.test(u)) return { en: 'Apple silicon', zh: 'Apple 芯片', arm: true };
    if (/x64|x86_64|amd64/i.test(u)) return { en: 'Intel', zh: 'Intel', arm: false };
    return null;
  }
  // 设置双语文字：语言切换读 data-en / data-zh。
  function setText(el, en, zh) {
    el.innerHTML = en;
    el.setAttribute('data-en', en);
    el.setAttribute('data-zh', zh);
  }
  function renderDownloads(os, unsupportedArch) {
    var url = os && !unsupportedArch ? urls[os] : null;
    var name = os ? OS_NAMES[os] : '';
    var any = Object.keys(urls).length > 0;
    Array.prototype.forEach.call(document.querySelectorAll('[data-dl]'), function (a) {
      var short = a.getAttribute('data-dl') === 'short';
      a.removeAttribute('aria-disabled');
      if (url) {
        a.setAttribute('href', url);
        if (short) setText(a, 'Download', '下载');
        else setText(a, 'Download for ' + name, '下载 ' + name + ' 版');
      } else if (os || !any) {
        a.removeAttribute('href');
        a.setAttribute('aria-disabled', 'true');
        var what = unsupportedArch ? name + ' (Intel)' : name;
        if (short || !os) setText(a, 'Coming soon', '即将上线');
        else setText(a, 'Coming soon for ' + what, what + ' 版即将上线');
      } else {
        a.setAttribute('href', '#get');
        setText(a, 'Download', '下载');
      }
    });
    // 下载区列出全部可用安装包，系统识别错了也能手动选。
    var list = document.querySelector('[data-dl-list]');
    if (list && any) {
      var en = [], zh = [];
      Object.keys(urls).forEach(function (k) {
        var arch = archOf(urls[k]);
        var href = ' href="' + urls[k].replace(/"/g, '&quot;') + '"';
        en.push('<a' + href + '>' + OS_NAMES[k] + (arch ? ' · ' + arch.en : '') + '</a>');
        zh.push('<a' + href + '>' + OS_NAMES[k] + (arch ? ' · ' + arch.zh : '') + '</a>');
      });
      setText(list, 'All downloads: ' + en.join(' / '), '全部安装包：' + zh.join(' / '));
      list.hidden = false;
    }
  }
  var visitorOS = detectOS();
  renderDownloads(visitorOS, false);
  var version = root.getAttribute('data-version') || '';
  function fillVersion() {
    Array.prototype.forEach.call(document.querySelectorAll('.ver'), function (el) { el.textContent = version; });
  }

  /* ---- language: English default, 中文 optional ---- */
  var ZH = {
    title: 'Preflight — 产品介绍',
    desc: 'Preflight 是一款免费的 Git 客户端。用清晰的分支图呈现历史，合并、变基、重置在真正改动仓库之前先给你预览。'
  };
  var titleEn = document.title;
  var descEl = document.getElementById('meta-desc');
  var descEn = descEl ? descEl.getAttribute('content') : '';
  var nodes = document.querySelectorAll('[data-zh],[data-zh-alt],[data-zh-label]');
  Array.prototype.forEach.call(nodes, function (el) {
    if (el.hasAttribute('data-zh')) el.setAttribute('data-en', el.innerHTML);
    if (el.hasAttribute('data-zh-alt')) el.setAttribute('data-en-alt', el.getAttribute('alt'));
    if (el.hasAttribute('data-zh-label')) el.setAttribute('data-en-label', el.getAttribute('aria-label'));
  });
  function setLang(lang, save) {
    var zh = lang === 'zh';
    Array.prototype.forEach.call(nodes, function (el) {
      if (el.hasAttribute('data-zh')) el.innerHTML = zh ? el.getAttribute('data-zh') : el.getAttribute('data-en');
      if (el.hasAttribute('data-zh-alt')) el.setAttribute('alt', zh ? el.getAttribute('data-zh-alt') : el.getAttribute('data-en-alt'));
      if (el.hasAttribute('data-zh-label')) el.setAttribute('aria-label', zh ? el.getAttribute('data-zh-label') : el.getAttribute('data-en-label'));
    });
    root.setAttribute('lang', zh ? 'zh-Hans' : 'en');
    document.title = zh ? ZH.title : titleEn;
    if (descEl) descEl.setAttribute('content', zh ? ZH.desc : descEn);
    Array.prototype.forEach.call(document.querySelectorAll('[data-lang]'), function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-lang') === lang));
    });
    fillVersion();
    if (save) store.set('gitviz-lang', lang);
  }
  var q = /[?&]lang=(en|zh)\b/.exec(location.search);
  var initial = q ? q[1] : store.get('gitviz-lang');
  setLang(initial === 'zh' ? 'zh' : 'en', false);
  Array.prototype.forEach.call(document.querySelectorAll('[data-lang]'), function (b) {
    b.addEventListener('click', function () { setLang(b.getAttribute('data-lang'), true); });
  });

  var macArch = urls.mac && archOf(urls.mac);
  var uad = navigator.userAgentData;
  if (visitorOS === 'mac' && macArch && macArch.arm && uad && uad.getHighEntropyValues) {
    uad.getHighEntropyValues(['architecture']).then(function (v) {
      if (v.architecture === 'x86') {
        renderDownloads('mac', true);
        setLang(root.getAttribute('lang') === 'zh-Hans' ? 'zh' : 'en', false);
      }
    }, function () {});
  }

  /* ---- theme: light / dark / follow system ---- */
  function setTheme(mode, save) {
    if (mode === 'light' || mode === 'dark') root.setAttribute('data-theme', mode);
    else { root.removeAttribute('data-theme'); mode = 'system'; }
    Array.prototype.forEach.call(document.querySelectorAll('[data-theme-set]'), function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-theme-set') === mode));
    });
    if (save) store.set('gitviz-theme', mode);
  }
  setTheme(store.get('gitviz-theme') || 'system', false);
  Array.prototype.forEach.call(document.querySelectorAll('[data-theme-set]'), function (b) {
    b.addEventListener('click', function () { setTheme(b.getAttribute('data-theme-set'), true); });
  });

  /* ---- header border once scrolled ---- */
  var top = document.getElementById('top');
  function onScroll() { top.classList.toggle('scrolled', window.scrollY > 4); }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();
