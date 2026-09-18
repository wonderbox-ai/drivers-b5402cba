package com.local.planomagic;

import android.app.*;
import android.content.SharedPreferences;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.hardware.biometrics.BiometricPrompt;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String PLANO_HOST = "plano.wonderbox.com";
    private static final String PLANO_URL = "https://" + PLANO_HOST + "/login/auth";
    private static final String CUSTOM_SITES = "custom_sites_v2";
    private static final String SETTINGS = "plano_magic_settings";
    private static final String KEY_DARK = "dark_mode";
    private static final String APP_NAME = "Wonder Apps";
    private static final String KEY_ONBOARDED = "onboarding_complete";
    private static final int REQ_EXPORT_BACKUP = 5101;
    private static final int REQ_IMPORT_BACKUP = 5102;

    private final Handler timer = new Handler(Looper.getMainLooper());

    private CredentialStore secrets;
    private PinManager pinManager;
    private WebView web;
    private LinearLayout root, homeList, topBar;
    private ScrollView homeScroll;
    private TextView headerTitle, status, homeButton, refreshButton, menuButton;
    private ImageView avatar;
    private View separator;

    private boolean darkMode;
    private boolean unlocked = false;
    private boolean unlockFallbackStarted = false;
    private int pinAttempts = 0;
    private String pendingBackupPassword;
    private boolean importFromOnboarding = false;
    private CancellationSignal biometricCancellation;
    private boolean onHome = true;
    private boolean analysisMode = false;
    private int analysisPass = 0;

    private enum Mode { NONE, PLANO, CUSTOM }
    private Mode mode = Mode.NONE;
    private SiteProfile activeSite;

    private boolean basicTried, adTried, failureShown, genericFormTried, genericBasicTried;

    static class SiteProfile {
        String id = "";
        String name = "";
        String url = "";
        String username = "";
        String password = "";
        String authType = "PENDING";
        String loginHost = "";

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("username", username);
            o.put("password", password);
            o.put("authType", authType);
            o.put("loginHost", loginHost);
            return o;
        }

        static SiteProfile fromJson(JSONObject o) {
            SiteProfile s = new SiteProfile();
            s.id = o.optString("id", UUID.randomUUID().toString());
            s.name = o.optString("name", "Site");
            s.url = o.optString("url", "");
            s.username = o.optString("username", "");
            s.password = o.optString("password", "");
            s.authType = o.optString("authType", "PENDING");
            s.loginHost = o.optString("loginHost", "");
            if (o.has("autoLogin") && "PENDING".equals(s.authType) && o.optBoolean("autoLogin", false)) {
                s.authType = "FORM";
            }
            return s;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        SharedPreferences settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        darkMode = settings.getBoolean(KEY_DARK, false);
        setTheme(darkMode
                ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);

        secrets = new CredentialStore(this);
        pinManager = new PinManager(this);

        buildUi();
        configureWebView();
        updateSystemBars();

        boolean onboarded = settings.getBoolean(KEY_ONBOARDED, false);
        if (!onboarded) {
            unlocked = true;
            root.setVisibility(View.VISIBLE);
            showHome("Bienvenue");
            timer.postDelayed(this::startOnboarding, 250);
        } else {
            root.setVisibility(View.INVISIBLE);
            requestUnlock();
        }
    }

    private void requestUnlock() {
        String mode = pinManager.getMode();

        if (PinManager.MODE_DISABLED.equals(mode)) {
            unlockApp();
            return;
        }

        if (PinManager.MODE_PIN.equals(mode) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showPinUnlockDialog();
            return;
        }

        if (!pinManager.hasPin()) {
            showPinSetup(() -> requestBiometricUnlock());
            return;
        }

        requestBiometricUnlock();
    }

    private void requestBiometricUnlock() {
        root.setVisibility(View.INVISIBLE);
        unlockFallbackStarted = false;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showPinUnlockDialog();
            return;
        }

        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                    .setTitle("Déverrouiller " + APP_NAME)
                    .setSubtitle("Empreinte / biométrie")
                    .setNegativeButton(
                            "Utiliser le code PIN",
                            getMainExecutor(),
                            (dialog, which) -> showPinUnlockDialog());

            biometricCancellation = new CancellationSignal();

            builder.build().authenticate(
                    biometricCancellation,
                    getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            unlockApp();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            if (!unlocked && !unlockFallbackStarted) {
                                showPinUnlockDialog();
                            }
                        }
                    });
        } catch (Exception e) {
            showPinUnlockDialog();
        }
    }

    private void showPinUnlockDialog() {
        if (unlocked || unlockFallbackStarted) return;
        unlockFallbackStarted = true;

        if (!pinManager.hasPin()) {
            showPinSetup(this::unlockApp);
            return;
        }

        final EditText pin = input("Code PIN", true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Code PIN")
                .setMessage("Saisis le code PIN de Wonder Apps.")
                .setView(form(pin))
                .setPositiveButton("Déverrouiller", null)
                .setNegativeButton("Fermer", (d,w) -> finishAndRemoveTask())
                .create();

        dialog.setCancelable(false);
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = pin.getText().toString();
            if (pinManager.verifyPin(value)) {
                pinAttempts = 0;
                dialog.dismiss();
                unlockApp();
                return;
            }

            pinAttempts++;
            pin.setText("");
            pin.setError("Code PIN incorrect");

            if (pinAttempts >= 5) {
                dialog.dismiss();
                Toast.makeText(this, "Trop de tentatives. Wonder Apps va se fermer.", Toast.LENGTH_LONG).show();
                timer.postDelayed(this::finishAndRemoveTask, 700);
            }
        }));
        dialog.show();
    }

    private void showPinSetup(Runnable after) {
        final EditText pin1 = input("Nouveau code PIN (4 à 8 chiffres)", true);
        final EditText pin2 = input("Confirmer le code PIN", true);
        pin1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Créer un code PIN")
                .setMessage("Ce code sert de secours à la biométrie et peut aussi devenir la méthode principale de déverrouillage.")
                .setView(form(pin1, pin2))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = pin1.getText().toString();
            String b = pin2.getText().toString();

            if (!a.matches("\\d{4,8}")) {
                pin1.setError("4 à 8 chiffres");
                return;
            }
            if (!a.equals(b)) {
                pin2.setError("Les codes ne correspondent pas");
                return;
            }

            pinManager.setPin(a);
            dialog.dismiss();
            if (after != null) after.run();
        }));
        dialog.show();
    }

    private void unlockApp() {
        if (unlocked || isFinishing()) return;
        unlocked = true;
        unlockFallbackStarted = false;
        root.setVisibility(View.VISIBLE);
        showHome("Déverrouillé");
    }

    private void updateSystemBars() {
        Window window = getWindow();
        int barColor = darkMode ? Color.rgb(18,18,18) : Color.WHITE;
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(barColor);

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();

        if (!darkMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }

        decor.setSystemUiVisibility(flags);
    }

    private int bg() { return darkMode ? Color.rgb(18,18,18) : Color.rgb(250,250,250); }
    private int surface() { return darkMode ? Color.rgb(32,32,32) : Color.WHITE; }
    private int surface2() { return darkMode ? Color.rgb(43,43,43) : Color.rgb(247,247,247); }
    private int primary() { return darkMode ? Color.rgb(240,240,240) : Color.rgb(28,28,28); }
    private int secondary() { return darkMode ? Color.rgb(175,175,175) : Color.rgb(105,105,105); }
    private int border() { return darkMode ? Color.rgb(68,68,68) : Color.rgb(225,225,225); }
    private int accent() { return darkMode ? Color.rgb(120,170,255) : Color.rgb(45,105,200); }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(6), dp(8), dp(6));
        topBar.setMinimumHeight(dp(58));
        topBar.setBackgroundColor(surface());

        avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.app_icon_photo);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avatarLp.setMargins(0, 0, dp(10), 0);
        avatar.setLayoutParams(avatarLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        headerTitle = new TextView(this);
        headerTitle.setText("Mes accès Wonderbox");
        headerTitle.setTextSize(18);
        headerTitle.setTextColor(primary());

        status = new TextView(this);
        status.setText("Prêt");
        status.setTextSize(12);
        status.setTextColor(secondary());

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

        menuButton = topAction("⚙", 22);
        menuButton.setContentDescription("Réglages");
        menuButton.setOnClickListener(v -> showSettingsCenter());

        topBar.addView(avatar);
        topBar.addView(labels);
        topBar.addView(homeButton);
        topBar.addView(refreshButton);
        topBar.addView(menuButton);

        separator = new View(this);
        separator.setBackgroundColor(border());
        separator.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeScroll.setBackgroundColor(bg());

        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        homeList.setPadding(dp(18), dp(20), dp(18), dp(24));
        homeScroll.addView(homeList);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        content.addView(web);
        content.addView(homeScroll);

        root.addView(topBar);
        root.addView(separator);
        root.addView(content);
        setContentView(root);
        updateSystemBars();
    }

    private TextView topAction(String text, int size) {
        TextView v = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(2), 0, dp(2), 0);
        v.setLayoutParams(lp);
        v.setText(text);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(primary());
        v.setBackground(round(surface2(), 12, Color.TRANSPARENT));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void rebuildHome() {
        homeList.removeAllViews();

        TextView title = new TextView(this);
        title.setText("Mes applis Wonderbox");
        title.setTextSize(26);
        title.setTextColor(primary());
        homeList.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Ajoute simplement l’adresse d’un site : l’application analyse automatiquement sa méthode de connexion.");
        intro.setTextSize(14);
        intro.setTextColor(secondary());
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.setMargins(0, dp(6), 0, dp(20));
        intro.setLayoutParams(introLp);
        homeList.addView(intro);

        addPlanoCard();

        for (SiteProfile s : loadSites()) {
            addCustomCard(s);
        }

        Button add = new Button(this);
        add.setText("+ Ajouter un site");
        add.setAllCaps(false);
        add.setTextColor(darkMode ? Color.WHITE : Color.rgb(20,20,20));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(54));
        addLp.setMargins(0, dp(10), 0, 0);
        add.setLayoutParams(addLp);
        add.setOnClickListener(v -> editSiteAddress(null));
        homeList.addView(add);

        TextView hint = new TextView(this);
        hint.setText("Analyse sans mot de passe : HTTP Basic, formulaire classique, SSO/MFA ou simple raccourci. Les identifiants ne sont demandés qu’après détection si nécessaire.");
        hint.setTextSize(12);
        hint.setTextColor(secondary());
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(dp(4), dp(12), dp(4), 0);
        hint.setLayoutParams(hintLp);
        homeList.addView(hint);
    }

    private void addPlanoCard() {
        LinearLayout card = cardBase();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = cardTitle("Plano");
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView badge = badge("AUTO", accent());
        top.addView(name);
        top.addView(badge);

        TextView sub = cardSub("Connexion automatique sécurisée • 2 étapes");

        LinearLayout row = buttonRow();
        Button open = smallButton("Ouvrir");
        open.setOnClickListener(v -> openPlano());

        Button settings = smallButton("Identifiants");
        settings.setOnClickListener(v -> editPlanoCredentials(false));

        row.addView(open);
        row.addView(settings);

        card.addView(top);
        card.addView(sub);
        card.addView(row);
        homeList.addView(card);
    }

    private void addCustomCard(SiteProfile s) {
        LinearLayout card = cardBase();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = cardTitle(s.name);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        String type = s.authType == null ? "PENDING" : s.authType;
        TextView badge = badge(authShort(type), authColor(type));

        top.addView(name);
        top.addView(badge);

        Uri u = Uri.parse(s.url);
        String host = u.getHost() == null ? s.url : u.getHost();

        TextView sub = cardSub(host + " • " + authLabel(type));

        LinearLayout row = buttonRow();

        Button open = smallButton("Ouvrir");
        open.setOnClickListener(v -> {
            if ("PENDING".equals(type) || "UNKNOWN".equals(type)) analyzeSite(s);
            else openCustom(s);
        });

        Button edit = smallButton("Gérer");
        edit.setOnClickListener(v -> showSiteActions(s));

        row.addView(open);
        row.addView(edit);

        card.addView(top);
        card.addView(sub);
        card.addView(row);
        homeList.addView(card);
    }

    private TextView badge(String text, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(11);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(4), dp(8), dp(4));
        v.setBackground(round(surface2(), 20, color));
        return v;
    }

    private String authShort(String type) {
        switch (type) {
            case "FORM": return "FORM";
            case "BASIC": return "BASIC";
            case "SSO": return "SSO";
            case "MFA": return "MFA";
            case "NONE": return "WEB";
            default: return "ANALYSE";
        }
    }

    private String authLabel(String type) {
        switch (type) {
            case "FORM": return "formulaire détecté";
            case "BASIC": return "HTTP Basic détecté";
            case "SSO": return "SSO détecté";
            case "MFA": return "MFA détecté";
            case "NONE": return "aucune connexion détectée";
            case "UNKNOWN": return "méthode non reconnue";
            default: return "analyse requise";
        }
    }

    private int authColor(String type) {
        if ("FORM".equals(type) || "BASIC".equals(type)) return Color.rgb(45,160,90);
        if ("SSO".equals(type) || "MFA".equals(type)) return Color.rgb(220,150,35);
        if ("UNKNOWN".equals(type)) return Color.rgb(210,80,70);
        return accent();
    }

    private LinearLayout cardBase() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(round(surface(), 18, border()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView cardTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(20);
        v.setTextColor(primary());
        return v;
    }

    private TextView cardSub(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(secondary());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(10));
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
        b.setTextColor(darkMode ? Color.WHITE : Color.rgb(20,20,20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
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
                if (analysisMode && activeSite != null) {
                    h.cancel();
                    activeSite.loginHost = host == null ? "" : host;
                    finishAnalysis(activeSite, "BASIC");
                    return;
                }

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
                    h.proceed(
                            secrets.get(CredentialStore.BASIC_USER),
                            secrets.get(CredentialStore.BASIC_PASS));
                    return;
                }

                if (mode == Mode.CUSTOM && activeSite != null && "BASIC".equals(activeSite.authType)
                        && sameConfiguredHost(activeSite, host)
                        && !genericBasicTried
                        && !activeSite.username.isEmpty()
                        && !activeSite.password.isEmpty()) {
                    genericBasicTried = true;
                    status("Authentification…");
                    h.proceed(activeSite.username, activeSite.password);
                    return;
                }

                h.cancel();
            }

            @Override public void onPageFinished(WebView v, String u) {
                Uri uri = Uri.parse(u);

                if (analysisMode && activeSite != null) {
                    timer.postDelayed(() -> analyzeCurrentPage(activeSite, uri), 700);
                    return;
                }

                headerTitle.setText(uri.getHost() == null ? "Site" : uri.getHost());

                if (mode == Mode.PLANO && PLANO_HOST.equalsIgnoreCase(uri.getHost())) {
                    tryPlanoAdLogin();
                } else if (mode == Mode.CUSTOM && activeSite != null
                        && sameConfiguredHost(activeSite, uri.getHost())
                        && "FORM".equals(activeSite.authType)) {
                    tryGenericLogin();
                }
            }
        });
    }

    private boolean sameConfiguredHost(SiteProfile site, String host) {
        if (site == null || host == null) return false;
        String allowed = site.loginHost == null || site.loginHost.isEmpty()
                ? Uri.parse(site.url).getHost()
                : site.loginHost;
        return allowed != null && allowed.equalsIgnoreCase(host);
    }

    private boolean commonSsoHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("login.microsoftonline.com")
                || h.equals("accounts.google.com")
                || h.contains("okta")
                || h.contains("auth0")
                || h.contains("onelogin")
                || h.contains("pingidentity");
    }

    private void analyzeSite(SiteProfile site) {
        clearWebSession();

        activeSite = site;
        mode = Mode.CUSTOM;
        analysisMode = true;
        analysisPass = 0;

        showWeb("Analyse • " + site.name);
        status("Analyse de la connexion…");

        web.loadUrl(site.url);

        timer.postDelayed(() -> {
            if (analysisMode && activeSite != null && activeSite.id.equals(site.id)) {
                analyzeCurrentPage(site, Uri.parse(web.getUrl() == null ? site.url : web.getUrl()));
            }
        }, 4500);
    }

    private void analyzeCurrentPage(SiteProfile site, Uri current) {
        if (!analysisMode || site == null || activeSite == null || !site.id.equals(activeSite.id)) return;

        String host = current == null ? null : current.getHost();

        if (commonSsoHost(host)) {
            site.loginHost = "";
            finishAnalysis(site, "SSO");
            return;
        }

        String path = current == null ? "" : String.valueOf(current.getPath()).toLowerCase(Locale.ROOT);
        if (path.contains("saml") || path.contains("oauth") || path.contains("authorize")) {
            site.loginHost = "";
            finishAnalysis(site, "SSO");
            return;
        }

        String js = "(function(){"
                + "function V(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "var p=[].slice.call(document.querySelectorAll('input[type=password]')).filter(V).length;"
                + "var u=[].slice.call(document.querySelectorAll('input[autocomplete=username],input[type=email],input[name*=user i],input[name*=login i],input[name*=ident i]')).filter(V).length;"
                + "var otp=[].slice.call(document.querySelectorAll('input[autocomplete=one-time-code],input[name*=otp i],input[name*=code i]')).filter(V).length;"
                + "var txt=(document.body?document.body.innerText:'').toLowerCase();"
                + "var sso=(txt.indexOf('sign in with microsoft')>=0||txt.indexOf('continuer avec microsoft')>=0||txt.indexOf('sign in with google')>=0||txt.indexOf('single sign-on')>=0||txt.indexOf('sso')>=0);"
                + "return JSON.stringify({p:p,u:u,otp:otp,sso:sso});})()";

        web.evaluateJavascript(js, result -> {
            if (!analysisMode || activeSite == null || !site.id.equals(activeSite.id)) return;

            try {
                String decoded = result;
                if (decoded != null && decoded.length() >= 2 && decoded.startsWith("\"") && decoded.endsWith("\"")) {
                    decoded = new JSONArray("[" + decoded + "]").getString(0);
                }

                JSONObject o = new JSONObject(decoded == null ? "{}" : decoded);
                int passwords = o.optInt("p", 0);
                int users = o.optInt("u", 0);
                int otp = o.optInt("otp", 0);
                boolean sso = o.optBoolean("sso", false);

                if (otp > 0) {
                    site.loginHost = "";
                    finishAnalysis(site, "MFA");
                } else if (passwords > 0) {
                    site.loginHost = host == null ? "" : host;
                    finishAnalysis(site, "FORM");
                } else if (sso) {
                    site.loginHost = "";
                    finishAnalysis(site, "SSO");
                } else {
                    analysisPass++;
                    if (analysisPass >= 2) {
                        finishAnalysis(site, "NONE");
                    } else {
                        timer.postDelayed(() -> analyzeCurrentPage(site,
                                Uri.parse(web.getUrl() == null ? site.url : web.getUrl())), 2200);
                    }
                }
            } catch (Exception e) {
                analysisPass++;
                if (analysisPass >= 2) finishAnalysis(site, "UNKNOWN");
            }
        });
    }

    private void finishAnalysis(SiteProfile site, String detected) {
        if (!analysisMode) return;

        analysisMode = false;
        timer.removeCallbacksAndMessages(null);

        List<SiteProfile> sites = loadSites();
        SiteProfile saved = findById(sites, site.id);
        if (saved == null) {
            saved = site;
            sites.add(saved);
        }

        saved.authType = detected;
        saveSites(sites);
        activeSite = saved;

        web.stopLoading();
        web.loadUrl("about:blank");
        showHome("Analyse terminée");

        final SiteProfile resultSite = saved;
        String title;
        String message;
        boolean credsUseful = false;

        switch (detected) {
            case "BASIC":
                title = "HTTP Basic détecté";
                message = "Le site demande une authentification native"
                        + (saved.loginHost.isEmpty() ? "" : " sur " + saved.loginHost)
                        + ". Wonder Apps peut la remplir automatiquement.";
                credsUseful = true;
                break;
            case "FORM":
                title = "Formulaire détecté";
                message = "Un formulaire identifiant / mot de passe a été détecté"
                        + (saved.loginHost.isEmpty() ? "" : " sur " + saved.loginHost)
                        + ". La connexion automatique est compatible.";
                credsUseful = true;
                break;
            case "SSO":
                title = "SSO détecté";
                message = "Le site semble utiliser Microsoft, Google, Okta ou un autre SSO. L’authentification restera interactive pour éviter de contourner MFA/SSO.";
                break;
            case "MFA":
                title = "MFA détecté";
                message = "Un second facteur a été détecté. L’application ouvrira le site mais ne tentera pas d’automatiser le code MFA.";
                break;
            case "NONE":
                title = "Aucune connexion détectée";
                message = "Le site semble directement accessible. Il sera utilisé comme raccourci sécurisé.";
                break;
            default:
                title = "Analyse incertaine";
                message = "La méthode de connexion n’a pas pu être identifiée avec suffisamment de certitude. Tu peux quand même ouvrir le site ou relancer l’analyse.";
                break;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Fermer", null);

        if (credsUseful) {
            b.setPositiveButton("Configurer les identifiants", (d,w) -> editSiteCredentials(resultSite));
        } else {
            b.setPositiveButton("Ouvrir", (d,w) -> openCustom(resultSite));
        }

        b.show();
    }

    private void tryPlanoAdLogin() {
        if (!secrets.isConfigured() || adTried) return;

        String user = JSONObject.quote(secrets.get(CredentialStore.AD_USER));
        String pass = JSONObject.quote(secrets.get(CredentialStore.AD_PASS));

        web.evaluateJavascript(loginScript(user, pass), r -> {
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
        if (activeSite == null || genericFormTried) return;
        if (!"FORM".equals(activeSite.authType)) return;
        if (activeSite.username.isEmpty() || activeSite.password.isEmpty()) return;

        String user = JSONObject.quote(activeSite.username);
        String pass = JSONObject.quote(activeSite.password);

        web.evaluateJavascript(loginScript(user, pass), r -> {
            if (r != null && r.contains("OK")) {
                genericFormTried = true;
                status("Connexion automatique…");
            } else {
                status("Ouvert");
            }
        });
    }

    private String loginScript(String userJson, String passJson) {
        return "(function(){"
                + "function V(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "var p=[].slice.call(document.querySelectorAll('input[type=password]')).find(V);"
                + "if(!p)return 'NONE';"
                + "var sels=['input[autocomplete=username]','input[type=email]','input[name*=user i]','input[name*=login i]','input[name*=ident i]','input[type=text]'];"
                + "var u=null;for(var i=0;i<sels.length&&!u;i++)u=[].slice.call(document.querySelectorAll(sels[i])).find(V);"
                + "if(!u)return 'NONE';"
                + "function F(e,x){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');"
                + "if(d&&d.set)d.set.call(e,x);else e.value=x;"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "F(u," + userJson + ");F(p," + passJson + ");"
                + "setTimeout(function(){var f=p.form||u.form;"
                + "if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}"
                + "else{var b=[].slice.call(document.querySelectorAll('button,input[type=submit]')).find(V);if(b)b.click();}},180);"
                + "return 'OK';})()";
    }

    private void checkPlanoFailure() {
        if (!adTried || failureShown || isFinishing()) return;

        web.evaluateJavascript(
                "(function(){var p=document.querySelector('input[type=password]');return !!(p&&p.offsetParent!==null);})()",
                r -> {
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

        clearTransientState();
        mode = Mode.PLANO;
        activeSite = null;
        showWeb("Plano");
        status("Ouverture…");
        web.loadUrl(PLANO_URL);
    }

    private void openCustom(SiteProfile s) {
        if (s == null) return;

        if ("PENDING".equals(s.authType) || "UNKNOWN".equals(s.authType)) {
            analyzeSite(s);
            return;
        }

        clearTransientState();
        mode = Mode.CUSTOM;
        activeSite = s;
        showWeb(s.name);
        status("Ouverture…");
        web.loadUrl(s.url);
    }

    private void showHome(String msg) {
        analysisMode = false;
        mode = Mode.NONE;
        activeSite = null;
        onHome = true;

        rebuildHome();

        homeScroll.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        headerTitle.setText("Mes accès Wonderbox");
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
        m.getMenu().add("Apparence");
        m.getMenu().add("Sécurité");
        m.getMenu().add("À propos");

        m.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();

            if ("Accueil".equals(t)) showHome("Prêt");
            else if ("Ajouter un site".equals(t)) editSiteAddress(null);
            else if ("Modifier le mot de passe AD".equals(t)) editAdPassword();
            else if ("Réinitialiser la session".equals(t)) resetSessionToHome();
            else if ("Apparence".equals(t)) showAppearance();
            else if ("Sécurité".equals(t)) showSecurity();
            else if ("À propos".equals(t)) showAbout();

            return true;
        });

        m.show();
    }

    private void showAppearance() {
        String[] choices = {"Classique", "Sombre"};
        int checked = darkMode ? 1 : 0;

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Apparence")
                .setSingleChoiceItems(choices, checked, null)
                .setPositiveButton("Appliquer", null)
                .setNegativeButton("Annuler", null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selected = d.getListView().getCheckedItemPosition();
            boolean nextDark = selected == 1;

            getSharedPreferences(SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DARK, nextDark)
                    .apply();

            d.dismiss();

            if (nextDark != darkMode) recreate();
        }));

        d.show();
    }

    private void editSiteAddress(SiteProfile existing) {
        boolean edit = existing != null;

        EditText name = input("Nom du site", false);
        EditText url = input("https://exemple.com", false);

        if (edit) {
            name.setText(existing.name);
            url.setText(existing.url);
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(edit ? "Modifier le site" : "Ajouter un site")
                .setMessage(edit
                        ? "Si l’adresse change, une nouvelle analyse sera lancée."
                        : "Tu n’as besoin de connaître aucun type d’authentification. L’analyse se fait automatiquement.")
                .setView(form(name, url))
                .setPositiveButton(edit ? "Enregistrer" : "Enregistrer et analyser", null)
                .setNegativeButton("Annuler", null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String raw = url.getText().toString().trim();

            if (n.isEmpty() || raw.isEmpty()) {
                Toast.makeText(this, "Nom et adresse requis", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!raw.startsWith("https://")) {
                if (raw.startsWith("http://")) {
                    Toast.makeText(this, "Seuls les sites HTTPS sont acceptés", Toast.LENGTH_LONG).show();
                    return;
                }
                raw = "https://" + raw;
            }

            Uri parsed = Uri.parse(raw);

            if (parsed.getHost() == null || !"https".equalsIgnoreCase(parsed.getScheme())) {
                Toast.makeText(this, "Adresse HTTPS invalide", Toast.LENGTH_SHORT).show();
                return;
            }

            List<SiteProfile> sites = loadSites();
            SiteProfile s;

            if (edit) {
                s = findById(sites, existing.id);
                if (s == null) s = existing;

                boolean urlChanged = !raw.equalsIgnoreCase(s.url);
                s.name = n;
                s.url = raw;

                if (urlChanged) {
                    s.authType = "PENDING";
                    s.loginHost = "";
                    s.username = "";
                    s.password = "";
                }
            } else {
                s = new SiteProfile();
                s.id = UUID.randomUUID().toString();
                s.name = n;
                s.url = raw;
                s.authType = "PENDING";
                sites.add(s);
            }

            saveSites(sites);
            d.dismiss();

            analyzeSite(s);
        }));

        d.show();
    }

    private void showSiteActions(SiteProfile site) {
        String[] actions = {
                "Modifier le nom / l’adresse",
                "Identifiants",
                "Ré-analyser la connexion",
                "Supprimer"
        };

        new AlertDialog.Builder(this)
                .setTitle(site.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) editSiteAddress(site);
                    else if (which == 1) editSiteCredentials(site);
                    else if (which == 2) analyzeSite(site);
                    else confirmDeleteSite(site);
                })
                .show();
    }

    private void editSiteCredentials(SiteProfile site) {
        if (site == null) return;

        if (!("FORM".equals(site.authType) || "BASIC".equals(site.authType))) {
            new AlertDialog.Builder(this)
                    .setTitle("Identifiants non nécessaires")
                    .setMessage("La méthode détectée est : " + authLabel(site.authType)
                            + ". L’application ne tentera pas de contourner un SSO ou un MFA.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        EditText user = input("Identifiant", false);
        EditText pass = input(site.password.isEmpty()
                ? "Mot de passe"
                : "Nouveau mot de passe (vide = conserver)", true);

        user.setText(site.username);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Identifiants • " + site.name)
                .setView(form(user, pass))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = user.getText().toString().trim();
            String p = pass.getText().toString();

            if (u.isEmpty() || (site.password.isEmpty() && p.isEmpty())) {
                Toast.makeText(this, "Identifiant et mot de passe requis", Toast.LENGTH_SHORT).show();
                return;
            }

            List<SiteProfile> sites = loadSites();
            SiteProfile saved = findById(sites, site.id);

            if (saved == null) {
                saved = site;
                sites.add(saved);
            }

            saved.username = u;
            if (!p.isEmpty()) saved.password = p;

            saveSites(sites);
            d.dismiss();
            showHome("Identifiants enregistrés");
        }));

        d.show();
    }

    private void confirmDeleteSite(SiteProfile site) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer " + site.name + " ?")
                .setMessage("Le site et ses identifiants chiffrés seront supprimés de ce téléphone.")
                .setPositiveButton("Supprimer", (d,w) -> {
                    List<SiteProfile> sites = loadSites();

                    for (int i = sites.size() - 1; i >= 0; i--) {
                        if (site.id.equals(sites.get(i).id)) sites.remove(i);
                    }

                    saveSites(sites);
                    showHome("Site supprimé");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private SiteProfile findById(List<SiteProfile> sites, String id) {
        if (id == null) return null;
        for (SiteProfile s : sites) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    private List<SiteProfile> loadSites() {
        List<SiteProfile> out = new ArrayList<>();

        try {
            String raw = secrets.get(CUSTOM_SITES);

            if (raw.isEmpty()) {
                String old = secrets.get("custom_sites_v1");
                if (!old.isEmpty()) raw = old;
            }

            if (raw.isEmpty()) return out;

            JSONArray a = new JSONArray(raw);

            for (int i = 0; i < a.length(); i++) {
                out.add(SiteProfile.fromJson(a.getJSONObject(i)));
            }
        } catch (Exception ignored) {}

        return out;
    }

    private void saveSites(List<SiteProfile> sites) {
        try {
            JSONArray a = new JSONArray();

            for (SiteProfile s : sites) {
                a.put(s.toJson());
            }

            secrets.put(CUSTOM_SITES, a.toString());
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d’enregistrer le site", Toast.LENGTH_SHORT).show();
        }
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(primary());
        e.setHintTextColor(secondary());
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | (password
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return e;
    }

    private LinearLayout form(View... fields) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);

        for (View v : fields) l.addView(v);

        return l;
    }

    private void editPlanoCredentials(boolean first) {
        EditText bu = input("Identifiant accès 1", false);
        EditText bp = input(first ? "Mot de passe accès 1" : "Nouveau mot de passe accès 1 (vide = conserver)", true);
        EditText au = input("Login AD", false);
        EditText ap = input(first ? "Mot de passe AD" : "Nouveau mot de passe AD (vide = conserver)", true);

        bu.setText(secrets.get(CredentialStore.BASIC_USER));
        au.setText(secrets.get(CredentialStore.AD_USER));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(first ? "Configurer Plano" : "Identifiants Plano")
                .setView(form(bu, bp, au, ap))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton(first ? null : "Annuler", null)
                .create();

        d.setCancelable(!first);

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u1 = bu.getText().toString().trim();
            String p1 = bp.getText().toString();
            String u2 = au.getText().toString().trim();
            String p2 = ap.getText().toString();

            if (u1.isEmpty() || u2.isEmpty() || (first && (p1.isEmpty() || p2.isEmpty()))) {
                Toast.makeText(this, "Tous les champs requis doivent être remplis", Toast.LENGTH_SHORT).show();
                return;
            }

            secrets.put(CredentialStore.BASIC_USER, u1);
            secrets.put(CredentialStore.AD_USER, u2);

            if (!p1.isEmpty()) secrets.put(CredentialStore.BASIC_PASS, p1);
            if (!p2.isEmpty()) secrets.put(CredentialStore.AD_PASS, p2);

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

        EditText p = input("Nouveau mot de passe AD", true);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Mettre à jour le mot de passe AD")
                .setView(form(p))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = p.getText().toString();

            if (value.isEmpty()) {
                p.setError("Requis");
                return;
            }

            secrets.put(CredentialStore.AD_PASS, value);
            d.dismiss();
            clearWebSession();
            showHome("Mot de passe AD mis à jour");
        }));

        d.show();
    }

    private void showSecurity() {
        new AlertDialog.Builder(this)
                .setTitle("Sécurité")
                .setMessage("• Déverrouillage biométrique au lancement (code/PIN de secours selon Android)\n"
                        + "• Mots de passe chiffrés localement en AES-256-GCM\n"
                        + "• Clé cryptographique conservée dans Android Keystore\n"
                        + "• Aucun mot de passe enregistré dans Chrome/Edge\n"
                        + "• Aucun mot de passe envoyé sur GitHub\n"
                        + "• Ajout de sites limité à HTTPS\n"
                        + "• Les identifiants d’un site ne sont injectés que sur son domaine exact\n"
                        + "• SSO/MFA détectés : aucune tentative de contournement\n\n"
                        + "Les secrets sont brièvement déchiffrés en mémoire au moment d’une connexion. Un appareil rooté ou compromis peut réduire cette protection.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("À propos de l’application")
                .setMessage("Wonder Apps\nVersion 1.5\n\n"
                        + "Analyse automatique des sites, accès rapides et stockage local chiffré.\n\n"
                        + "Signature : Younes AJBILOU")
                .setPositiveButton("OK", null)
                .show();
    }

    private void resetSessionToHome() {
        clearWebSession();
        showHome("Session réinitialisée");
        Toast.makeText(this, "Session effacée. Aucune reconnexion automatique.", Toast.LENGTH_SHORT).show();
    }

    private void clearWebSession() {
        clearTransientState();
        web.stopLoading();
        web.clearCache(true);
        web.clearHistory();
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        web.loadUrl("about:blank");
    }

    private void clearTransientState() {
        timer.removeCallbacksAndMessages(null);
        basicTried = false;
        adTried = false;
        failureShown = false;
        genericFormTried = false;
        genericBasicTried = false;
        analysisMode = false;
        analysisPass = 0;
    }

    private void status(String s) {
        status.setText(s);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        if (analysisMode) {
            clearWebSession();
            showHome("Analyse annulée");
        } else if (!onHome && web.canGoBack()) {
            web.goBack();
        } else if (!onHome) {
            showHome("Prêt");
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        if (biometricCancellation != null) {
            try { biometricCancellation.cancel(); } catch (Exception ignored) {}
        }

        if (web != null) {
            web.stopLoading();
            web.destroy();
        }

        super.onDestroy();
    }
}
