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
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class MainActivity extends Activity {
    private static final String PLANO_HOST = "plano.wonderbox.com";
    private static final String PLANO_URL = "https://" + PLANO_HOST + "/login/auth";
    private static final String CUSTOM_SITES = "custom_sites_v1";

    private final Handler timer = new Handler(Looper.getMainLooper());
    private CredentialStore secrets;
    private WebView web;
    private LinearLayout homeList;
    private ScrollView homeScroll;
    private TextView headerTitle, status, homeButton, refreshButton, menuButton;
    private boolean onHome = true;

    private enum Mode { NONE, PLANO, CUSTOM }
    private Mode mode = Mode.NONE;
    private SiteProfile activeSite;

    private boolean basicTried, adTried, failureShown, genericFormTried, genericBasicTried;

    static class SiteProfile {
        String id, name, url, username, password;
        boolean autoLogin;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("username", username);
            o.put("password", password);
            o.put("autoLogin", autoLogin);
            return o;
        }

        static SiteProfile fromJson(JSONObject o) {
            SiteProfile s = new SiteProfile();
            s.id = o.optString("id");
            s.name = o.optString("name");
            s.url = o.optString("url");
            s.username = o.optString("username");
            s.password = o.optString("password");
            s.autoLogin = o.optBoolean("autoLogin", false);
            return s;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        secrets = new CredentialStore(this);
        buildUi();
        configureWebView();
        showHome("Prêt");

        if (!secrets.isConfigured()) {
            editPlanoCredentials(true);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(8), dp(6));
        bar.setMinimumHeight(dp(58));
        bar.setBackgroundColor(Color.WHITE);

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.app_icon_photo);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avatarLp.setMargins(0, 0, dp(10), 0);
        avatar.setLayoutParams(avatarLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        headerTitle = new TextView(this);
        headerTitle.setText("Mes accès");
        headerTitle.setTextSize(18);
        headerTitle.setTextColor(Color.rgb(28,28,28));

        status = new TextView(this);
        status.setText("Prêt");
        status.setTextSize(12);
        status.setTextColor(Color.rgb(105,105,105));

        labels.addView(headerTitle);
        labels.addView(status);

        homeButton = topAction("⌂", 24);
        homeButton.setContentDescription("Accueil");
        homeButton.setOnClickListener(v -> showHome("Prêt"));

        refreshButton = topAction("↻", 24);
        refreshButton.setContentDescription("Actualiser");
        refreshButton.setOnClickListener(v -> {
            if (!onHome) web.reload();
        });

        menuButton = topAction("⋮", 28);
        menuButton.setContentDescription("Menu");
        menuButton.setOnClickListener(this::showPopupMenu);

        bar.addView(avatar);
        bar.addView(labels);
        bar.addView(homeButton);
        bar.addView(refreshButton);
        bar.addView(menuButton);

        View sep = new View(this);
        sep.setBackgroundColor(Color.rgb(235,235,235));
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        homeList.setPadding(dp(18), dp(20), dp(18), dp(24));
        homeScroll.addView(homeList);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));

        content.addView(web);
        content.addView(homeScroll);

        root.addView(bar);
        root.addView(sep);
        root.addView(content);
        setContentView(root);
    }

    private TextView topAction(String text, int size) {
        TextView v = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(2),0,dp(2),0);
        v.setLayoutParams(lp);
        v.setText(text);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.rgb(55,55,55));
        v.setBackground(round(Color.rgb(247,247,247), 12, Color.TRANSPARENT));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void rebuildHome() {
        homeList.removeAllViews();

        TextView title = new TextView(this);
        title.setText("Mes applications");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(25,25,25));
        homeList.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Un appui suffit. Tes identifiants restent chiffrés sur ce téléphone.");
        intro.setTextSize(14);
        intro.setTextColor(Color.rgb(100,100,100));
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1,-2);
        introLp.setMargins(0,dp(6),0,dp(20));
        intro.setLayoutParams(introLp);
        homeList.addView(intro);

        addPlanoCard();

        List<SiteProfile> sites = loadSites();
        for (SiteProfile s : sites) addCustomCard(s);

        Button add = new Button(this);
        add.setText("+ Ajouter un site");
        add.setAllCaps(false);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(52));
        addLp.setMargins(0, dp(18), 0, 0);
        add.setLayoutParams(addLp);
        add.setOnClickListener(v -> editSite(null));
        homeList.addView(add);

        TextView hint = new TextView(this);
        hint.setText("Les sites classiques peuvent être ajoutés directement ici. Les connexions SSO/MFA ou très spécifiques peuvent nécessiter un réglage dédié.");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(120,120,120));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1,-2);
        hintLp.setMargins(dp(4),dp(12),dp(4),0);
        hint.setLayoutParams(hintLp);
        homeList.addView(hint);
    }

    private void addPlanoCard() {
        LinearLayout card = cardBase();

        TextView name = cardTitle("Plano");
        TextView sub = cardSub("Connexion automatique • 2 étapes");

        LinearLayout row = buttonRow();
        Button open = smallButton("Ouvrir");
        open.setOnClickListener(v -> openPlano());

        Button settings = smallButton("Identifiants");
        settings.setOnClickListener(v -> editPlanoCredentials(false));

        row.addView(open);
        row.addView(settings);

        card.addView(name);
        card.addView(sub);
        card.addView(row);
        homeList.addView(card);
    }

    private void addCustomCard(SiteProfile s) {
        LinearLayout card = cardBase();
        TextView name = cardTitle(s.name);

        Uri u = Uri.parse(s.url);
        String host = u.getHost() == null ? s.url : u.getHost();
        TextView sub = cardSub(host + (s.autoLogin ? " • connexion auto" : " • raccourci"));

        LinearLayout row = buttonRow();
        Button open = smallButton("Ouvrir");
        open.setOnClickListener(v -> openCustom(s));

        Button edit = smallButton("Modifier");
        edit.setOnClickListener(v -> editSite(s));

        row.addView(open);
        row.addView(edit);

        card.addView(name);
        card.addView(sub);
        card.addView(row);
        homeList.addView(card);
    }

    private LinearLayout cardBase() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(16),dp(18),dp(14));
        card.setBackground(round(Color.WHITE, 18, Color.rgb(225,225,225)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,0,0,dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView cardTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(20);
        v.setTextColor(Color.rgb(25,25,25));
        return v;
    }

    private TextView cardSub(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(Color.rgb(105,105,105));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(3),0,dp(10));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        return row;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.setMargins(dp(4),0,dp(4),0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
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
                Uri u = r.getUrl();
                if (u == null || !"https".equalsIgnoreCase(u.getScheme())) {
                    Toast.makeText(MainActivity.this, "Navigation non sécurisée bloquée", Toast.LENGTH_SHORT).show();
                    return true;
                }
                if (mode == Mode.PLANO && !PLANO_HOST.equalsIgnoreCase(u.getHost())) {
                    Toast.makeText(MainActivity.this, "Navigation hors Plano bloquée", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }

            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                if (mode == Mode.PLANO) {
                    if (!PLANO_HOST.equalsIgnoreCase(host) || !secrets.isConfigured()) {
                        h.cancel();
                        return;
                    }
                    if (basicTried) {
                        h.cancel();
                        status("Accès Plano refusé");
                        editPlanoCredentials(false);
                        return;
                    }
                    basicTried = true;
                    status("Connexion Plano 1/2…");
                    h.proceed(secrets.get(CredentialStore.BASIC_USER), secrets.get(CredentialStore.BASIC_PASS));
                    return;
                }

                if (mode == Mode.CUSTOM && activeSite != null && activeSite.autoLogin) {
                    String expected = Uri.parse(activeSite.url).getHost();
                    if (expected != null && expected.equalsIgnoreCase(host)
                            && !genericBasicTried
                            && !activeSite.username.isEmpty()
                            && !activeSite.password.isEmpty()) {
                        genericBasicTried = true;
                        status("Authentification…");
                        h.proceed(activeSite.username, activeSite.password);
                        return;
                    }
                }

                h.cancel();
            }

            @Override public void onPageFinished(WebView v, String u) {
                Uri uri = Uri.parse(u);
                headerTitle.setText(uri.getHost() == null ? "Site" : uri.getHost());

                if (mode == Mode.PLANO && PLANO_HOST.equalsIgnoreCase(uri.getHost())) {
                    tryPlanoAdLogin();
                } else if (mode == Mode.CUSTOM && activeSite != null) {
                    String expected = Uri.parse(activeSite.url).getHost();
                    if (expected != null && expected.equalsIgnoreCase(uri.getHost())) {
                        tryGenericLogin();
                    }
                }
            }
        });
    }

    private void tryPlanoAdLogin() {
        if (!secrets.isConfigured() || adTried) return;

        String user = JSONObject.quote(secrets.get(CredentialStore.AD_USER));
        String pass = JSONObject.quote(secrets.get(CredentialStore.AD_PASS));
        String js = loginScript(user, pass, true);

        web.evaluateJavascript(js, r -> {
            if (r != null && r.contains("OK")) {
                adTried = true;
                status("Connexion Plano 2/2…");
                timer.postDelayed(this::checkPlanoFailure, 7000);
            } else {
                status("Connecté");
            }
        });
    }

    private void tryGenericLogin() {
        if (activeSite == null || !activeSite.autoLogin || genericFormTried) return;
        if (activeSite.username.isEmpty() || activeSite.password.isEmpty()) return;

        String user = JSONObject.quote(activeSite.username);
        String pass = JSONObject.quote(activeSite.password);
        web.evaluateJavascript(loginScript(user, pass, true), r -> {
            if (r != null && r.contains("OK")) {
                genericFormTried = true;
                status("Connexion automatique…");
            } else {
                status("Ouvert");
            }
        });
    }

    private String loginScript(String userJson, String passJson, boolean submit) {
        return "(function(){"
                + "function V(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "var p=[].slice.call(document.querySelectorAll('input[type=password]')).find(V);"
                + "if(!p)return 'NONE';"
                + "var sels=['input[autocomplete=username]','input[type=email]','input[name*=user i]',"
                + "'input[name*=login i]','input[name*=ident i]','input[type=text]'];"
                + "var u=null;for(var i=0;i<sels.length&&!u;i++)u=[].slice.call(document.querySelectorAll(sels[i])).find(V);"
                + "if(!u)return 'NONE';"
                + "function F(e,x){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');"
                + "if(d&&d.set)d.set.call(e,x);else e.value=x;"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "F(u,"+userJson+");F(p,"+passJson+");"
                + (submit
                    ? "setTimeout(function(){var f=p.form||u.form;if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}"
                      + "else{var b=[].slice.call(document.querySelectorAll('button,input[type=submit]')).find(V);if(b)b.click();}},180);"
                    : "")
                + "return 'OK';})()";
    }

    private void checkPlanoFailure() {
        if (!adTried || failureShown || isFinishing()) return;
        web.evaluateJavascript("(function(){var p=document.querySelector('input[type=password]');return !!(p&&p.offsetParent!==null);})()", r -> {
            if ("true".equals(r)) {
                failureShown = true;
                status("Mot de passe AD à mettre à jour");
                new AlertDialog.Builder(this)
                        .setTitle("Connexion AD refusée")
                        .setMessage("Ton mot de passe AD a peut-être changé.")
                        .setPositiveButton("Mettre à jour", (d,w) -> editAdPassword())
                        .setNegativeButton("Annuler", null)
                        .show();
            } else {
                status("Connecté");
            }
        });
    }

    private void openPlano() {
        if (!secrets.isConfigured()) {
            editPlanoCredentials(true);
            return;
        }
        resetFlags();
        mode = Mode.PLANO;
        activeSite = null;
        showWeb("Plano");
        status("Ouverture…");
        web.loadUrl(PLANO_URL);
    }

    private void openCustom(SiteProfile s) {
        resetFlags();
        mode = Mode.CUSTOM;
        activeSite = s;
        showWeb(s.name);
        status("Ouverture…");
        web.loadUrl(s.url);
    }

    private void showHome(String msg) {
        mode = Mode.NONE;
        activeSite = null;
        onHome = true;
        rebuildHome();
        homeScroll.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        headerTitle.setText("Mes accès");
        status(msg);
        homeButton.setVisibility(View.GONE);
        refreshButton.setVisibility(View.GONE);
    }

    private void showWeb(String title) {
        onHome = false;
        homeScroll.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        headerTitle.setText(title);
        homeButton.setVisibility(View.VISIBLE);
        refreshButton.setVisibility(View.VISIBLE);
    }

    private void showPopupMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);

        if (!onHome) m.getMenu().add("Accueil");
        m.getMenu().add("Ajouter un site");
        m.getMenu().add("Modifier le mot de passe AD");
        m.getMenu().add("Réinitialiser la session");
        m.getMenu().add("Sécurité");
        m.getMenu().add("À propos");

        m.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if ("Accueil".equals(t)) showHome("Prêt");
            else if ("Ajouter un site".equals(t)) editSite(null);
            else if ("Modifier le mot de passe AD".equals(t)) editAdPassword();
            else if ("Réinitialiser la session".equals(t)) resetSessionToHome();
            else if ("Sécurité".equals(t)) showSecurity();
            else if ("À propos".equals(t)) showAbout();
            return true;
        });

        m.show();
    }

    private void editSite(SiteProfile existing) {
        boolean edit = existing != null;
        EditText name = input("Nom du site", false);
        EditText url = input("https://exemple.com", false);
        EditText user = input("Identifiant (optionnel)", false);
        EditText pass = input(edit ? "Nouveau mot de passe (vide = conserver)" : "Mot de passe (optionnel)", true);
        CheckBox auto = new CheckBox(this);
        auto.setText("Connexion automatique si formulaire classique");

        if (edit) {
            name.setText(existing.name);
            url.setText(existing.url);
            user.setText(existing.username);
            auto.setChecked(existing.autoLogin);
        }

        LinearLayout form = form(name,url,user,pass);
        form.addView(auto);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(edit ? "Modifier le site" : "Ajouter un site")
                .setView(form)
                .setPositiveButton(edit ? "Enregistrer" : "Ajouter", null)
                .setNegativeButton("Annuler", null)
                .setNeutralButton(edit ? "Supprimer" : null, null)
                .create();

        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String n=name.getText().toString().trim();
                String raw=url.getText().toString().trim();
                String u=user.getText().toString().trim();
                String p=pass.getText().toString();

                if (n.isEmpty() || raw.isEmpty()) {
                    Toast.makeText(this,"Nom et adresse requis",Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!raw.startsWith("https://")) {
                    if (raw.startsWith("http://")) {
                        Toast.makeText(this,"Pour la sécurité, seuls les sites HTTPS sont acceptés",Toast.LENGTH_LONG).show();
                        return;
                    }
                    raw="https://"+raw;
                }

                Uri parsed=Uri.parse(raw);
                if (parsed.getHost()==null || !"https".equalsIgnoreCase(parsed.getScheme())) {
                    Toast.makeText(this,"Adresse HTTPS invalide",Toast.LENGTH_SHORT).show();
                    return;
                }

                List<SiteProfile> sites=loadSites();
                SiteProfile s=edit ? findById(sites, existing.id) : new SiteProfile();
                if (s==null) s=new SiteProfile();
                if (!edit) s.id=UUID.randomUUID().toString();
                s.name=n;
                s.url=raw;
                s.username=u;
                if (!p.isEmpty() || !edit) s.password=p;
                s.autoLogin=auto.isChecked() && !s.username.isEmpty() && !s.password.isEmpty();

                if (!edit) sites.add(s);
                saveSites(sites);
                d.dismiss();
                showHome("Site enregistré");
            });

            if (edit) {
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Supprimer " + existing.name + " ?")
                            .setPositiveButton("Supprimer",(a,b)->{
                                List<SiteProfile> sites=loadSites();
                                for (int i=sites.size()-1;i>=0;i--) {
                                    if (existing.id.equals(sites.get(i).id)) sites.remove(i);
                                }
                                saveSites(sites);
                                d.dismiss();
                                showHome("Site supprimé");
                            })
                            .setNegativeButton("Annuler",null)
                            .show();
                });
            }
        });

        d.show();
    }

    private SiteProfile findById(List<SiteProfile> sites, String id) {
        for (SiteProfile s:sites) if (id.equals(s.id)) return s;
        return null;
    }

    private List<SiteProfile> loadSites() {
        List<SiteProfile> out = new ArrayList<>();
        try {
            String raw = secrets.get(CUSTOM_SITES);
            if (raw.isEmpty()) return out;
            JSONArray a = new JSONArray(raw);
            for (int i=0;i<a.length();i++) out.add(SiteProfile.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    private void saveSites(List<SiteProfile> sites) {
        try {
            JSONArray a = new JSONArray();
            for (SiteProfile s:sites) a.put(s.toJson());
            secrets.put(CUSTOM_SITES, a.toString());
        } catch (Exception e) {
            Toast.makeText(this,"Impossible d’enregistrer le site",Toast.LENGTH_SHORT).show();
        }
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT |
                (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return e;
    }

    private LinearLayout form(View... fields) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20),dp(8),dp(20),0);
        for (View v:fields) l.addView(v);
        return l;
    }

    private void editPlanoCredentials(boolean first) {
        EditText bu=input("Identifiant accès 1",false);
        EditText bp=input(first?"Mot de passe accès 1":"Nouveau mot de passe accès 1 (vide = conserver)",true);
        EditText au=input("Login AD",false);
        EditText ap=input(first?"Mot de passe AD":"Nouveau mot de passe AD (vide = conserver)",true);

        bu.setText(secrets.get(CredentialStore.BASIC_USER));
        au.setText(secrets.get(CredentialStore.AD_USER));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(first ? "Configurer Plano" : "Identifiants Plano")
                .setView(form(bu,bp,au,ap))
                .setPositiveButton("Enregistrer",null)
                .setNegativeButton(first ? null : "Annuler",null)
                .create();

        d.setCancelable(!first);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u1=bu.getText().toString().trim();
            String p1=bp.getText().toString();
            String u2=au.getText().toString().trim();
            String p2=ap.getText().toString();

            if (u1.isEmpty() || u2.isEmpty() || (first && (p1.isEmpty() || p2.isEmpty()))) {
                Toast.makeText(this,"Tous les champs requis doivent être remplis",Toast.LENGTH_SHORT).show();
                return;
            }

            secrets.put(CredentialStore.BASIC_USER,u1);
            secrets.put(CredentialStore.AD_USER,u2);
            if (!p1.isEmpty()) secrets.put(CredentialStore.BASIC_PASS,p1);
            if (!p2.isEmpty()) secrets.put(CredentialStore.AD_PASS,p2);

            d.dismiss();
            showHome("Plano configuré");
        }));
        d.show();
    }

    private void editAdPassword() {
        if (secrets.get(CredentialStore.AD_USER).isEmpty()) {
            editPlanoCredentials(true);
            return;
        }

        EditText p=input("Nouveau mot de passe AD",true);
        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle("Mettre à jour le mot de passe AD")
                .setView(form(p))
                .setPositiveButton("Enregistrer",null)
                .setNegativeButton("Annuler",null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value=p.getText().toString();
            if (value.isEmpty()) {
                p.setError("Requis");
                return;
            }
            secrets.put(CredentialStore.AD_PASS,value);
            d.dismiss();
            clearWebSession();
            showHome("Mot de passe AD mis à jour");
        }));
        d.show();
    }

    private void showSecurity() {
        new AlertDialog.Builder(this)
                .setTitle("Sécurité")
                .setMessage("• Mots de passe chiffrés localement en AES-256-GCM\n"
                        + "• Clé conservée dans Android Keystore\n"
                        + "• Aucun mot de passe enregistré dans Chrome/Edge\n"
                        + "• Aucun mot de passe envoyé sur GitHub\n"
                        + "• Connexions automatiques uniquement sur HTTPS\n\n"
                        + "Comme toute application, les identifiants sont brièvement déchiffrés en mémoire au moment de la connexion. Un téléphone compromis/rooté peut réduire la protection.")
                .setPositiveButton("OK",null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("À propos de l’application")
                .setMessage("Plano Magic\nVersion 1.3\n\n"
                        + "Accès rapide aux applications internes et sites ajoutés manuellement.\n\n"
                        + "Signature : Younes AJBILOU")
                .setPositiveButton("OK",null)
                .show();
    }

    private void resetSessionToHome() {
        clearWebSession();
        showHome("Session réinitialisée");
        Toast.makeText(this,"Session effacée. Aucune reconnexion automatique.",Toast.LENGTH_SHORT).show();
    }

    private void clearWebSession() {
        resetFlags();
        timer.removeCallbacksAndMessages(null);
        web.stopLoading();
        web.clearCache(true);
        web.clearHistory();
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        web.loadUrl("about:blank");
    }

    private void resetFlags() {
        basicTried=false;
        adTried=false;
        failureShown=false;
        genericFormTried=false;
        genericBasicTried=false;
    }

    private void status(String s) { status.setText(s); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (!onHome && web.canGoBack()) web.goBack();
        else if (!onHome) showHome("Prêt");
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        if (web!=null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
