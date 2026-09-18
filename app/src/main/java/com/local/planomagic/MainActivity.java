package com.local.planomagic;

import android.app.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOST = "plano.wonderbox.com";
    private static final String URL = "https://" + HOST + "/login/auth";
    private final Handler timer = new Handler(Looper.getMainLooper());
    private CredentialStore secrets;
    private WebView web;
    private TextView status;
    private boolean basicTried, adTried, failureShown;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        secrets = new CredentialStore(this);
        buildUi();
        configureWebView();
        if (secrets.isConfigured()) openPlano(); else editAll(true);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12),dp(4),dp(6),dp(4));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        TextView title = new TextView(this); title.setText("Plano Magic"); title.setTextSize(18);
        status = new TextView(this); status.setText("Prêt"); status.setTextSize(12);
        labels.addView(title); labels.addView(status);
        Button reload = new Button(this); reload.setText("↻"); reload.setOnClickListener(v -> openPlano());
        Button settings = new Button(this); settings.setText("⚙"); settings.setOnClickListener(v -> settings());
        bar.addView(labels); bar.addView(reload); bar.addView(settings);
        web = new WebView(this); web.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
        root.addView(bar); root.addView(web); setContentView(root);
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setSaveFormData(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (allowed(r.getUrl())) return false;
                if (r.isForMainFrame()) Toast.makeText(MainActivity.this,"Navigation hors Plano bloquée",Toast.LENGTH_SHORT).show();
                return r.isForMainFrame();
            }
            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                if (!HOST.equalsIgnoreCase(host) || !secrets.isConfigured()) { h.cancel(); return; }
                if (basicTried) { h.cancel(); status("Accès 1 refusé"); editAll(false); return; }
                basicTried = true; status("Connexion 1/2…"); h.proceed(secrets.get(CredentialStore.BASIC_USER), secrets.get(CredentialStore.BASIC_PASS));
            }
            @Override public void onPageFinished(WebView v, String u) { if (allowed(Uri.parse(u))) tryAdLogin(); }
        });
    }

    private boolean allowed(Uri u) { return u != null && "https".equalsIgnoreCase(u.getScheme()) && HOST.equalsIgnoreCase(u.getHost()); }

    private void tryAdLogin() {
        if (!secrets.isConfigured() || adTried) return;
        String user = JSONObject.quote(secrets.get(CredentialStore.AD_USER));
        String pass = JSONObject.quote(secrets.get(CredentialStore.AD_PASS));
        String js = "(function(){" +
                "function V(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}" +
                "var p=[].slice.call(document.querySelectorAll('input[type=password]')).find(V);if(!p)return 'NONE';" +
                "var a=['input[autocomplete=username]','input[name*=user i]','input[name*=login i]','input[name*=ident i]','input[type=text]'];" +
                "var u=null;for(var i=0;i<a.length&&!u;i++)u=[].slice.call(document.querySelectorAll(a[i])).find(V);if(!u)return 'NONE';" +
                "function F(e,x){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');if(d&&d.set)d.set.call(e,x);else e.value=x;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}" +
                "F(u,"+user+");F(p,"+pass+");var f=p.form||u.form;setTimeout(function(){if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}else{var b=document.querySelector('button,input[type=submit]');if(b)b.click();}},120);return 'OK';})()";
        web.evaluateJavascript(js, r -> {
            if (r != null && r.contains("OK")) { adTried = true; status("Connexion 2/2…"); timer.postDelayed(this::checkAdFailure,7000); }
            else status("Connecté");
        });
    }

    private void checkAdFailure() {
        if (!adTried || failureShown || isFinishing()) return;
        web.evaluateJavascript("(function(){var p=document.querySelector('input[type=password]');return !!(p&&p.offsetParent!==null);})()", r -> {
            if ("true".equals(r)) {
                failureShown = true; status("Mot de passe AD à mettre à jour");
                new AlertDialog.Builder(this).setTitle("Connexion AD refusée").setMessage("Ton mot de passe AD a peut-être changé. Mets-le à jour une seule fois.")
                        .setPositiveButton("Mettre à jour",(d,w)->editAdPassword()).setNegativeButton("Annuler",null).show();
            } else status("Connecté");
        });
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return e;
    }

    private LinearLayout form(EditText... fields) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(20),dp(8),dp(20),0);
        for (EditText e: fields) l.addView(e); return l;
    }

    private void editAll(boolean first) {
        EditText bu=input("Identifiant accès 1",false), bp=input(first?"Mot de passe accès 1":"Nouveau mot de passe accès 1 (vide = conserver)",true);
        EditText au=input("Login AD",false), ap=input(first?"Mot de passe AD":"Nouveau mot de passe AD (vide = conserver)",true);
        bu.setText(secrets.get(CredentialStore.BASIC_USER)); au.setText(secrets.get(CredentialStore.AD_USER));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(first?"Configurer Plano Magic":"Modifier les identifiants")
                .setView(form(bu,bp,au,ap)).setPositiveButton("Enregistrer",null).setNegativeButton(first?null:"Annuler",null).create();
        d.setCancelable(!first); d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u1=bu.getText().toString().trim(), u2=au.getText().toString().trim(), p1=bp.getText().toString(), p2=ap.getText().toString();
            if (u1.isEmpty() || u2.isEmpty() || (first && (p1.isEmpty() || p2.isEmpty()))) { Toast.makeText(this,"Tous les champs requis doivent être remplis",Toast.LENGTH_SHORT).show(); return; }
            secrets.put(CredentialStore.BASIC_USER,u1); secrets.put(CredentialStore.AD_USER,u2); if(!p1.isEmpty())secrets.put(CredentialStore.BASIC_PASS,p1); if(!p2.isEmpty())secrets.put(CredentialStore.AD_PASS,p2);
            d.dismiss(); resetAndOpen();
        })); d.show();
    }

    private void editAdPassword() {
        EditText p=input("Nouveau mot de passe AD",true);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Mettre à jour le mot de passe AD").setView(form(p)).setPositiveButton("Enregistrer et reconnecter",null).setNegativeButton("Annuler",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String s=p.getText().toString(); if(s.isEmpty()){p.setError("Requis");return;} secrets.put(CredentialStore.AD_PASS,s); d.dismiss(); resetAndOpen();})); d.show();
    }

    private void settings() {
        String[] a={"Modifier le mot de passe AD","Modifier tous les identifiants","Réinitialiser la session","Effacer les identifiants"};
        new AlertDialog.Builder(this).setTitle("Plano Magic").setItems(a,(d,n)->{if(n==0)editAdPassword(); else if(n==1)editAll(false); else if(n==2)resetAndOpen(); else clearAll();}).show();
    }

    private void clearAll() {
        new AlertDialog.Builder(this).setTitle("Effacer les identifiants ?").setPositiveButton("Effacer",(d,w)->{secrets.clear(); clearWeb(); editAll(true);}).setNegativeButton("Annuler",null).show();
    }

    private void clearWeb() {
        basicTried=false; adTried=false; failureShown=false; timer.removeCallbacksAndMessages(null);
        web.clearCache(true); web.clearHistory(); WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush();
    }

    private void resetAndOpen() { clearWeb(); openPlano(); }
    private void openPlano() { if(!secrets.isConfigured()){editAll(true);return;} basicTried=false; adTried=false; failureShown=false; status("Ouverture…"); web.loadUrl(URL); }
    private void status(String s) { status.setText(s); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() { if(web.canGoBack())web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { timer.removeCallbacksAndMessages(null); if(web!=null){web.stopLoading();web.destroy();} super.onDestroy(); }
}
