package net.ivpn.client.common.bindings;

import androidx.databinding.BindingAdapter;
import android.webkit.WebView;

public class WebViewBindingAdapter {

    @BindingAdapter("url")
    public static void setWebViewUrl(WebView view, String url) {
        if (view != null) {
            view.loadUrl(url);
        }
    }
}
