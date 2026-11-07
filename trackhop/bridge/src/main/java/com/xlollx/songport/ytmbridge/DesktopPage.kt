package com.xlollx.songport.ytmbridge

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * A desktop browser, from inside the page: a 1280 px wide window, screen and viewport (Qobuz's web
 * player asks for at least 1024 px and otherwise shows "download the app"), a Windows platform, no
 * touch, and the media checks a desktop player runs before it loads answered. Used where a site's
 * desktop page is needed to sign in or to watch its calls; nothing here plays anything.
 */
object DesktopPage {
    const val WIDTH = 1280

    /** Wide viewport and the script at document start (or, on an old WebView, at each page start: [lateInject]). */
    fun apply(web: WebView): Boolean {
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, setOf("*"))
        return true
    }

    /** For a WebView without document-start scripts: call from onPageStarted and onPageCommitVisible. */
    fun lateInject(web: WebView) = web.evaluateJavascript(SCRIPT, null)

    val SCRIPT = """
(function(){
  if (window.__thDesktop) return; window.__thDesktop = 1;
  var W = 1280;
  var wide = function(m){ if (m && m.getAttribute('name') === 'viewport') m.setAttribute('content', 'width=' + W); };
  try {
    new MutationObserver(function(rs){ rs.forEach(function(r){ r.addedNodes.forEach(function(n){ if (n.tagName === 'META') wide(n); }); }); })
      .observe(document, { childList: true, subtree: true });
    document.addEventListener('DOMContentLoaded', function(){
      var m = document.querySelector('meta[name=viewport]');
      if (!m) { m = document.createElement('meta'); m.setAttribute('name', 'viewport'); (document.head || document.documentElement).appendChild(m); }
      wide(m);
    });
  } catch(e){}
  try { Object.defineProperty(window, 'innerWidth', { get: function(){ return W; }, configurable: true }); } catch(e){}
  try { Object.defineProperty(window, 'outerWidth', { get: function(){ return W; }, configurable: true }); } catch(e){}
  try { Object.defineProperty(Screen.prototype, 'width', { get: function(){ return W; }, configurable: true }); } catch(e){}
  try { Object.defineProperty(Screen.prototype, 'availWidth', { get: function(){ return W; }, configurable: true }); } catch(e){}
  try { Object.defineProperty(document.documentElement, 'clientWidth', { get: function(){ return W; }, configurable: true }); } catch(e){}
  // What the desktop player checks before it loads (read from its own code): the platform
  // must not be Linux (navigator.platform, "Linux aarch64" on a phone), the browser must
  // not be mobile, MSE must accept FLAC in MP4 and Widevine must accept it too. Otherwise
  // "Update Chrome". This screen never plays anything, so the codec answers are given.
  try { Object.defineProperty(Navigator.prototype, 'platform', { get: function(){ return 'Win32'; }, configurable: true }); } catch(e){}
  try { Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: function(){ return 0; }, configurable: true }); } catch(e){}
  try {
    var isType = MediaSource.isTypeSupported.bind(MediaSource);
    MediaSource.isTypeSupported = function(t){ return /audio\/mp4/.test(String(t)) ? true : isType(t); };
  } catch(e){}
  try {
    var realEme = navigator.requestMediaKeySystemAccess.bind(navigator);
    var stub = function(ks, cfgs){
      var cfg = (cfgs && cfgs[0]) || {};
      return { keySystem: ks, getConfiguration: function(){ return cfg; },
        createMediaKeys: function(){ return Promise.resolve({
          createSession: function(){ return { addEventListener: function(){}, removeEventListener: function(){}, generateRequest: function(){ return Promise.resolve(); }, update: function(){ return Promise.resolve(); }, close: function(){ return Promise.resolve(); }, closed: Promise.resolve() }; },
          setServerCertificate: function(){ return Promise.resolve(true); } }); } };
    };
    navigator.requestMediaKeySystemAccess = function(ks, cfgs){ return realEme(ks, cfgs).catch(function(){ return stub(ks, cfgs); }); };
  } catch(e){}
  // A WebView has no window.chrome: a page that tells Chrome by it sees an old browser.
  try { if (!window.chrome) window.chrome = { runtime: {}, app: { isInstalled: false }, loadTimes: function(){ return {}; }, csi: function(){ return {}; } }; } catch(e){}
})();
""".trimIndent()
}
