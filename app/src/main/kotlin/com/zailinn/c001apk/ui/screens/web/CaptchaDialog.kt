package com.zailinn.c001apk.ui.screens.web

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zailinn.c001apk.CaptchaCoordinator
import com.zailinn.c001apk.ui.screens.appContainer

private const val BASE_URL = "https://captcha.c001apk.invalid/"

/** NetEase captcha page; the validate proof goes straight back to the waiting request. */
private fun captchaHtml(captchaId: String) = """<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>:root{color-scheme:light dark}body{font:16px sans-serif;margin:24px;color:CanvasText;background:Canvas}#captcha{margin-top:24px}p{line-height:1.6}</style>
</head><body><p id="status">正在加载验证组件…</p><div id="captcha"></div>
<script>
function statusText(text){document.getElementById('status').textContent=text;}
var timeout=setTimeout(function(){statusText('验证组件加载超时，请点击右上角重新加载');},15000);
var script=document.createElement('script');
script.src='https://cstaticdun.126.net/load.min.js';
script.onerror=function(){clearTimeout(timeout);statusText('验证组件加载失败，请检查网络后重新加载');};
script.onload=function(){
  if(typeof initNECaptcha!=='function'){clearTimeout(timeout);statusText('验证组件不可用，请重新加载');return;}
  try { initNECaptcha({captchaId:'$captchaId',element:'#captcha',protocol:'https',
    mode:'embed',apiVersion:2,width:Math.min(320,window.innerWidth-48)+'px',lang:'zh-CN',
    onVerify:function(err,data){
      if(!err && data && data.validate){C001apkCaptcha.verified(data.validate);}
      else{statusText('验证未通过，请按提示重试');}
    }
  },function(){clearTimeout(timeout);statusText('请按下方提示完成人机验证');},
    function(){clearTimeout(timeout);statusText('无法初始化验证，请检查网络后重新加载');});
  } catch(e){clearTimeout(timeout);statusText('验证组件初始化失败，请重新加载');}
};
document.head.appendChild(script);
</script></body></html>"""

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun CaptchaDialog(pending: CaptchaCoordinator.Pending) {
    val app = appContainer()
    var web by remember { mutableStateOf<WebView?>(null) }
    val html = remember(pending) { captchaHtml(pending.challenge.captchaId) }
    Dialog(onDismissRequest = { pending.complete(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("人机验证") },
                    navigationIcon = { IconButton(onClick = { pending.complete(null) }) { Icon(Icons.Filled.Close, "取消") } },
                    actions = {
                        IconButton(onClick = { web?.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null) }) {
                            Icon(Icons.Outlined.Refresh, "重新加载")
                        }
                    },
                )
            },
        ) { padding ->
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.userAgentString = app.settings.current.protocol.userAgent.ifEmpty { settings.userAgentString }
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun verified(validate: String?) {
                                val proof = pending.challenge.proof(validate) ?: return
                                post { pending.complete(proof) }
                            }
                        }, "C001apkCaptcha")
                        webViewClient = object : WebViewClient() {
                            // The captcha never needs to navigate the top-level page away.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame
                        }
                        loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
                        web = this
                    }
                },
                modifier = Modifier.padding(padding).fillMaxSize(),
                onRelease = { it.destroy() },
            )
        }
    }
}
