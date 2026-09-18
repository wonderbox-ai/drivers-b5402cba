package com.local.planomagic;

import android.app.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOST = "plano.wonderbox.com";
    private static final String URL = "https://" + HOST + "/login/auth";

    private final Handler timer = new Handler(Looper.getMainLooper());
    private CredentialStore secrets;
    private WebView web;
    private LinearLayout home;
    private FrameLayout content;
    private TextView status;
    private TextView refreshButton;
    private TextView menuButton;

    private boolean basicTried, adTried, failureShown;
    private boolean homeVisible;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        secrets = new CredentialStore(this);
        buildUi();
        configureWebView();

        if (secrets.isConfigured()) {
            openPlano();
        } else {
            editAll(true);
            showHome("Configuration requise");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(6), dp(8), dp(6));
        bar.setMinimumHeight(dp(58));
        bar.setBackgroundColor(Color.WHITE);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setText("Plano Magic");
        title.setTextSize(18);
        title.setTextColor(Color.rgb(30, 30, 30));

        status = new TextView(this);
        status.setText("Prêt");
        status.setTextSize(12);
        status.setTextColor(Color.rgb(105, 105, 105));

        labels.addView(title);
        labels.addView(status);

        refreshButton = compactAction("↻", 24);
        refreshButton.setContentDescription("Actualiser");
        refreshButton.setOnClickListener(v -> {
            if (homeVisible) openPlano();
            else web.reload();
        });

        menuButton = compactAction("⋮", 28);
        menuButton.setContentDescription("Menu");
        menuButton.setOnClickListener(this::showPopupMenu);

        bar.addView(labels);
        bar.addView(refreshButton);
        bar.addView(menuButton);

        View separator = new View(this);
        separator.setBackgroundColor(Color.rgb(232, 232, 232));
        separator.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        home = createHomeView();
        home.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        content.addView(web);
        content.addView(home);

        root.addView(bar);
        root.addView(separator);
        root.addView(content);
        setContentView(root);
    }

    private TextView compactAction(String text, int textSize) {
        TextView v = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(dp(2), 0, dp(2), 0);
        v.setLayoutParams(lp);
        v.setText(text);
        v.setTextSize(textSize);
        v.setTextColor(Color.rgb(55, 55, 55));
        v.setGravity(Gravity.CENTER);
        v.setBackground(roundedBg(Color.rgb(245, 245, 245), dp(12), Color.TRANSPARENT));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private LinearLayout createHomeView() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(22), dp(34), dp(22), dp(22));
        outer.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView heading = new TextView(this);
        heading.setText("Applications");
        heading.setTextSize(24);
        heading.setTextColor(Color.rgb(28, 28, 28));
        heading.setGravity(Gravity.CENTER);

        TextView info = new TextView(this);
        info.setText("Choisis une application. Plano reste en connexion automatique.");
        info.setTextSize(14);
        info.setTextColor(Color.rgb(100, 100, 100));
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(8), 0, dp(28));
        info.setLayoutParams(infoLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(roundedBg(Color.WHITE, dp(18), Color.rgb(225, 225, 225)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        TextView appName = new TextView(this);
        appName.setText("Plano");
        appName.setTextSize(20);
        appName.setTextColor(Color.rgb(25, 25, 25));

        TextView appDesc = new TextView(this);
        appDesc.setText("Authentification automatique en 2 étapes");
        appDesc.setTextSize(13);
        appDesc.setTextColor(Color.rgb(110, 110, 110));
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.setMargins(0, dp(4), 0, dp(14));
        appDesc.setLayoutParams(descLp);

        Button open = new Button(this);
        open.setText("Ouvrir Plano");
        open.setAllCaps(false);
        open.setOnClickListener(v -> openPlano());

        card.addView(appName);
        card.addView(appDesc);
        card.addView(open);

        TextView future = new TextView(this);
        future.setText("Tes 2 autres applications pourront être ajoutées ici plus tard.");
        future.setTextSize(13);
        future.setTextColor(Color.rgb(120, 120, 120));
        future.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams futureLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        futureLp.setMargins(0, dp(22), 0, 0);
        future.setLayoutParams(futureLp);

        outer.addView(heading);
        outer.addView(info);
        outer.addView(card);
        outer.addView(future);
        return outer;
    }

    private GradientDrawable roundedBg(int fillColor, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radius);
        if (strokeColor != Color.TRANSPARENT) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSaveFormData(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (allowed(r.getUrl())) return false;
                if (r.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "Navigation hors Plano bloquée", Toast.LENGTH_SHORT).show();
                }
                return r.isForMainFrame();
            }

            @Override public void onReceivedHttpAuthRequest(
                    WebView v, HttpAuthHandler h, String host, String realm) {
                if (!HOST.equalsIgnoreCase(host) || !secrets.isConfigured()) {
                    h.cancel();
                    return;
                }

                if (basicTried) {
                    h.cancel();
                    status("Accès 1 refusé");
                    editAll(false);
                    return;
                }

                basicTried = true;
                status("Connexion 1/2…");
                h.proceed(
                        secrets.get(CredentialStore.BASIC_USER),
                        secrets.get(CredentialStore.BASIC_PASS));
            }

            @Override public void onPageFinished(WebView v, String u) {
                Uri uri = Uri.parse(u);
                if (allowed(uri)) tryAdLogin();
            }
        });
    }

    private boolean allowed(Uri u) {
        return u != null
                && "https".equalsIgnoreCase(u.getScheme())
                && HOST.equalsIgnoreCase(u.getHost());
    }

    private void tryAdLogin() {
        if (!secrets.isConfigured() || adTried) return;

        String user = JSONObject.quote(secrets.get(CredentialStore.AD_USER));
        String pass = JSONObject.quote(secrets.get(CredentialStore.AD_PASS));

        String js = "(function(){"
                + "function V(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "var p=[].slice.call(document.querySelectorAll('input[type=password]')).find(V);"
                + "if(!p)return 'NONE';"
                + "var a=['input[autocomplete=username]','input[name*=user i]','input[name*=login i]',"
                + "'input[name*=ident i]','input[type=text]'];"
                + "var u=null;for(var i=0;i<a.length&&!u;i++)u=[].slice.call(document.querySelectorAll(a[i])).find(V);"
                + "if(!u)return 'NONE';"
                + "function F(e,x){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');"
                + "if(d&&d.set)d.set.call(e,x);else e.value=x;"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "F(u," + user + ");F(p," + pass + ");"
                + "var f=p.form||u.form;"
                + "setTimeout(function(){"
                + "if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}"
                + "else{var b=document.querySelector('button,input[type=submit]');if(b)b.click();}"
                + "},120);return 'OK';})()";

        web.evaluateJavascript(js, r -> {
            if (r != null && r.contains("OK")) {
                adTried = true;
                status("Connexion 2/2…");
                timer.postDelayed(this::checkAdFailure, 7000);
            } else {
                status("Connecté");
            }
        });
    }

    private void checkAdFailure() {
        if (!adTried || failureShown || isFinishing()) return;

        web.evaluateJavascript(
                "(function(){var p=document.querySelector('input[type=password]');"
                        + "return !!(p&&p.offsetParent!==null);})()",
                r -> {
                    if ("true".equals(r)) {
                        failureShown = true;
                        status("Mot de passe AD à mettre à jour");
                        new AlertDialog.Builder(this)
                                .setTitle("Connexion AD refusée")
                                .setMessage("Ton mot de passe AD a peut-être changé. Mets-le à jour une seule fois.")
                                .setPositiveButton("Mettre à jour", (d, w) -> editAdPassword())
                                .setNegativeButton("Annuler", null)
                                .show();
                    } else {
                        status("Connecté");
                    }
                });
    }

    private void showPopupMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Accueil");
        menu.getMenu().add("Modifier le mot de passe AD");
        menu.getMenu().add("Modifier tous les identifiants");
        menu.getMenu().add("Réinitialiser la session");
        menu.getMenu().add("Effacer les identifiants");
        menu.getMenu().add("À propos");

        menu.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();

            if ("Accueil".equals(t)) {
                showHome("Prêt");
            } else if ("Modifier le mot de passe AD".equals(t)) {
                editAdPassword();
            } else if ("Modifier tous les identifiants".equals(t)) {
                editAll(false);
            } else if ("Réinitialiser la session".equals(t)) {
                resetSessionToHome();
            } else if ("Effacer les identifiants".equals(t)) {
                clearAll();
            } else if ("À propos".equals(t)) {
                showAbout();
            }
            return true;
        });

        menu.show();
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | (password
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return e;
    }

    private LinearLayout form(EditText... fields) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        for (EditText e : fields) l.addView(e);
        return l;
    }

    private void editAll(boolean first) {
        EditText bu = input("Identifiant accès 1", false);
        EditText bp = input(first
                ? "Mot de passe accès 1"
                : "Nouveau mot de passe accès 1 (vide = conserver)", true);

        EditText au = input("Login AD", false);
        EditText ap = input(first
                ? "Mot de passe AD"
                : "Nouveau mot de passe AD (vide = conserver)", true);

        bu.setText(secrets.get(CredentialStore.BASIC_USER));
        au.setText(secrets.get(CredentialStore.AD_USER));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(first ? "Configurer Plano Magic" : "Modifier les identifiants")
                .setView(form(bu, bp, au, ap))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton(first ? null : "Annuler", null)
                .create();

        d.setCancelable(!first);

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String u1 = bu.getText().toString().trim();
                    String u2 = au.getText().toString().trim();
                    String p1 = bp.getText().toString();
                    String p2 = ap.getText().toString();

                    if (u1.isEmpty() || u2.isEmpty()
                            || (first && (p1.isEmpty() || p2.isEmpty()))) {
                        Toast.makeText(this,
                                "Tous les champs requis doivent être remplis",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    secrets.put(CredentialStore.BASIC_USER, u1);
                    secrets.put(CredentialStore.AD_USER, u2);
                    if (!p1.isEmpty()) secrets.put(CredentialStore.BASIC_PASS, p1);
                    if (!p2.isEmpty()) secrets.put(CredentialStore.AD_PASS, p2);

                    d.dismiss();
                    resetAndOpen();
                }));

        d.show();
    }

    private void editAdPassword() {
        EditText p = input("Nouveau mot de passe AD", true);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Mettre à jour le mot de passe AD")
                .setView(form(p))
                .setPositiveButton("Enregistrer et reconnecter", null)
                .setNegativeButton("Annuler", null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String s = p.getText().toString();

                    if (s.isEmpty()) {
                        p.setError("Requis");
                        return;
                    }

                    secrets.put(CredentialStore.AD_PASS, s);
                    d.dismiss();
                    resetAndOpen();
                }));

        d.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("À propos de l’application")
                .setMessage("Plano Magic\\nVersion 1.2\\n\\nAccès rapide et automatisé aux applications internes.\\n\\nSignature : Younes AJBILOU")
                .setPositiveButton("OK", null)
                .show();
    }

    private void clearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Effacer les identifiants ?")
                .setMessage("Les identifiants stockés localement seront supprimés.")
                .setPositiveButton("Effacer", (d, w) -> {
                    secrets.clear();
                    clearWebSession();
                    showHome("Identifiants effacés");
                    editAll(true);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void clearWebSession() {
        basicTried = false;
        adTried = false;
        failureShown = false;
        timer.removeCallbacksAndMessages(null);

        web.stopLoading();
        web.clearCache(true);
        web.clearHistory();

        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();

        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();

        web.loadUrl("about:blank");
    }

    private void resetSessionToHome() {
        clearWebSession();
        showHome("Session réinitialisée");
        Toast.makeText(this,
                "Session réinitialisée. Aucune reconnexion automatique.",
                Toast.LENGTH_SHORT).show();
    }

    private void resetAndOpen() {
        clearWebSession();
        openPlano();
    }

    private void openPlano() {
        if (!secrets.isConfigured()) {
            showHome("Configuration requise");
            editAll(true);
            return;
        }

        basicTried = false;
        adTried = false;
        failureShown = false;
        showWeb();
        status("Ouverture…");
        web.loadUrl(URL);
    }

    private void showHome(String message) {
        homeVisible = true;
        web.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        refreshButton.setText("▶");
        refreshButton.setContentDescription("Ouvrir Plano");
        status(message);
    }

    private void showWeb() {
        homeVisible = false;
        home.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        refreshButton.setText("↻");
        refreshButton.setContentDescription("Actualiser");
    }

    private void status(String s) {
        status.setText(s);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        if (!homeVisible && web.canGoBack()) {
            web.goBack();
        } else if (!homeVisible) {
            showHome("Prêt");
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
