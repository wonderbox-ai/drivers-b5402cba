package com.local.planomagic;

import android.app.*;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.os.*;
import android.hardware.biometrics.BiometricPrompt;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import android.util.Base64;

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
    private static final int REQ_PICK_LOGO = 5103;
    private static final int REQ_PICK_SITE_LOGO = 5104;
    private static final String SITE_PLANO_ID = "__plano__";
    private static final String KEY_APP_ORDER = "app_order_v1";
    private static final String KEY_PLANO_FAVORITE = "plano_favorite";
    private static final String KEY_PLANO_REQUIRE_BIO = "plano_require_biometric";
    private static final String KEY_PLANO_LOCK_EXIT = "plano_lock_on_exit";
    private static final String KEY_PLANO_BLOCK_SCREEN = "plano_block_screenshots";
    private static final String KEY_AUTO_LOCK_SECONDS = "auto_lock_seconds";
    private static final String ACTION_OPEN_SITE = "com.local.planomagic.OPEN_SITE";
    private static final String ACTION_ADD_SITE = "com.local.planomagic.ADD_SITE";
    private static final String ACTION_FEEDBACK = "com.local.planomagic.FEEDBACK";
    private static final String ACTION_SETTINGS = "com.local.planomagic.SETTINGS";

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
    private String pendingSiteLogoId;
    private String pendingShortcutSiteId;
    private String pendingLauncherAction;
    private long backgroundAt = 0L;
    private CancellationSignal biometricCancellation;
    private boolean onHome = true;
    private boolean analysisMode = false;
    private int analysisPass = 0;

    private enum Mode { NONE, PLANO, CUSTOM }
    private Mode mode = Mode.NONE;
    private SiteProfile activeSite;

    private boolean basicTried, adTried, failureShown, genericFormTried, genericBasicTried;
    private boolean browserHandoffStarted = false;

    static class SiteProfile {
        String id = "";
        String name = "";
        String url = "";
        String username = "";
        String password = "";
        String basicUsername = "";
        String basicPassword = "";
        String authType = "PENDING";
        String loginHost = "";
        String openingMode = "AUTO";
        // Explicit opt-in for new applications. Existing saved sites retain their
        // previous behavior via fromJson's compatibility default.
        boolean autoConnect = true;
        boolean favorite = false;
        boolean requireBiometric = false;
        boolean lockOnExit = false;
        boolean blockScreenshots = false;
        boolean vpnRequired = false;
        String category = "";
        long lastUsed = 0L;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("username", username);
            o.put("password", password);
            o.put("basicUsername", basicUsername);
            o.put("basicPassword", basicPassword);
            o.put("authType", authType);
            o.put("loginHost", loginHost);
            o.put("openingMode", openingMode);
            o.put("autoConnect", autoConnect);
            o.put("favorite", favorite);
            o.put("requireBiometric", requireBiometric);
            o.put("lockOnExit", lockOnExit);
            o.put("blockScreenshots", blockScreenshots);
            o.put("vpnRequired", vpnRequired);
            o.put("category", category);
            o.put("lastUsed", lastUsed);
            return o;
        }

        static SiteProfile fromJson(JSONObject o) {
            SiteProfile s = new SiteProfile();
            s.id = o.optString("id", UUID.randomUUID().toString());
            s.name = o.optString("name", "Site");
            s.url = o.optString("url", "");
            s.username = o.optString("username", "");
            s.password = o.optString("password", "");
            s.basicUsername = o.optString("basicUsername", "");
            s.basicPassword = o.optString("basicPassword", "");
            s.authType = o.optString("authType", "PENDING");
            s.loginHost = o.optString("loginHost", "");
            String opening = o.optString("openingMode", "AUTO");
            s.openingMode = ("IN_APP".equals(opening) || "BROWSER".equals(opening)
                    || "EDGE".equals(opening) || "CHROME".equals(opening)
                    || "SAMSUNG".equals(opening)) ? opening : "AUTO";
            s.autoConnect = o.optBoolean("autoConnect", true);
            s.favorite = o.optBoolean("favorite", false);
            s.requireBiometric = o.optBoolean("requireBiometric", false);
            s.lockOnExit = o.optBoolean("lockOnExit", false);
            s.blockScreenshots = o.optBoolean("blockScreenshots", false);
            s.vpnRequired = o.optBoolean("vpnRequired", false);
            s.category = o.optString("category", "");
            s.lastUsed = o.optLong("lastUsed", 0L);
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

        captureLauncherIntent(getIntent());

        boolean onboarded = settings.getBoolean(KEY_ONBOARDED, false);

        // Migration douce depuis les versions précédentes : si des identifiants existent déjà,
        // l'utilisateur a déjà configuré l'application et ne doit pas repasser par l'onboarding.
        if (!onboarded && secrets.isConfigured()) {
            onboarded = true;
            settings.edit().putBoolean(KEY_ONBOARDED, true).apply();
        }

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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureLauncherIntent(intent);
        // Android may deliver a launcher shortcut before onStart. Evaluate the
        // background timeout *before* handling the shortcut, never after.
        if (unlocked && backgroundLockExpired()) {
            lockFromBackground();
        } else if (unlocked) {
            handlePendingShortcut();
        }
    }

    private void captureLauncherIntent(Intent intent) {
        if (intent == null) return;
        pendingShortcutSiteId = intent.getStringExtra("shortcut_site_id");
        String action = intent.getAction();
        if (ACTION_OPEN_SITE.equals(action) || ACTION_ADD_SITE.equals(action)
                || ACTION_FEEDBACK.equals(action) || ACTION_SETTINGS.equals(action)) {
            pendingLauncherAction = action;
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

        boolean requiredWhileLocked = !unlocked;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Créer un code PIN")
                .setMessage("Ce code sert de secours à la biométrie et peut aussi devenir la méthode principale de déverrouillage.")
                .setView(form(pin1, pin2))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton(requiredWhileLocked ? "Fermer" : "Annuler",
                        requiredWhileLocked ? (d,w) -> finishAndRemoveTask() : null)
                .create();

        dialog.setCancelable(!requiredWhileLocked);
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
        backgroundAt = 0L;
        unlockFallbackStarted = false;
        root.setVisibility(View.VISIBLE);
        showHome("Déverrouillé");
        handlePendingShortcut();
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

    private void startOnboarding() {
        new AlertDialog.Builder(this)
                .setTitle("Bienvenue dans Wonder Apps")
                .setMessage("Wonder Apps simplifie l’accès à tes outils professionnels : connexion automatique lorsque c’est possible, "
                        + "analyse des sites ajoutés, stockage local chiffré et réglages de sécurité.\n\n"
                        + "Nous allons d’abord choisir comment protéger l’application, puis configurer une première application. "
                        + "Tu pourras en ajouter d’autres ensuite.")
                .setCancelable(false)
                .setPositiveButton("Commencer", (d,w) ->
                        chooseUnlockMethod(true, this::showFirstAppOnboarding))
                .setNeutralButton("Importer une sauvegarde", (d,w) ->
                        startImport(true))
                .show();
    }

    private void showFirstAppOnboarding() {
        new AlertDialog.Builder(this)
                .setTitle("Première application")
                .setMessage("Commençons par Plano. Cette configuration sert uniquement de première étape : "
                        + "Wonder Apps est conçu pour accueillir d’autres sites et applications ensuite.")
                .setCancelable(false)
                .setPositiveButton("Configurer Plano", (d,w) ->
                        editPlanoCredentials(true, this::finishOnboarding))
                .show();
    }

    private void finishOnboarding() {
        getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDED, true)
                .apply();

        showHome("Configuration terminée");

        new AlertDialog.Builder(this)
                .setTitle("Tout est prêt")
                .setMessage("Wonder Apps est configuré. Veux-tu intégrer un autre site maintenant ?")
                .setPositiveButton("Ajouter un site", (d,w) -> editSiteAddress(null))
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private void finishImportedOnboarding() {
        getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDED, true)
                .apply();

        showHome("Sauvegarde restaurée");

        new AlertDialog.Builder(this)
                .setTitle("Import terminé")
                .setMessage("Tes sites et identifiants ont été restaurés. La méthode de déverrouillage a été configurée sur ce téléphone.")
                .setPositiveButton("Continuer", null)
                .show();
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
        loadAppLogo();
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avatarLp.setMargins(0, 0, dp(10), 0);
        avatar.setLayoutParams(avatarLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        headerTitle = new TextView(this);
        headerTitle.setText("Wonder Apps");
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
            if (!onHome) {
                basicTried = false;
                adTried = false;
                failureShown = false;
                genericFormTried = false;
                genericBasicTried = false;
                status("Actualisation…");
                web.reload();
            }
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

        List<SiteProfile> customSites = loadSites();
        int appCount = 1 + customSites.size();
        int autoCount = secrets.isConfigured() ? 1 : 0;
        int actionCount = secrets.isConfigured() ? 0 : 1;

        for (SiteProfile site : customSites) {
            String type = site.authType == null ? "PENDING" : site.authType;
            boolean twoStep = "BASIC_FORM".equals(type);
            boolean auto = site.autoConnect
                    && ("FORM".equals(type) || "BASIC".equals(type) || twoStep)
                    && !site.username.isEmpty()
                    && !site.password.isEmpty()
                    && (!twoStep || (!site.basicUsername.isEmpty()
                            && !site.basicPassword.isEmpty()));
            if (auto && !isBrowserPreferred(site)) autoCount++;

            if (!isBrowserPreferred(site) && site.autoConnect
                    && ("PENDING".equals(type) || "UNKNOWN".equals(type)
                    || (("FORM".equals(type) || "BASIC".equals(type)
                         || "BASIC_FORM".equals(type)) && !auto))) {
                actionCount++;
            }
        }

        TextView hello = new TextView(this);
        hello.setText("Bonjour 👋");
        hello.setTextSize(25);
        hello.setTextColor(primary());
        homeList.addView(hello);

        TextView ready = new TextView(this);
        ready.setText(actionCount == 0
                ? "Tes applications sont à portée de main"
                : actionCount + " application" + (actionCount > 1 ? "s" : "")
                        + " à configurer");
        ready.setTextSize(14);
        ready.setTextColor(secondary());
        LinearLayout.LayoutParams readyLp = new LinearLayout.LayoutParams(-1, -2);
        readyLp.setMargins(0, dp(3), 0, dp(16));
        ready.setLayoutParams(readyLp);
        homeList.addView(ready);

        TextView searchButton = actionButton("⌕  Rechercher une application", false);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(46));
        searchLp.setMargins(0, 0, 0, dp(12));
        searchButton.setLayoutParams(searchLp);
        searchButton.setOnClickListener(v -> showSearchApps());
        homeList.addView(searchButton);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setWeightSum(3f);

        stats.addView(statTile(String.valueOf(appCount), "Applications"));
        stats.addView(statTile(String.valueOf(autoCount), "Auto configurées"));
        stats.addView(statTile(String.valueOf(actionCount), "À vérifier"));

        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-1, -2);
        statsLp.setMargins(0, 0, 0, dp(20));
        stats.setLayoutParams(statsLp);
        homeList.addView(stats);

        SharedPreferences prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE);

        Map<String, Long> recentTimes = new HashMap<>();
        long planoLast = prefs.getLong("plano_last_used", 0L);
        if (planoLast > 0L) recentTimes.put(SITE_PLANO_ID, planoLast);
        for (SiteProfile site : customSites) {
            if (site.lastUsed > 0L) recentTimes.put(site.id, site.lastUsed);
        }

        // With only two or three apps, the Recent section merely duplicates
        // the whole application list and makes the dashboard harder to scan.
        if (appCount >= 4 && !recentTimes.isEmpty()) {
            List<String> recentIds = new ArrayList<>(recentTimes.keySet());
            recentIds.sort((a,b) -> Long.compare(recentTimes.get(b), recentTimes.get(a)));

            TextView recentTitle = new TextView(this);
            recentTitle.setText("Récents");
            recentTitle.setTextSize(18);
            recentTitle.setTextColor(primary());
            LinearLayout.LayoutParams recentLp = new LinearLayout.LayoutParams(-1, -2);
            recentLp.setMargins(dp(2), 0, 0, dp(10));
            recentTitle.setLayoutParams(recentLp);
            homeList.addView(recentTitle);

            int shownRecent = 0;
            for (String id : recentIds) {
                if (shownRecent >= 3) break;
                if (SITE_PLANO_ID.equals(id)) {
                    addPlanoCard();
                } else {
                    SiteProfile site = findById(customSites, id);
                    if (site != null) addCustomCard(site);
                }
                shownRecent++;
            }

            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(8)));
            homeList.addView(spacer);
        }

        boolean planoFavorite = prefs.getBoolean(KEY_PLANO_FAVORITE, false);
        List<SiteProfile> favorites = new ArrayList<>();
        for (SiteProfile site : customSites) {
            if (site.favorite) favorites.add(site);
        }

        if (appCount >= 4 && (planoFavorite || !favorites.isEmpty())) {
            TextView favTitle = new TextView(this);
            favTitle.setText("★ Favoris");
            favTitle.setTextSize(18);
            favTitle.setTextColor(primary());
            LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-1, -2);
            favLp.setMargins(dp(2), 0, 0, dp(10));
            favTitle.setLayoutParams(favLp);
            homeList.addView(favTitle);

            int shown = 0;
            if (planoFavorite && shown < 4) {
                addPlanoCard();
                shown++;
            }
            for (SiteProfile site : favorites) {
                if (shown >= 4) break;
                addCustomCard(site);
                shown++;
            }

            TextView allFav = smallNote("Appui long sur une application pour l’ajouter ou la retirer des favoris.");
            LinearLayout.LayoutParams allFavLp = new LinearLayout.LayoutParams(-1, -2);
            allFavLp.setMargins(0, 0, 0, dp(12));
            allFav.setLayoutParams(allFavLp);
            homeList.addView(allFav);
        }

        TextView section = new TextView(this);
        section.setText("Mes applications");
        section.setTextSize(19);
        section.setTextColor(primary());
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.setMargins(dp(2), 0, 0, dp(10));
        section.setLayoutParams(sectionLp);
        homeList.addView(section);

        addPlanoCard();

        for (SiteProfile site : customSites) {
            addCustomCard(site);
        }

        TextView add = actionButton("+  Ajouter une application", false);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(52));
        addLp.setMargins(0, dp(8), 0, dp(10));
        add.setLayoutParams(addLp);
        add.setOnClickListener(v -> editSiteAddress(null));
        homeList.addView(add);

        TextView idea = actionButton("💡  Proposer une idée", true);
        LinearLayout.LayoutParams ideaLp = new LinearLayout.LayoutParams(-1, dp(52));
        ideaLp.setMargins(0, 0, 0, dp(8));
        idea.setLayoutParams(ideaLp);
        idea.setOnClickListener(v -> showFeedbackDialog());
        homeList.addView(idea);

        TextView about = actionButton("ⓘ  À propos de Wonder Apps", false);
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(-1, dp(46));
        aboutLp.setMargins(0, dp(2), 0, dp(7));
        about.setLayoutParams(aboutLp);
        about.setOnClickListener(v -> showAbout());
        homeList.addView(about);
        homeList.addView(smallNote(
                "« La performance naît souvent des petites frictions "
                        + "que l’on supprime chaque jour. »"));

        updateDynamicAppShortcuts();
    }

    private void showSearchApps() {
        LinearLayout stack = dialogStack();

        EditText query = input("Rechercher par nom, domaine ou catégorie", false);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);

        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(false);
        resultScroll.addView(results);
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(-1, dp(360));
        resultLp.setMargins(0, dp(8), 0, 0);
        resultScroll.setLayoutParams(resultLp);

        stack.addView(query);
        stack.addView(resultScroll);

        refreshSearchResults("", results);

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshSearchResults(String.valueOf(s), results);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        new AlertDialog.Builder(this)
                .setTitle("Rechercher")
                .setView(stack)
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void refreshSearchResults(String rawQuery, LinearLayout results) {
        results.removeAllViews();
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);

        if (q.isEmpty() || "plano".contains(q) || PLANO_HOST.contains(q)) {
            LinearLayout plano = dashboardAppCard(
                    SITE_PLANO_ID,
                    "Plano",
                    "Connexion automatique • Prêt",
                    "AUTO",
                    accent());
            plano.setOnClickListener(v -> openPlano());
            plano.setOnLongClickListener(v -> {
                showPlanoActions();
                return true;
            });
            results.addView(plano);
        }

        for (SiteProfile site : loadSites()) {
            Uri uri = Uri.parse(site.url);
            String host = uri.getHost() == null ? site.url : uri.getHost();
            String haystack = (site.name + " " + host + " " + site.category).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !haystack.contains(q)) continue;

            String context = site.category == null || site.category.trim().isEmpty()
                    ? host
                    : site.category.trim() + " • " + host;

            LinearLayout card = dashboardAppCard(
                    site.id,
                    site.name,
                    context + " • " + siteStateLabel(site),
                    isBrowserPreferred(site) ? "WEB" : authShort(site.authType),
                    isBrowserPreferred(site) ? accent() : authColor(site.authType));
            card.setOnClickListener(v -> openCustom(site));
            card.setOnLongClickListener(v -> {
                showSiteActions(site);
                return true;
            });
            results.addView(card);
        }

        if (results.getChildCount() == 0) {
            results.addView(smallNote("Aucune application ne correspond à cette recherche."));
        }
    }

    private LinearLayout statTile(String value, String label) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(8), dp(12), dp(8), dp(12));
        tile.setBackground(round(surface(), 15, border()));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(78), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        tile.setLayoutParams(lp);

        TextView number = new TextView(this);
        number.setText(value);
        number.setTextSize(22);
        number.setTextColor(primary());
        number.setGravity(Gravity.CENTER);

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(10.5f);
        caption.setTextColor(secondary());
        caption.setGravity(Gravity.CENTER);

        tile.addView(number);
        tile.addView(caption);
        return tile;
    }

    private TextView actionButton(String text, boolean accentButton) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);

        if (accentButton) {
            button.setTextColor(Color.WHITE);
            button.setBackground(round(accent(), 15, Color.TRANSPARENT));
        } else {
            button.setTextColor(primary());
            button.setBackground(round(surface2(), 15, border()));
        }
        return button;
    }

    private void addPlanoCard() {
        LinearLayout card = dashboardAppCard(
                SITE_PLANO_ID,
                "Plano",
                "Prêt • Connexion automatique",
                "AUTO",
                accent());
        card.setOnClickListener(v -> openPlano());
        card.setOnLongClickListener(v -> {
            showPlanoActions();
            return true;
        });
        homeList.addView(card);
    }

    private void addCustomCard(SiteProfile site) {
        String type = site.authType == null ? "PENDING" : site.authType;
        Uri uri = Uri.parse(site.url);
        String host = uri.getHost() == null ? site.url : uri.getHost();
        String context = site.category == null || site.category.trim().isEmpty()
                ? host
                : site.category.trim() + " • " + host;

        LinearLayout card = dashboardAppCard(
                site.id,
                site.name,
                context + " • " + siteStateLabel(site),
                isBrowserPreferred(site) ? "WEB" : authShort(type),
                isBrowserPreferred(site) ? accent() : authColor(type));

        card.setOnClickListener(v -> openCustom(site));

        card.setOnLongClickListener(v -> {
            showSiteActions(site);
            return true;
        });

        homeList.addView(card);
    }

    private LinearLayout dashboardAppCard(String siteId, String name, String subtitle, String badgeText, int badgeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(round(surface(), 17, border()));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        View appIcon = siteIconView(siteId, name);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(17);
        title.setTextColor(primary());

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(12.5f);
        sub.setTextColor(secondary());

        text.addView(title);
        text.addView(sub);

        TextView badge = badge(badgeText, badgeColor);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(26);
        arrow.setTextColor(secondary());
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(30), dp(48));
        arrowLp.setMargins(dp(6), 0, 0, 0);
        arrow.setLayoutParams(arrowLp);

        card.addView(appIcon);
        card.addView(text);
        card.addView(badge);
        card.addView(arrow);
        return card;
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

    private String siteStateLabel(SiteProfile site) {
        if (site == null) return "Configuration à vérifier";
        String type = site.authType == null ? "PENDING" : site.authType;
        if (isInternalHttp(site)) return "Site interne • VPN requis";
        if (isBrowserPreferred(site)) return "Connexion via navigateur";
        if (!site.autoConnect) return "Ouverture manuelle";
        if ("SSO".equals(type)) return "Connexion professionnelle";
        if ("MFA".equals(type)) return "Validation de connexion nécessaire";
        if ("BASIC_FORM".equals(type)) {
            return (!site.basicUsername.isEmpty() && !site.basicPassword.isEmpty()
                    && !site.username.isEmpty() && !site.password.isEmpty())
                    ? "Connexion en 2 étapes configurée"
                    : "Connexion en 2 étapes à configurer";
        }
        if ("PENDING".equals(type) || "UNKNOWN".equals(type)) return "Configuration à vérifier";
        if (("FORM".equals(type) || "BASIC".equals(type))
                && (site.username.isEmpty() || site.password.isEmpty())) {
            return "Identifiants à configurer";
        }
        if ("FORM".equals(type) || "BASIC".equals(type)) return "Connexion auto configurée";
        return "Page accessible";
    }

    private String authShort(String type) {
        switch (type) {
            case "FORM":
            case "BASIC":
            case "BASIC_FORM": return "AUTO";
            case "SSO": return "PRO";
            case "MFA": return "MFA";
            case "NONE": return "WEB";
            default: return "À VOIR";
        }
    }

    private String authLabel(String type) {
        switch (type) {
            case "FORM": return "formulaire détecté";
            case "BASIC": return "HTTP Basic détecté";
            case "BASIC_FORM": return "Deux étapes : HTTP Basic puis formulaire professionnel";
            case "SSO": return "SSO détecté";
            case "MFA": return "MFA détecté";
            case "NONE": return "aucune connexion détectée";
            case "UNKNOWN": return "méthode non reconnue";
            default: return "analyse requise";
        }
    }

    private int authColor(String type) {
        if ("FORM".equals(type) || "BASIC".equals(type)
                || "BASIC_FORM".equals(type)) return Color.rgb(45,160,90);
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
                if (u == null) return true;

                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);

                // Internal WebView schemes are not network transport and must not be treated as insecure.
                if ("javascript".equals(scheme) || "about".equals(scheme)
                        || "data".equals(scheme) || "blob".equals(scheme)) {
                    return false;
                }

                // Plano contains legacy links that may still point to http:// on the same host.
                // Upgrade safe GET navigations to HTTPS instead of blocking logout/navigation.
                if ("http".equals(scheme) && mode == Mode.PLANO
                        && PLANO_HOST.equalsIgnoreCase(u.getHost())
                        && "GET".equalsIgnoreCase(r.getMethod())) {
                    Uri secure = u.buildUpon().scheme("https").build();
                    v.loadUrl(secure.toString());
                    return true;
                }

                if (!"https".equals(scheme)) {
                    Toast.makeText(MainActivity.this, "Lien externe ou non sécurisé bloqué", Toast.LENGTH_SHORT).show();
                    return true;
                }

                if (mode == Mode.PLANO && !PLANO_HOST.equalsIgnoreCase(u.getHost())) {
                    Toast.makeText(MainActivity.this, "Navigation hors Plano bloquée", Toast.LENGTH_SHORT).show();
                    return true;
                }

                if (r.isForMainFrame() && !analysisMode && mode == Mode.CUSTOM
                        && activeSite != null && "AUTO".equals(activeSite.openingMode)
                        && commonSsoHost(u.getHost())) {
                    SiteProfile target = activeSite;
                    v.post(() -> handoffSsoToBrowser(target));
                    return true;
                }

                return false;
            }

            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                if (analysisMode && activeSite != null) {
                    h.cancel();
                    String configuredHost = Uri.parse(activeSite.url).getHost();
                    if (configuredHost != null && configuredHost.equalsIgnoreCase(host)
                            && !SiteRoutingPolicy.isIdentityProviderHost(host)) {
                        activeSite.loginHost = host;
                        finishAnalysis(activeSite, "BASIC");
                    }
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

                if (mode == Mode.CUSTOM && activeSite != null
                        && activeSite.autoConnect
                        && ("BASIC".equals(activeSite.authType)
                                || "BASIC_FORM".equals(activeSite.authType))
                        && sameConfiguredHost(activeSite, host)
                        && !genericBasicTried) {
                    boolean twoSteps = "BASIC_FORM".equals(activeSite.authType);
                    String basicUser = twoSteps ? activeSite.basicUsername : activeSite.username;
                    String basicPass = twoSteps ? activeSite.basicPassword : activeSite.password;
                    if (!basicUser.isEmpty() && !basicPass.isEmpty()) {
                        genericBasicTried = true;
                        status(twoSteps ? "Connexion 1/2 • Accès au site…" : "Authentification…");
                        h.proceed(basicUser, basicPass);
                        return;
                    }
                }

                h.cancel();
            }

            @Override public void onPageFinished(WebView v, String u) {
                Uri uri = Uri.parse(u);
                if (onHome || !unlocked || "about".equalsIgnoreCase(uri.getScheme())) {
                    return;
                }

                if (analysisMode && activeSite != null) {
                    timer.postDelayed(() -> analyzeCurrentPage(activeSite, uri), 700);
                    return;
                }

                if (mode == Mode.CUSTOM && activeSite != null
                        && "AUTO".equals(activeSite.openingMode)
                        && commonSsoHost(uri.getHost())) {
                    handoffSsoToBrowser(activeSite);
                    return;
                }

                headerTitle.setText(mode == Mode.CUSTOM && activeSite != null
                        ? activeSite.name
                        : uri.getHost() == null ? "Site" : uri.getHost());

                if (mode == Mode.PLANO && PLANO_HOST.equalsIgnoreCase(uri.getHost())) {
                    tryPlanoAdLogin();
                } else if (mode == Mode.CUSTOM && activeSite != null) {
                    String type = activeSite.authType == null ? "PENDING" : activeSite.authType;
                    if (activeSite.autoConnect
                            && ("FORM".equals(type) || "BASIC_FORM".equals(type))
                            && sameConfiguredHost(activeSite, uri.getHost())) {
                        if (genericFormTried) checkGenericSessionState(activeSite);
                        else tryGenericLogin();
                    } else if (!activeSite.autoConnect) {
                        status("Connexion manuelle");
                    } else if ("BASIC".equals(type) || "NONE".equals(type)) {
                        status("Page ouverte • connexion à confirmer");
                    } else if ("SSO".equals(type) || "MFA".equals(type)) {
                        status("Connexion professionnelle");
                    }
                }
            }
        });
    }

    private boolean sameConfiguredHost(SiteProfile site, String host) {
        if (site == null || host == null) return false;
        String allowed = site.loginHost == null || site.loginHost.isEmpty()
                ? Uri.parse(site.url).getHost()
                : site.loginHost;
        return SiteRoutingPolicy.mayInjectCredentials(allowed, host, site.authType);
    }

    private boolean commonSsoHost(String host) {
        return SiteRoutingPolicy.isIdentityProviderHost(host);
    }

    private void analyzeSite(SiteProfile site) {
        if (isInternalHttp(site)) {
            new AlertDialog.Builder(this)
                    .setTitle("Site interne HTTP")
                    .setMessage("Ce site reste dans ton navigateur via VPN. "
                            + "Wonder Apps n’analyse pas son formulaire et ne "
                            + "stocke pas de mot de passe pour une adresse HTTP.")
                    .setPositiveButton("Ouvrir", (d,w) -> openCustom(site))
                    .setNegativeButton("Fermer", null)
                    .show();
            return;
        }
        // Analyzing Jira must never log the user out of Plano or other sites.
        clearTransientState();
        web.stopLoading();
        web.loadUrl("about:blank");

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
                } else if (sso) {
                    // Be conservative: an identity-provider option on a login
                    // form must not be mistaken for proven password auto-login.
                    site.loginHost = "";
                    finishAnalysis(site, "SSO");
                } else if (passwords > 0) {
                    site.loginHost = host == null ? "" : host;
                    finishAnalysis(site, "FORM");
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
                        + ". Wonder Apps peut proposer tes identifiants sur cet hôte. "
                        + "La réussite devra être confirmée.";
                credsUseful = true;
                break;
            case "FORM":
                title = "Formulaire détecté";
                message = "Un formulaire identifiant / mot de passe a été détecté"
                        + (saved.loginHost.isEmpty() ? "" : " sur " + saved.loginHost)
                        + ". Une automatisation peut être configurée, mais sa réussite "
                        + "reste à confirmer sur le site réel.";
                credsUseful = true;
                break;
            case "SSO":
                title = "SSO détecté";
                message = "Connexion professionnelle détectée. Wonder Apps utilisera normalement "
                        + "le navigateur du téléphone pour faciliter le retour vers " + saved.name
                        + ". Les contrôles Microsoft et MFA restent actifs.";
                break;
            case "MFA":
                title = "MFA détecté";
                message = "Validation supplémentaire détectée. Wonder Apps privilégiera "
                        + "le navigateur du téléphone. La validation MFA n’est jamais contournée.";
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
                status("Page ouverte • connexion à confirmer");
            }
        });
    }

    private void tryGenericLogin() {
        if (activeSite == null || genericFormTried) return;
        if (!activeSite.autoConnect
                || (!"FORM".equals(activeSite.authType)
                        && !"BASIC_FORM".equals(activeSite.authType))) return;
        if (isAtlassianCloudSite(activeSite)) {
            status("Connexion professionnelle • authentification manuelle");
            return;
        }
        String currentHost = web.getUrl() == null ? null : Uri.parse(web.getUrl()).getHost();
        if (!sameConfiguredHost(activeSite, currentHost)) return;
        if (activeSite.username.isEmpty() || activeSite.password.isEmpty()) return;

        String user = JSONObject.quote(activeSite.username);
        String pass = JSONObject.quote(activeSite.password);

        web.evaluateJavascript(loginScript(user, pass), r -> {
            if (r != null && r.contains("OK")) {
                genericFormTried = true;
                status("BASIC_FORM".equals(activeSite.authType)
                        ? "Connexion 2/2 • Compte professionnel…"
                        : "Connexion automatique…");
                timer.postDelayed(() -> checkGenericSessionState(activeSite), 4500);
            } else {
                status("Formulaire prêt • connexion à confirmer");
            }
        });
    }

    private void checkGenericSessionState(SiteProfile site) {
        if (site == null || mode != Mode.CUSTOM || activeSite == null
                || !site.id.equals(activeSite.id) || isFinishing()) return;

        web.evaluateJavascript(
                "(function(){var p=document.querySelector('input[type=password]');return !!(p&&p.offsetParent!==null);})()",
                r -> {
                    if (mode != Mode.CUSTOM || activeSite == null || !site.id.equals(activeSite.id)) return;
                    if ("true".equals(r)) {
                        status("Connexion nécessaire");
                    } else {
                        status("Page ouverte • connexion à confirmer");
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

        String js = "(function(){"
                + "var p=document.querySelector('input[type=password]');"
                + "var visible=!!(p&&p.offsetParent!==null);"
                + "var txt=(document.body?document.body.innerText:'').toLowerCase();"
                + "var explicit=(txt.indexOf('mot de passe incorrect')>=0"
                + "||txt.indexOf('identifiant incorrect')>=0"
                + "||txt.indexOf('invalid password')>=0"
                + "||txt.indexOf('invalid credentials')>=0"
                + "||txt.indexOf('authentication failed')>=0"
                + "||txt.indexOf('connexion refusée')>=0);"
                + "return JSON.stringify({visible:visible,explicit:explicit});"
                + "})()";

        web.evaluateJavascript(js, r -> {
            try {
                String decoded = r;
                if (decoded != null && decoded.length() >= 2
                        && decoded.startsWith("\"") && decoded.endsWith("\"")) {
                    decoded = new JSONArray("[" + decoded + "]").getString(0);
                }

                JSONObject result = new JSONObject(decoded == null ? "{}" : decoded);
                boolean visible = result.optBoolean("visible", false);
                boolean explicit = result.optBoolean("explicit", false);

                if (visible && explicit) {
                    failureShown = true;
                    status("Connexion AD refusée");

                    new AlertDialog.Builder(this)
                            .setTitle("Connexion AD refusée")
                            .setMessage("Plano indique explicitement que l’identifiant ou le mot de passe a été refusé.")
                            .setPositiveButton("Mettre à jour", (d,w) -> editAdPassword())
                            .setNegativeButton("Annuler", null)
                            .show();
                } else if (visible) {
                    status("Formulaire de connexion prêt");
                } else {
                    status("Page ouverte • connexion à confirmer");
                }
            } catch (Exception ex) {
                status("Connexion en cours…");
            }
        });
    }

    private void openPlano() {
        boolean requireBio = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getBoolean(KEY_PLANO_REQUIRE_BIO, false);
        if (requireBio) {
            requestSiteAuthentication("Plano", this::openPlanoNow);
        } else {
            openPlanoNow();
        }
    }

    private void openPlanoNow() {
        if (!secrets.isConfigured()) {
            editPlanoCredentials(true);
            return;
        }

        getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .edit()
                .putLong("plano_last_used", System.currentTimeMillis())
                .apply();

        clearTransientState();
        mode = Mode.PLANO;
        activeSite = null;
        applyScreenProtection(getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getBoolean(KEY_PLANO_BLOCK_SCREEN, false));
        showWeb("Plano");
        status("Ouverture…");
        web.loadUrl(PLANO_URL);
        logEvent("Ouverture", "Plano");
    }

    private boolean isAtlassianCloudSite(SiteProfile site) {
        if (site == null || site.url == null) return false;
        return SiteRoutingPolicy.isAtlassianCloudHost(Uri.parse(site.url).getHost());
    }

    private boolean isProviderEntryHost(String host) {
        return SiteRoutingPolicy.isIdentityProviderHost(host);
    }

    private boolean isBrowserPreferred(SiteProfile site) {
        if (site == null) return false;
        if (isInternalHttp(site)) return true; // Even when old settings claim IN_APP.
        if ("BASIC_FORM".equals(site.authType)) return false; // Two-step login is integrated and HTTPS-only.
        String host = site.url == null ? null : Uri.parse(site.url).getHost();
        return SiteRoutingPolicy.useExternalBrowser(host, site.authType, site.openingMode);
    }

    private boolean hasInvalidProviderEntryPoint(SiteProfile site) {
        if (site == null) return true;
        Uri uri = Uri.parse(site.url == null ? "" : site.url);
        return (!"https".equalsIgnoreCase(uri.getScheme())
                    && !SiteRoutingPolicy.isAllowedInternalHttp(
                            uri.getScheme(), uri.getHost()))
                || uri.getHost() == null
                || isProviderEntryHost(uri.getHost());
    }

    private void explainEntryPoint(SiteProfile site) {
        new AlertDialog.Builder(this)
                .setTitle("Adresse à corriger • " + site.name)
                .setMessage("L’adresse enregistrée est une page de connexion Atlassian, Microsoft "
                        + "ou Google, et non le site " + site.name + ". Pour conserver "
                        + "le retour automatique après identification, enregistre l’adresse "
                        + "du site que tu ouvres normalement dans ton navigateur. "
                        + "Les identifiants ne sont pas modifiés.")
                .setPositiveButton("Modifier l’adresse", (d,w) -> editSiteAddress(site))
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private void openCustom(SiteProfile site) {
        if (site == null) return;
        if (hasInvalidProviderEntryPoint(site)) {
            explainEntryPoint(site);
            return;
        }

        Runnable open = () -> {
            if (isInternalHttp(site) || !site.autoConnect) {
                // Manual connections must not trigger automatic analysis or
                // have their HTTP Basic challenges silently cancelled.
                openBrowserForSite(site);
            } else if ("BASIC_FORM".equals(site.authType)
                    && (site.basicUsername.isEmpty() || site.basicPassword.isEmpty()
                    || site.username.isEmpty() || site.password.isEmpty())) {
                new AlertDialog.Builder(this)
                        .setTitle("Configurer la connexion de " + site.name)
                        .setMessage("Comme Plano, cette application utilise deux étapes : "
                                + "accès au site, puis formulaire professionnel. "
                                + "Enregistre les deux identifiants et les deux mots de passe.")
                        .setPositiveButton("Configurer", (d,w) -> editTwoStepCredentials(site))
                        .setNegativeButton("Annuler", null)
                        .show();
            } else if ("PENDING".equals(site.authType) || "UNKNOWN".equals(site.authType)) {
                if (isBrowserPreferred(site)) openBrowserForSite(site);
                else analyzeSite(site);
            } else if (isBrowserPreferred(site)) {
                openBrowserForSite(site);
            } else {
                openCustomNow(site);
            }
        };

        if (site.requireBiometric) requestSiteAuthentication(site.name, open);
        else open.run();
    }

    private void recordSiteUse(SiteProfile site) {
        site.lastUsed = System.currentTimeMillis();
        List<SiteProfile> sites = loadSites();
        SiteProfile saved = findById(sites, site.id);
        if (saved != null) {
            saved.lastUsed = site.lastUsed;
            saveSites(sites);
        }
    }

    private boolean launchExternalSite(SiteProfile site, String browserPackage) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(site.url));
        view.addCategory(Intent.CATEGORY_BROWSABLE);
        if (browserPackage != null) view.setPackage(browserPackage);

        try {
            startActivity(view);
            recordSiteUse(site);
            logEvent("Ouverture navigateur", site.name);
            showHome("Site ouvert dans le navigateur");
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private boolean hasActiveVpn() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null || manager.getActiveNetwork() == null) return false;
            NetworkCapabilities capabilities =
                    manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showVpnConnectionHelp(SiteProfile site) {
        new AlertDialog.Builder(this)
                .setTitle("Connexion professionnelle nécessaire")
                .setMessage(site.name + " est un site interne en HTTP. "
                        + "Wonder Apps ne détecte pas de VPN actif pour sa connexion. "
                        + "Active le VPN Wonderbox, puis retouche l’application. "
                        + "Si tu es déjà sur le réseau interne de l’entreprise, "
                        + "tu peux essayer sans VPN. Wonder Apps ne peut pas "
                        + "activer ni authentifier ton VPN à ta place.")
                .setPositiveButton("Réglages VPN", (d,w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
                    } catch (Exception unavailable) {
                        Toast.makeText(this, "Ouvre ton application VPN professionnelle.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Déjà sur réseau interne", (d,w) -> {
                    if (!launchExternalSite(site, null)) {
                        Toast.makeText(this, "Navigateur indisponible",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void openBrowserForSite(SiteProfile site) {
        if (hasInvalidProviderEntryPoint(site)) {
            explainEntryPoint(site);
            return;
        }

        // FLAG_SECURE applies to Wonder Apps only. It cannot secure Edge / Chrome.
        if (site.blockScreenshots) {
            new AlertDialog.Builder(this)
                    .setTitle("Protection des captures activée")
                    .setMessage("Le navigateur ne peut pas hériter de la protection "
                            + "des captures d’écran de Wonder Apps. Pour ouvrir "
                            + site.name + " dans le navigateur, choisis d’abord "
                            + "si tu souhaites désactiver cette protection "
                            + "pour cette application. Aucun réglage de sécurité "
                            + "n’est modifié automatiquement.")
                    .setPositiveButton("Sécurité du site",
                            (d,w) -> showSiteSecurity(site))
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }

        if (isInternalHttp(site) && !hasActiveVpn()) {
            showVpnConnectionHelp(site);
            return;
        }

        String packageName = null;
        if ("EDGE".equals(site.openingMode)) {
            packageName = "com.microsoft.emmx";
        } else if ("CHROME".equals(site.openingMode)) {
            packageName = "com.android.chrome";
        } else if ("SAMSUNG".equals(site.openingMode)) {
            packageName = "com.sec.android.app.sbrowser";
        } else if ("AUTO".equals(site.openingMode)
                && (isAtlassianCloudSite(site)
                || "SSO".equals(site.authType) || "MFA".equals(site.authType))) {
            // Microsoft Edge often has the corporate Microsoft session.
            packageName = "com.microsoft.emmx";
        }

        if (launchExternalSite(site, packageName)) return;

        if ("AUTO".equals(site.openingMode) && packageName != null) {
            if (launchExternalSite(site, null)) {
                Toast.makeText(this, "Edge indisponible : navigateur habituel utilisé",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Navigateur indisponible")
                .setMessage("Le navigateur sélectionné pour " + site.name
                        + " n’est pas disponible sur ce téléphone. "
                        + "Choisis un autre navigateur ou modifie le mode d’ouverture.")
                .setPositiveButton("Navigateur habituel", (d,w) -> {
                    if (!launchExternalSite(site, null)) {
                        Toast.makeText(this, "Aucun navigateur disponible",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Modifier le mode", (d,w) -> showSiteOpeningMode(site))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void handoffSsoToBrowser(SiteProfile site) {
        if (site == null || browserHandoffStarted || mode != Mode.CUSTOM
                || activeSite == null || !site.id.equals(activeSite.id)
                || analysisMode || !"AUTO".equals(site.openingMode)) return;

        browserHandoffStarted = true;
        List<SiteProfile> sites = loadSites();
        SiteProfile saved = findById(sites, site.id);

        if (saved != null) {
            if (!"MFA".equals(saved.authType)) saved.authType = "SSO";
            saveSites(sites);
            site.authType = saved.authType;
        }

        web.stopLoading();
        web.loadUrl("about:blank");
        openBrowserForSite(site);
    }

    private void openCustomNow(SiteProfile site) {
        if (site == null) return;

        browserHandoffStarted = false;
        recordSiteUse(site);
        clearTransientState();
        mode = Mode.CUSTOM;
        activeSite = site;
        applyScreenProtection(site.blockScreenshots);
        showWeb(site.name);
        status("Ouverture…");
        web.loadUrl(site.url);
        logEvent("Ouverture intégrée", site.name);
    }

    private void showHome(String msg) {
        Mode previousMode = mode;
        SiteProfile previousSite = activeSite;

        if (previousMode == Mode.CUSTOM && previousSite != null && previousSite.lockOnExit) {
            clearSiteSession(previousSite.url, "BASIC".equals(previousSite.authType));
        } else if (previousMode == Mode.PLANO
                && getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(KEY_PLANO_LOCK_EXIT, false)) {
            clearSiteSession(PLANO_URL, true);
        }

        analysisMode = false;
        mode = Mode.NONE;
        activeSite = null;
        onHome = true;
        applyScreenProtection(false);

        rebuildHome();

        homeScroll.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        headerTitle.setText("Wonder Apps");
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

    private void applyScreenProtection(boolean enabled) {
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void showSitePinUnlockDialog(String siteName, Runnable onSuccess) {
        if (!pinManager.hasPin()) {
            new AlertDialog.Builder(this)
                    .setTitle("Code PIN Wonder Apps requis")
                    .setMessage("Configure un code PIN Wonder Apps pour pouvoir utiliser "
                            + "la protection renforcée de " + siteName + ".")
                    .setPositiveButton("Configurer", (d,w) ->
                            showPinSetup(() -> requestSiteAuthentication(siteName, onSuccess)))
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }

        EditText pin = input("Code PIN Wonder Apps", true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        final int[] attempts = {0};

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Accès sécurisé • " + siteName)
                .setMessage("Utilise ton code PIN Wonder Apps pour cette application.")
                .setView(form(pin))
                .setPositiveButton("Déverrouiller", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (pinManager.verifyPin(pin.getText().toString())) {
                        dialog.dismiss();
                        // A timeout may have locked Wonder Apps while the prompt was active.
                        if (unlocked && !isFinishing() && onSuccess != null) {
                            onSuccess.run();
                        } else if (!unlocked) {
                            requestUnlock();
                        }
                        return;
                    }

                    attempts[0]++;
                    pin.setText("");
                    if (attempts[0] >= 5) {
                        dialog.dismiss();
                        Toast.makeText(this,
                                "Trop de tentatives. Réessaie plus tard.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        pin.setError("Code PIN incorrect");
                    }
                }));
        dialog.show();
    }

    private void requestSiteAuthentication(String siteName, Runnable onSuccess) {
        if (!pinManager.hasPin()) {
            showSitePinUnlockDialog(siteName, onSuccess);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showSitePinUnlockDialog(siteName, onSuccess);
            return;
        }

        final boolean[] handled = {false};
        try {
            CancellationSignal signal = new CancellationSignal();
            new BiometricPrompt.Builder(this)
                    .setTitle("Accès sécurisé • " + siteName)
                    .setSubtitle("Confirme ton identité pour ouvrir cette application")
                    .setNegativeButton("Code PIN Wonder Apps", getMainExecutor(),
                            (dialog, which) -> {
                                if (handled[0]) return;
                                handled[0] = true;
                                showSitePinUnlockDialog(siteName, onSuccess);
                            })
                    .build()
                    .authenticate(signal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            if (handled[0]) return;
                            handled[0] = true;
                            if (unlocked && !isFinishing() && onSuccess != null) {
                                onSuccess.run();
                            } else if (!unlocked) {
                                requestUnlock();
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence message) {
                            super.onAuthenticationError(errorCode, message);
                            if (handled[0]) return;
                            handled[0] = true;
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                    && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                                showSitePinUnlockDialog(siteName, onSuccess);
                            }
                        }
                    });
        } catch (Exception unavailable) {
            if (!handled[0]) {
                handled[0] = true;
                showSitePinUnlockDialog(siteName, onSuccess);
            }
        }
    }

    private void handlePendingShortcut() {
        String action = pendingLauncherAction;
        String id = pendingShortcutSiteId;
        pendingLauncherAction = null;
        pendingShortcutSiteId = null;

        if (!unlocked || isFinishing()) return;

        if (ACTION_ADD_SITE.equals(action)) {
            showHome("Prêt");
            timer.postDelayed(() -> editSiteAddress(null), 120);
            return;
        }

        if (ACTION_FEEDBACK.equals(action)) {
            showHome("Prêt");
            timer.postDelayed(this::showFeedbackDialog, 120);
            return;
        }

        if (ACTION_SETTINGS.equals(action)) {
            showHome("Prêt");
            timer.postDelayed(this::showSettingsCenter, 120);
            return;
        }

        if (!ACTION_OPEN_SITE.equals(action) && (id == null || id.isEmpty())) return;

        if (SITE_PLANO_ID.equals(id)) {
            openPlano();
            return;
        }

        SiteProfile site = findById(loadSites(), id);
        if (site == null) {
            Toast.makeText(this, "Cette application n’existe plus dans Wonder Apps.", Toast.LENGTH_LONG).show();
            return;
        }

        openCustom(site);
    }

    /**
     * Best-effort cleanup of the current WebView origin only.
     * CookieManager does not support reliable removal of all cookies for one
     * origin (domain/path variants), and Android's HTTP auth store is global.
     * Do not call the global clear methods from a per-site action.
     */
    private void clearSiteSession(String url, boolean httpBasic) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) return;
            String origin = "https://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());

            CookieManager manager = CookieManager.getInstance();
            String existing = manager.getCookie(url);
            if (existing != null) {
                for (String cookie : existing.split(";")) {
                    int separator = cookie.indexOf('=');
                    if (separator <= 0) continue;
                    String name = cookie.substring(0, separator).trim();
                    // This expires the root-scoped cookie for this host, not
                    // cookies belonging to separate identity-provider sites.
                    manager.setCookie(origin,
                            name + "=; Max-Age=0; Path=/; Secure");
                }
                manager.flush();
            }
            WebStorage.getInstance().deleteOrigin(origin);
        } catch (Exception ignored) {
            logEvent("Nettoyage local partiel", url == null ? "" : Uri.parse(url).getHost());
        }
    }

    private void resetSiteSession(String siteId, String url, boolean httpBasic) {
        if (!SITE_PLANO_ID.equals(siteId)) {
            SiteProfile saved = findById(loadSites(), siteId);
            if (saved != null && isBrowserPreferred(saved)) {
                new AlertDialog.Builder(this)
                        .setTitle("Session de " + saved.name)
                        .setMessage("Cette application s’ouvre dans un navigateur externe. "
                                + "Wonder Apps ne peut pas lire ni supprimer les cookies "
                                + "et sessions d’Edge, Chrome ou Samsung Internet. "
                                + "Pour te déconnecter, utilise le bouton du site "
                                + "ou les paramètres du navigateur concerné.")
                        .setPositiveButton("Compris", null)
                        .show();
                return;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Nettoyage local")
                .setMessage("Wonder Apps va nettoyer les cookies accessibles et le "
                        + "stockage Web de ce site, sans effacer les autres sites. "
                        + (httpBasic
                        ? "L’authentification HTTP Basic peut rester en cache dans "
                            + "Android : une déconnexion complète peut nécessiter "
                            + "la réinitialisation globale de la session navigateur. "
                        : "Les cookies d’autres chemins ou fournisseurs SSO "
                            + "peuvent persister. ")
                        + "Pour une déconnexion garantie, utilise la fonction "
                        + "Déconnexion du site quand elle existe.")
                .setPositiveButton("Nettoyer", (d,w) -> {
                    clearSiteSession(url, httpBasic);
                    if (activeSite != null && siteId != null && siteId.equals(activeSite.id)) {
                        web.stopLoading();
                        web.loadUrl("about:blank");
                    } else if (SITE_PLANO_ID.equals(siteId) && mode == Mode.PLANO) {
                        web.stopLoading();
                        web.loadUrl("about:blank");
                    }
                    clearTransientState();
                    logEvent("Nettoyage local", siteId);
                    Toast.makeText(this, "Nettoyage local effectué",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private Bitmap automaticShortcutIcon(String siteId, String name) {
        Bitmap custom = loadSiteLogoBitmap(siteId);
        if (custom != null) return custom;
        int size = 256;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(accent());
        canvas.drawRoundRect(0, 0, size, size, 52, 52, bgPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(112);
        String letter = name == null || name.isEmpty() ? "A" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter, size / 2f, y, textPaint);
        return bitmap;
    }

    private Bitmap quickActionIcon(String symbol) {
        final int size = 192;
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(Color.rgb(12, 12, 12));
        canvas.drawRoundRect(4, 4, size - 4, size - 4, 45, 45, panel);

        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(Color.WHITE);
        mark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mark.setTextAlign(Paint.Align.CENTER);
        mark.setTextSize(108);
        Paint.FontMetrics fm = mark.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(symbol, size / 2f, baseline, mark);
        return result;
    }

    private ShortcutInfo launcherShortcut(String id, String label, String action, String siteId,
                                              Bitmap bitmap, int rank) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (siteId != null) intent.putExtra("shortcut_site_id", siteId);

        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(this, id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntent(intent)
                .setRank(rank);

        if (bitmap != null) builder.setIcon(Icon.createWithBitmap(bitmap));
        else builder.setIcon(Icon.createWithBitmap(quickActionIcon(
                ACTION_ADD_SITE.equals(action) ? "+" :
                ACTION_FEEDBACK.equals(action) ? "✦" : "⚙")));
        return builder.build();
    }

    private void updateDynamicAppShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;

        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null) return;

        int max = Math.max(1, Math.min(4, manager.getMaxShortcutCountPerActivity()));
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        int rank = 0;

        SharedPreferences prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        List<SiteProfile> sites = loadSites();
        SiteProfile preferred = null;

        for (SiteProfile site : sites) {
            if (site.favorite) {
                preferred = site;
                break;
            }
        }

        if (preferred != null && shortcuts.size() < max) {
            shortcuts.add(launcherShortcut(
                    "quick_favorite",
                    preferred.name,
                    ACTION_OPEN_SITE,
                    preferred.id,
                    automaticShortcutIcon(preferred.id, preferred.name),
                    rank++));
        } else if (shortcuts.size() < max) {
            shortcuts.add(launcherShortcut(
                    "quick_plano",
                    "Plano",
                    ACTION_OPEN_SITE,
                    SITE_PLANO_ID,
                    automaticShortcutIcon(SITE_PLANO_ID, "Plano"),
                    rank++));
        }

        if (shortcuts.size() < max) {
            shortcuts.add(launcherShortcut(
                    "quick_add",
                    "Ajouter une application",
                    ACTION_ADD_SITE,
                    null,
                    null,
                    rank++));
        }

        if (shortcuts.size() < max) {
            shortcuts.add(launcherShortcut(
                    "quick_feedback",
                    "Proposer une idée",
                    ACTION_FEEDBACK,
                    null,
                    null,
                    rank++));
        }

        if (shortcuts.size() < max) {
            shortcuts.add(launcherShortcut(
                    "quick_settings",
                    "Réglages",
                    ACTION_SETTINGS,
                    null,
                    null,
                    rank++));
        }

        try {
            manager.setDynamicShortcuts(shortcuts);
        } catch (Exception ignored) {}
    }

    private void pinSiteShortcut(String siteId, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Les raccourcis personnalisés nécessitent Android 8 ou plus récent.", Toast.LENGTH_LONG).show();
            return;
        }

        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Le lanceur de ce téléphone ne permet pas les raccourcis épinglés.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent launch = new Intent(this, MainActivity.class);
        launch.setAction(ACTION_OPEN_SITE);
        launch.putExtra("shortcut_site_id", siteId);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        String safeId = (siteId == null ? "site" : siteId).replaceAll("[^a-zA-Z0-9._-]", "_");
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "wonderapps_" + safeId)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithBitmap(automaticShortcutIcon(siteId, name)))
                .setIntent(launch)
                .build();

        manager.requestPinShortcut(shortcut, null);
    }

    private LinearLayout dialogStack() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(8), dp(4), dp(8), dp(4));
        return stack;
    }

    private LinearLayout settingsRow(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(round(surface2(), 14, border()));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(primary());
        row.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(12.5f);
            s.setTextColor(secondary());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.setMargins(0, dp(3), 0, 0);
            s.setLayoutParams(slp);
            row.addView(s);
        }

        return row;
    }

    private TextView smallNote(String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(12.5f);
        note.setTextColor(secondary());
        note.setPadding(dp(8), dp(8), dp(8), dp(6));
        return note;
    }

    private File customLogoFile() {
        return new File(getFilesDir(), "wonder_apps_custom_logo.png");
    }

    private void loadAppLogo() {
        if (avatar == null) return;
        avatar.setImageResource(R.drawable.ic_launcher_foreground);
        avatar.setBackground(round(Color.rgb(8,8,8), 12, Color.TRANSPARENT));
        avatar.setPadding(dp(3), dp(3), dp(3), dp(3));
    }

    private File siteLogoFile(String siteId) {
        String safe = (siteId == null ? "site" : siteId).replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(getFilesDir(), "site_logo_" + safe + ".png");
    }

    private Bitmap loadSiteLogoBitmap(String siteId) {
        File file = siteLogoFile(siteId);
        if (!file.exists()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private View siteIconView(String siteId, String name) {
        Bitmap bitmap = loadSiteLogoBitmap(siteId);
        if (bitmap != null) {
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setImageBitmap(bitmap);
            icon.setBackground(round(surface2(), 14, border()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
            lp.setMargins(0, 0, dp(12), 0);
            icon.setLayoutParams(lp);
            return icon;
        }

        TextView icon = new TextView(this);
        icon.setText(name == null || name.isEmpty() ? "A" : name.substring(0, 1).toUpperCase(Locale.ROOT));
        icon.setTextSize(20);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(accent(), 14, Color.TRANSPARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(0, 0, dp(12), 0);
        icon.setLayoutParams(lp);
        return icon;
    }

    private void chooseSiteLogo(String siteId) {
        pendingSiteLogoId = siteId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK_SITE_LOGO);
    }

    private void savePickedSiteLogo(Uri uri, String siteId) {
        if (siteId == null || siteId.isEmpty()) return;

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Image inaccessible");

            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IOException("Image invalide");

            int max = 768;
            if (bitmap.getWidth() > max || bitmap.getHeight() > max) {
                float ratio = Math.min((float) max / bitmap.getWidth(), (float) max / bitmap.getHeight());
                bitmap = Bitmap.createScaledBitmap(
                        bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                        Math.max(1, Math.round(bitmap.getHeight() * ratio)),
                        true);
            }

            try (OutputStream out = new FileOutputStream(siteLogoFile(siteId))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out);
            }

            if (onHome) rebuildHome();
            Toast.makeText(this, "Logo de l’application mis à jour", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Logo non modifié")
                    .setMessage("L’image n’a pas pu être utilisée. Choisis une image PNG, JPG ou WEBP classique.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void resetSiteLogo(String siteId) {
        File file = siteLogoFile(siteId);
        if (file.exists()) file.delete();
        if (onHome) rebuildHome();
        Toast.makeText(this, "Logo automatique restauré", Toast.LENGTH_SHORT).show();
    }

    private void pinCustomHomeShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Cette fonction nécessite Android 8 ou plus récent.", Toast.LENGTH_LONG).show();
            return;
        }

        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Le lanceur de ce téléphone ne permet pas les raccourcis personnalisés.", Toast.LENGTH_LONG).show();
            return;
        }

        Bitmap bitmap = null;
        File file = customLogoFile();
        if (file.exists()) bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.app_icon_photo);

        Intent launch = new Intent(this, MainActivity.class);
        launch.setAction(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);

        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "wonder_apps_custom_home")
                .setShortLabel("Wonder Apps")
                .setLongLabel("Wonder Apps")
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(launch)
                .build();

        manager.requestPinShortcut(shortcut, null);
    }

    private void showSettingsCenter() {
        LinearLayout stack = dialogStack();

        stack.addView(settingsRow(
                "À propos de Wonder Apps",
                "Pourquoi cette application, sa citation et son créateur",
                v -> showAbout()));

        stack.addView(settingsRow(
                "Sécurité et déverrouillage",
                "Biométrie, code PIN et informations de sécurité",
                v -> showUnlockSettings()));

        stack.addView(settingsRow(
                "Mot de passe AD (session Windows)",
                "Mettre à jour le mot de passe mémorisé après un changement AD",
                v -> showAdPasswordInfo()));

        stack.addView(settingsRow(
                "Réinitialiser la session navigateur",
                "Effacer cookies et session Web sans supprimer tes identifiants",
                v -> confirmResetSession()));

        stack.addView(settingsRow(
                "Apparence",
                "Mode classique / sombre",
                v -> showAppearance()));

        stack.addView(settingsRow(
                "Sites",
                "Ajouter, modifier ou ré-analyser les applications enregistrées",
                v -> showSitesSettings()));

        stack.addView(settingsRow(
                "Sauvegarde / restauration",
                "Exporter ou réimporter une sauvegarde chiffrée",
                v -> showBackupMenu()));

        stack.addView(settingsRow(
                "Proposer une idée",
                "Envoyer une suggestion pour améliorer Wonder Apps",
                v -> showFeedbackDialog()));

        stack.addView(settingsRow(
                "Journal local",
                "Événements techniques utiles, sans mot de passe ni contenu des pages",
                v -> showLocalLog()));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(stack);

        new AlertDialog.Builder(this)
                .setTitle("Réglages")
                .setView(scroll)
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void showUnlockSettings() {
        String mode = pinManager.getMode();
        String current = PinManager.MODE_BIOMETRIC.equals(mode)
                ? "Biométrie + code PIN de secours"
                : (PinManager.MODE_PIN.equals(mode) ? "Code PIN" : "Désactivé");

        LinearLayout stack = dialogStack();

        stack.addView(settingsRow(
                "Méthode de déverrouillage",
                "Actuel : " + current,
                v -> chooseUnlockMethod(false, null)));

        stack.addView(settingsRow(
                "Modifier le code PIN",
                "Changer le code PIN Wonder Apps",
                v -> showPinSetup(() ->
                        Toast.makeText(this, "Code PIN mis à jour", Toast.LENGTH_SHORT).show())));

        int lockSeconds = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(KEY_AUTO_LOCK_SECONDS, 60);
        String lockLabel = lockSeconds == 0 ? "Jamais"
                : (lockSeconds == 30 ? "30 secondes"
                : (lockSeconds == 60 ? "1 minute" : "5 minutes"));
        stack.addView(settingsRow(
                "Verrouillage automatique",
                "Actuel : " + lockLabel,
                v -> showAutoLockSettings()));

        stack.addView(settingsRow(
                "Informations de sécurité",
                "Comprendre comment tes données sont protégées",
                v -> showSecurity()));

        new AlertDialog.Builder(this)
                .setTitle("Sécurité et déverrouillage")
                .setView(stack)
                .setNegativeButton("Retour", null)
                .show();
    }

    private void showAutoLockSettings() {
        String[] labels = {"30 secondes", "1 minute", "5 minutes", "Jamais"};
        int[] values = {30, 60, 300, 0};
        int current = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt(KEY_AUTO_LOCK_SECONDS, 60);
        int selected = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) selected = i;
        }

        final int[] choice = {selected};
        new AlertDialog.Builder(this)
                .setTitle("Verrouillage automatique")
                .setSingleChoiceItems(labels, selected, (d, which) -> choice[0] = which)
                .setPositiveButton("Enregistrer", (d,w) -> {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE)
                            .edit()
                            .putInt(KEY_AUTO_LOCK_SECONDS, values[choice[0]])
                            .apply();
                    Toast.makeText(this, "Délai de verrouillage mis à jour", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void chooseUnlockMethod(boolean onboarding, Runnable after) {
        String current = pinManager.getMode();

        LinearLayout content = dialogStack();
        content.addView(smallNote("Choisis comment protéger l’ouverture de Wonder Apps. Avec la biométrie, le code PIN reste toujours disponible en secours."));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(8), dp(4), dp(8), dp(4));

        RadioButton biometric = new RadioButton(this);
        biometric.setText("Biométrie + code PIN de secours");
        biometric.setTextSize(15);
        biometric.setTextColor(primary());
        biometric.setTag(PinManager.MODE_BIOMETRIC);

        RadioButton pin = new RadioButton(this);
        pin.setText("Code PIN");
        pin.setTextSize(15);
        pin.setTextColor(primary());
        pin.setTag(PinManager.MODE_PIN);

        RadioButton disabled = new RadioButton(this);
        disabled.setText("Désactivé");
        disabled.setTextSize(15);
        disabled.setTextColor(primary());
        disabled.setTag(PinManager.MODE_DISABLED);

        group.addView(biometric);
        group.addView(pin);
        group.addView(disabled);

        if (PinManager.MODE_PIN.equals(current)) pin.setChecked(true);
        else if (PinManager.MODE_DISABLED.equals(current)) disabled.setChecked(true);
        else biometric.setChecked(true);

        content.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Méthode de déverrouillage")
                .setView(content)
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton(onboarding ? null : "Annuler", null)
                .create();

        dialog.setCancelable(!onboarding);
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            RadioButton selected = content.findViewById(group.getCheckedRadioButtonId());
            if (selected == null) {
                Toast.makeText(this, "Choisis une méthode", Toast.LENGTH_SHORT).show();
                return;
            }

            String selectedMode = String.valueOf(selected.getTag());

            Runnable saveAndContinue = () -> {
                pinManager.setMode(selectedMode);
                dialog.dismiss();
                if (after != null) after.run();
            };

            if (PinManager.MODE_DISABLED.equals(selectedMode)) {
                saveAndContinue.run();
            } else if (!pinManager.hasPin()) {
                showPinSetup(saveAndContinue);
            } else {
                saveAndContinue.run();
            }
        }));
        dialog.show();
    }

    private void showAdPasswordInfo() {
        new AlertDialog.Builder(this)
                .setTitle("Mot de passe AD / session Windows")
                .setMessage("À utiliser après avoir changé ton mot de passe Windows / Active Directory. "
                        + "Wonder Apps met uniquement à jour le mot de passe mémorisé pour les connexions automatiques : "
                        + "cela ne modifie pas ton mot de passe dans l’Active Directory.")
                .setPositiveButton("Mettre à jour", (d,w) -> editAdPassword())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmResetSession() {
        new AlertDialog.Builder(this)
                .setTitle("Réinitialiser la session navigateur")
                .setMessage("Cette action efface les cookies, le cache Web et les authentifications de session. "
                        + "Elle peut être utile si un site reste bloqué, affiche une ancienne session ou ne se reconnecte plus correctement.\n\n"
                        + "Tes identifiants enregistrés dans Wonder Apps ne sont pas supprimés.")
                .setPositiveButton("Réinitialiser", (d,w) -> resetSessionToHome())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showSitesSettings() {
        List<SiteProfile> sites = loadSites();
        LinearLayout stack = dialogStack();

        stack.addView(settingsRow(
                "+ Ajouter un site",
                "Wonder Apps analysera automatiquement sa méthode de connexion",
                v -> editSiteAddress(null)));

        stack.addView(settingsRow(
                "Réorganiser les applications",
                "Glisser-déposer les applications ajoutées pour changer leur ordre",
                v -> showReorderApps()));

        stack.addView(settingsRow(
                "Plano",
                "Application configurée en connexion automatique",
                v -> showPlanoActions()));

        for (SiteProfile site : sites) {
            final SiteProfile current = site;
            stack.addView(settingsRow(
                    site.name,
                    authLabel(site.authType),
                    v -> showSiteActions(current)));
        }

        new AlertDialog.Builder(this)
                .setTitle("Sites")
                .setView(stack)
                .setNegativeButton("Retour", null)
                .show();
    }

    private void showAppearance() {
        LinearLayout content = dialogStack();

        TextView themeTitle = new TextView(this);
        themeTitle.setText("Thème");
        themeTitle.setTextSize(15);
        themeTitle.setTextColor(primary());
        themeTitle.setPadding(dp(8), dp(4), dp(8), dp(2));
        content.addView(themeTitle);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(8), 0, dp(8), dp(8));

        RadioButton classic = new RadioButton(this);
        classic.setText("Classique");
        classic.setTextSize(15);
        classic.setTextColor(primary());
        classic.setTag("light");

        RadioButton dark = new RadioButton(this);
        dark.setText("Sombre");
        dark.setTextSize(15);
        dark.setTextColor(primary());
        dark.setTag("dark");

        group.addView(classic);
        group.addView(dark);

        if (darkMode) dark.setChecked(true);
        else classic.setChecked(true);

        content.addView(group);
        content.addView(smallNote("Les logos sont désormais personnalisables directement depuis chaque application. Le changement de thème ne ferme pas le site actuellement ouvert."));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Apparence")
                .setView(content)
                .setPositiveButton("Appliquer le thème", null)
                .setNegativeButton("Fermer", null)
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            RadioButton selected = content.findViewById(group.getCheckedRadioButtonId());
            if (selected == null) return;
            boolean nextDark = "dark".equals(String.valueOf(selected.getTag()));
            applyThemeWithoutRestart(nextDark);
            d.dismiss();
        }));
        d.show();
    }

    private void applyThemeWithoutRestart(boolean nextDark) {
        darkMode = nextDark;
        getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK, nextDark)
                .apply();

        setTheme(darkMode
                ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);

        root.setBackgroundColor(bg());
        topBar.setBackgroundColor(surface());
        separator.setBackgroundColor(border());
        homeScroll.setBackgroundColor(bg());
        headerTitle.setTextColor(primary());
        status.setTextColor(secondary());

        TextView[] actions = {homeButton, refreshButton, menuButton};
        for (TextView action : actions) {
            action.setTextColor(primary());
            action.setBackground(round(surface2(), 12, Color.TRANSPARENT));
        }

        updateSystemBars();

        if (onHome) {
            rebuildHome();
        }
    }

    private boolean isInternalHttp(SiteProfile site) {
        if (site == null || site.url == null) return false;
        Uri uri = Uri.parse(site.url);
        return SiteRoutingPolicy.isAllowedInternalHttp(uri.getScheme(), uri.getHost());
    }

    private void editSiteAddress(SiteProfile existing) {
        boolean edit = existing != null;

        EditText name = input("Nom du site", false);
        EditText url = input("https://… ou http://site.wonderbox.vpn/…", false);
        EditText category = input("Catégorie (facultatif) — ex. IT, RH, Support", false);
        CheckBox automatic = securityCheck("Connexion automatique (facultatif)",
                "Wonder Apps peut enregistrer les identifiants de ce site sur ce téléphone.",
                !edit || existing.autoConnect);
        CheckBox twoSteps = securityCheck("Deux étapes de connexion, comme Plano / Nova",
                "Accès au site (HTTP Basic), puis formulaire professionnel : "
                        + "chaque étape possède ses identifiants.",
                edit && "BASIC_FORM".equals(existing.authType));
        twoSteps.setEnabled(automatic.isChecked());
        automatic.setOnCheckedChangeListener((button, checked) -> {
            twoSteps.setEnabled(checked);
            if (!checked) twoSteps.setChecked(false);
        });

        if (edit) {
            name.setText(existing.name);
            url.setText(existing.url);
            category.setText(existing.category);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(edit ? "Modifier le site" : "Ajouter un site")
                .setMessage("HTTPS reste le mode sécurisé normal. Les sites HTTP "
                        + "internes Wonderbox accessibles par VPN peuvent être ajoutés "
                        + "uniquement comme raccourcis vers un navigateur externe.")
                .setView(form(name, url, category, automatic, twoSteps))
                .setPositiveButton(edit ? "Enregistrer" : "Ajouter l’application", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String siteName = name.getText().toString().trim();
                    String entered = url.getText().toString().trim();
                    if (siteName.isEmpty() || entered.isEmpty()) {
                        Toast.makeText(this, "Nom et adresse requis", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!entered.regionMatches(true, 0, "http://", 0, 7)
                            && !entered.regionMatches(true, 0, "https://", 0, 8)) {
                        entered = "https://" + entered;
                    }

                    Uri parsed = Uri.parse(entered);
                    String scheme = parsed.getScheme();
                    String host = parsed.getHost();
                    String authority = parsed.getEncodedAuthority();
                    boolean internalHttp =
                            SiteRoutingPolicy.isAllowedInternalHttp(scheme, host);

                    if (host == null || authority == null || authority.contains("@")
                            || !("https".equalsIgnoreCase(scheme) || internalHttp)) {
                        url.setError("HTTPS requis, sauf site interne *.wonderbox.vpn en HTTP");
                        Toast.makeText(this,
                                "HTTP réservé aux sites internes Wonderbox VPN ; HTTPS pour les autres.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (isProviderEntryHost(host)) {
                        url.setError("Saisis l’adresse du site, pas une page de connexion");
                        new AlertDialog.Builder(this)
                                .setTitle("Adresse de l’application")
                                .setMessage("Enregistre l’adresse de départ de " + siteName
                                        + ", et non la page de connexion Microsoft ou Atlassian.")
                                .setPositiveButton("Corriger", null)
                                .show();
                        return;
                    }

                    final String finalUrl = entered;
                    final String finalCategory = category.getText().toString().trim();
                    final boolean wantsAuto = !internalHttp && automatic.isChecked();
                    final boolean wantsTwoSteps = wantsAuto && twoSteps.isChecked();
                    if (wantsTwoSteps && SiteRoutingPolicy.isAtlassianCloudHost(host)) {
                        url.setError("Utilise la connexion professionnelle Atlassian / Microsoft");
                        Toast.makeText(this, "La connexion Jira/Atlassian passe par le SSO, "
                                + "pas par le parcours Plano/Nova.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Runnable persist = () -> {
                        List<SiteProfile> sites = loadSites();
                        SiteProfile site;
                        if (edit) {
                            site = findById(sites, existing.id);
                            if (site == null) site = existing;

                            boolean addressChanged = !finalUrl.equalsIgnoreCase(site.url);
                            boolean modeChanged = wantsTwoSteps
                                    != "BASIC_FORM".equals(site.authType);
                            site.name = siteName;
                            site.url = finalUrl;
                            site.category = finalCategory;
                            site.autoConnect = wantsAuto;

                            if (addressChanged) {
                                site.authType = "PENDING";
                                site.loginHost = "";
                                site.username = "";
                                site.password = "";
                                site.basicUsername = "";
                                site.basicPassword = "";
                            }
                            if (wantsTwoSteps) {
                                site.authType = "BASIC_FORM";
                                site.loginHost = parsed.getHost();
                                // Existing credentials are preserved only for
                                // an unchanged URL using the same two-step mode.
                                if (modeChanged) {
                                    site.username = "";
                                    site.password = "";
                                    site.basicUsername = "";
                                    site.basicPassword = "";
                                }
                            } else if (modeChanged && !addressChanged) {
                                site.authType = "PENDING";
                                site.loginHost = "";
                                site.basicUsername = "";
                                site.basicPassword = "";
                            }
                        } else {
                            site = new SiteProfile();
                            site.id = UUID.randomUUID().toString();
                            site.name = siteName;
                            site.url = finalUrl;
                            site.category = finalCategory;
                            site.authType = wantsTwoSteps ? "BASIC_FORM" : "PENDING";
                            site.autoConnect = wantsAuto;
                            site.loginHost = wantsTwoSteps ? parsed.getHost() : "";
                            sites.add(site);
                        }

                        if (!site.autoConnect && "PENDING".equals(site.authType)) {
                            site.authType = "NONE";
                        }

                        if (internalHttp) {
                            // A VPN does not convert the origin into HTTPS.
                            // Never place passwords in our WebView or JS for HTTP.
                            site.authType = "NONE";
                            site.autoConnect = false;
                            site.loginHost = "";
                            site.username = "";
                            site.password = "";
                            site.basicUsername = "";
                            site.basicPassword = "";
                            site.openingMode = "BROWSER";
                            site.vpnRequired = true;
                            site.lockOnExit = false;
                        }

                        saveSites(sites);
                        dialog.dismiss();
                        if (isBrowserPreferred(site) || !site.autoConnect) {
                            showHome("Application enregistrée");
                            Toast.makeText(this, internalHttp
                                            ? "Site interne ajouté • connexion VPN nécessaire"
                                            : site.name + " est prêt dans Wonder Apps",
                                    Toast.LENGTH_LONG).show();
                        } else if (wantsTwoSteps) {
                            showHome("Application ajoutée");
                            editSiteCredentials(site);
                        } else {
                            analyzeSite(site);
                        }
                    };

                    if (internalHttp) {
                        new AlertDialog.Builder(this)
                                .setTitle("Site interne HTTP via VPN")
                                .setMessage("Wonder Apps ajoutera ce site comme raccourci "
                                        + "vers le navigateur du téléphone et demandera "
                                        + "la connexion VPN. HTTP n’est pas chiffré par "
                                        + "le site : même avec un VPN, sa sécurité de bout "
                                        + "en bout n’est pas équivalente à HTTPS. "
                                        + "Aucun identifiant ni mot de passe ne sera "
                                        + "stocké ou injecté par Wonder Apps pour ce site.")
                                .setPositiveButton("Ajouter ce site", (d,w) -> persist.run())
                                .setNegativeButton("Annuler", null)
                                .show();
                    } else {
                        persist.run();
                    }
                }));

        dialog.show();
    }

    private void showSiteActions(SiteProfile site) {
        if (site == null) return;

        String favoriteLabel = site.favorite ? "Retirer des favoris" : "Ajouter aux favoris";
        String[] actions = {
                "Ouvrir",
                "Modifier le nom / l’adresse",
                "Connexion et identifiants",
                "Mode d’ouverture",
                "Changer le logo",
                favoriteLabel,
                "Sécurité de cette application",
                "Réinitialiser sa session",
                "Créer / recréer le raccourci Android",
                "Tester l’accès",
                isInternalHttp(site) ? "Informations HTTP et VPN" : "Ré-analyser la connexion",
                "Signaler un problème",
                "Supprimer"
        };

        new AlertDialog.Builder(this)
                .setTitle(site.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) openCustom(site);
                    else if (which == 1) editSiteAddress(site);
                    else if (which == 2) {
                        if (isInternalHttp(site)) {
                            showInternalHttpInfo(site);
                        } else if (isAtlassianCloudSite(site)) {
                            showProfessionalConnectionInfo(site);
                        } else if (isBrowserPreferred(site)) {
                            showSiteOpeningMode(site);
                        } else editSiteCredentials(site);
                    }
                    else if (which == 3) showSiteOpeningMode(site);
                    else if (which == 4) showSiteLogoMenu(site.id, site.name);
                    else if (which == 5) toggleSiteFavorite(site);
                    else if (which == 6) showSiteSecurity(site);
                    else if (which == 7) resetSiteSession(site.id, site.url,
                            "BASIC".equals(site.authType) || "BASIC_FORM".equals(site.authType));
                    else if (which == 8) pinSiteShortcut(site.id, site.name);
                    else if (which == 9) testSiteAccess(site.name, site.url, site.vpnRequired);
                    else if (which == 10) {
                        if (isInternalHttp(site)) showInternalHttpInfo(site);
                        else confirmReanalyzeSite(site);
                    }
                    else if (which == 11) showFeedbackDialog("Problème sur " + site.name + " :\n");
                    else confirmDeleteSite(site);
                })
                .show();
    }

    private void showInternalHttpInfo(SiteProfile site) {
        new AlertDialog.Builder(this)
                .setTitle("Accès interne • " + site.name)
                .setMessage("Ce site utilise HTTP sur le réseau Wonderbox. "
                        + "Active le VPN professionnel avant de l’ouvrir. "
                        + "Wonder Apps le lance uniquement dans ton navigateur "
                        + "et ne conserve aucun mot de passe pour lui. "
                        + "Le VPN ne remplace pas le chiffrement HTTPS de bout en bout.")
                .setPositiveButton("Ouvrir", (d,w) -> openCustom(site))
                .setNeutralButton("Choisir le navigateur",
                        (d,w) -> showSiteOpeningMode(site))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void showSiteOpeningMode(SiteProfile site) {
        List<SiteProfile> sites = loadSites();
        SiteProfile saved = findById(sites, site.id);
        if (saved == null) return;

        boolean http = isInternalHttp(saved);
        String[] modes = http
                ? new String[]{
                    "Navigateur par défaut du téléphone",
                    "Samsung Internet",
                    "Microsoft Edge",
                    "Google Chrome"
                }
                : new String[]{
                    "Automatique • Edge si disponible pour les connexions pro",
                    "Ouvrir directement dans Wonder Apps",
                    "Microsoft Edge",
                    "Google Chrome",
                    "Samsung Internet",
                    "Navigateur par défaut du téléphone"
                };
        String[] values = http
                ? new String[]{"BROWSER", "SAMSUNG", "EDGE", "CHROME"}
                : new String[]{"AUTO", "IN_APP", "EDGE", "CHROME", "SAMSUNG", "BROWSER"};
        int initial = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(saved.openingMode)) initial = i;
        }
        final int[] selection = {initial};

        new AlertDialog.Builder(this)
                .setTitle((http ? "Navigateur • " : "Ouvrir ") + saved.name)
                .setSingleChoiceItems(modes, initial, (d, which) -> selection[0] = which)
                .setPositiveButton("Enregistrer", (d,w) -> {
                    saved.openingMode = values[selection[0]];
                    saveSites(sites);
                    if (activeSite != null && saved.id.equals(activeSite.id)) {
                        activeSite.openingMode = saved.openingMode;
                    }
                    if (onHome) rebuildHome();
                    Toast.makeText(this, "Ouverture configurée pour " + saved.name,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showProfessionalConnectionInfo(SiteProfile site) {
        boolean savedCredentials = !site.username.isEmpty() || !site.password.isEmpty();

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Connexion professionnelle • " + site.name)
                .setMessage("Wonder Apps ouvre le site dans un navigateur compatible. "
                        + "Sur Atlassian, choisis « Microsoft » si c’est ta méthode "
                        + "habituelle, ou saisis ton e-mail professionnel puis « Continuer » "
                        + "si votre entreprise utilise la connexion unique. "
                        + "La connexion et la MFA sont gérées par Atlassian / Microsoft : "
                        + "ton mot de passe n’est pas transmis par Wonder Apps.")
                .setPositiveButton("Ouvrir " + site.name,
                        (d,w) -> openCustom(site))
                .setNegativeButton("Fermer", null);

        if (savedCredentials) {
            dialog.setNeutralButton("Effacer anciens identifiants",
                    (d,w) -> new AlertDialog.Builder(this)
                            .setTitle("Effacer les identifiants de " + site.name + " ?")
                            .setMessage("Les anciens identifiants stockés par Wonder Apps "
                                    + "ne sont pas nécessaires à la connexion Microsoft. "
                                    + "Cette action ne modifie pas ton compte professionnel.")
                            .setPositiveButton("Effacer", (confirm,which) -> {
                                List<SiteProfile> sites = loadSites();
                                SiteProfile saved = findById(sites, site.id);
                                if (saved == null) return;
                                saved.username = "";
                                saved.password = "";
                                saveSites(sites);
                                site.username = "";
                                site.password = "";
                                Toast.makeText(this, "Anciens identifiants effacés",
                                        Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Annuler", null)
                            .show());
        } else {
            dialog.setNeutralButton("Mode d’ouverture", (d,w) -> showSiteOpeningMode(site));
        }
        dialog.show();
    }

    private void showPlanoActions() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        boolean favorite = prefs.getBoolean(KEY_PLANO_FAVORITE, false);
        String[] actions = {
                "Ouvrir",
                "Identifiants Plano",
                "Changer le logo",
                favorite ? "Retirer des favoris" : "Ajouter aux favoris",
                "Sécurité de cette application",
                "Réinitialiser sa session",
                "Créer / recréer le raccourci Android",
                "Tester l’accès",
                "Signaler un problème"
        };

        new AlertDialog.Builder(this)
                .setTitle("Plano")
                .setItems(actions, (d, which) -> {
                    if (which == 0) openPlano();
                    else if (which == 1) editPlanoCredentials(false);
                    else if (which == 2) showSiteLogoMenu(SITE_PLANO_ID, "Plano");
                    else if (which == 3) togglePlanoFavorite();
                    else if (which == 4) showPlanoSecurity();
                    else if (which == 5) resetSiteSession(SITE_PLANO_ID, PLANO_URL, true);
                    else if (which == 6) pinSiteShortcut(SITE_PLANO_ID, "Plano");
                    else if (which == 7) testSiteAccess("Plano", PLANO_URL, false);
                    else showFeedbackDialog("Problème sur Plano :\n");
                })
                .show();
    }

    private void showSiteLogoMenu(String siteId, String name) {
        String[] choices = {"Choisir dans la galerie", "Revenir au logo automatique"};
        new AlertDialog.Builder(this)
                .setTitle("Logo • " + name)
                .setItems(choices, (d, which) -> {
                    if (which == 0) chooseSiteLogo(siteId);
                    else resetSiteLogo(siteId);
                })
                .show();
    }

    private void toggleSiteFavorite(SiteProfile site) {
        List<SiteProfile> sites = loadSites();
        SiteProfile saved = findById(sites, site.id);
        if (saved == null) return;
        saved.favorite = !saved.favorite;
        saveSites(sites);
        updateDynamicAppShortcuts();
        showHome(saved.favorite ? "Ajouté aux favoris" : "Retiré des favoris");
    }

    private void togglePlanoFavorite() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        boolean next = !prefs.getBoolean(KEY_PLANO_FAVORITE, false);
        prefs.edit().putBoolean(KEY_PLANO_FAVORITE, next).apply();
        updateDynamicAppShortcuts();
        showHome(next ? "Plano ajouté aux favoris" : "Plano retiré des favoris");
    }

    private CheckBox securityCheck(String title, String subtitle, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(title + (subtitle == null || subtitle.isEmpty() ? "" : "\n" + subtitle));
        box.setTextSize(14);
        box.setTextColor(primary());
        box.setChecked(checked);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        return box;
    }

    private void showSiteSecurity(SiteProfile site) {
        List<SiteProfile> sites = loadSites();
        SiteProfile saved = findById(sites, site.id);
        if (saved == null) return;

        LinearLayout stack = dialogStack();
        CheckBox bio = securityCheck("Toujours demander la biométrie",
                "Exige une validation biométrique à chaque ouverture.", saved.requireBiometric);
        CheckBox lock = securityCheck("Nettoyage local à la sortie",
                "Nettoie les cookies accessibles dans Wonder Apps. "
                        + "Ne déconnecte pas nécessairement les autres services.",
                saved.lockOnExit);
        if (isBrowserPreferred(saved)) {
            lock.setChecked(false);
            lock.setEnabled(false);
            lock.setText("Session gérée par le navigateur externe\\n"
                    + "Déconnecte-toi depuis le site ou le navigateur.");
        }
        CheckBox screen = securityCheck("Bloquer les captures d’écran",
                "Option par site, désactivée par défaut.", saved.blockScreenshots);
        CheckBox vpn = securityCheck("VPN / réseau interne requis",
                "Aide à ouvrir les outils accessibles uniquement via le réseau interne.",
                saved.vpnRequired || isInternalHttp(saved));
        if (isInternalHttp(saved)) {
            vpn.setChecked(true);
            vpn.setEnabled(false);
        }

        stack.addView(bio);
        stack.addView(lock);
        stack.addView(screen);
        stack.addView(vpn);
        stack.addView(smallNote("La protection biométrique par site permet aussi "
                + "le code PIN Wonder Apps en secours. Elle ne remplace pas "
                + "l’authentification du site lui-même."));

        new AlertDialog.Builder(this)
                .setTitle("Sécurité • " + saved.name)
                .setView(stack)
                .setPositiveButton("Enregistrer", (d,w) -> {
                    saved.requireBiometric = bio.isChecked();
                    saved.lockOnExit = !isBrowserPreferred(saved) && lock.isChecked();
                    saved.blockScreenshots = screen.isChecked();
                    saved.vpnRequired = isInternalHttp(saved) || vpn.isChecked();
                    saveSites(sites);
                    if (activeSite != null && saved.id.equals(activeSite.id)) {
                        activeSite.requireBiometric = saved.requireBiometric;
                        activeSite.lockOnExit = saved.lockOnExit;
                        activeSite.blockScreenshots = saved.blockScreenshots;
                        activeSite.vpnRequired = saved.vpnRequired;
                        applyScreenProtection(saved.blockScreenshots);
                    }
                    if (onHome) rebuildHome();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showPlanoSecurity() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        LinearLayout stack = dialogStack();

        CheckBox bio = securityCheck("Toujours demander la biométrie",
                "Exige une validation biométrique à chaque ouverture de Plano.",
                prefs.getBoolean(KEY_PLANO_REQUIRE_BIO, false));
        CheckBox lock = securityCheck("Nettoyage local à la sortie",
                "Nettoie les cookies accessibles de Plano ; l’accès HTTP Basic "
                        + "peut rester en cache dans Android.",
                prefs.getBoolean(KEY_PLANO_LOCK_EXIT, false));
        CheckBox screen = securityCheck("Bloquer les captures d’écran",
                "Empêche captures et aperçu dans les applications récentes.",
                prefs.getBoolean(KEY_PLANO_BLOCK_SCREEN, false));

        stack.addView(bio);
        stack.addView(lock);
        stack.addView(screen);
        stack.addView(smallNote("La biométrie par site permet le code PIN "
                + "Wonder Apps en secours. Le nettoyage local ne garantit pas "
                + "la déconnexion HTTP Basic côté serveur."));

        new AlertDialog.Builder(this)
                .setTitle("Sécurité • Plano")
                .setView(stack)
                .setPositiveButton("Enregistrer", (d,w) -> {
                    prefs.edit()
                            .putBoolean(KEY_PLANO_REQUIRE_BIO, bio.isChecked())
                            .putBoolean(KEY_PLANO_LOCK_EXIT, lock.isChecked())
                            .putBoolean(KEY_PLANO_BLOCK_SCREEN, screen.isChecked())
                            .apply();
                    if (mode == Mode.PLANO) applyScreenProtection(screen.isChecked());
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void testSiteAccess(String name, String urlValue, boolean vpnRequired) {
        Uri target = Uri.parse(urlValue);
        if (SiteRoutingPolicy.isAllowedInternalHttp(target.getScheme(), target.getHost())) {
            new AlertDialog.Builder(this)
                    .setTitle("Disponibilité • " + name)
                    .setMessage("Ce site HTTP interne est ouvert par le navigateur via VPN. "
                            + "Wonder Apps ne lance pas de requête HTTP non chiffrée "
                            + "en arrière-plan et ne peut pas confirmer ta session "
                            + "dans un navigateur externe. Active le VPN puis ouvre le site.")
                    .setPositiveButton("Ouvrir", (d,w) -> {
                        for (SiteProfile site : loadSites()) {
                            if (urlValue.equals(site.url)) {
                                openCustom(site);
                                return;
                            }
                        }
                    })
                    .setNegativeButton("Fermer", null)
                    .show();
            return;
        }
        Toast.makeText(this, "Test de disponibilité en cours…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            int code = -1;
            try {
                URL url = new URL(urlValue);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                code = connection.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            final int result = code;
            runOnUiThread(() -> {
                String message;
                if (result >= 200 && result < 400) {
                    message = "Le serveur répond (HTTP " + result + "). "
                            + "Cela confirme la disponibilité du site, "
                            + "pas ta connexion à ton compte.";
                } else if (result == 401 || result == 403) {
                    message = "Le serveur répond (HTTP " + result + "), "
                            + "mais l’accès nécessite une authentification "
                            + "ou une autorisation. Ce test ne se connecte pas à ta place.";
                } else if (result >= 400 && result < 500) {
                    message = "Le serveur a répondu (HTTP " + result + "). "
                            + "Vérifie l’adresse ou les droits d’accès : "
                            + "aucune session utilisateur n’a été testée.";
                } else if (vpnRequired) {
                    message = "Le site ne répond pas. Vérifie que le VPN ou le réseau interne est actif.";
                } else {
                    message = "Le site ne répond pas actuellement. Vérifie la connexion réseau ou l’adresse.";
                }

                new AlertDialog.Builder(this)
                        .setTitle("Disponibilité • " + name)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }, "WonderAppsSiteCheck").start();
    }

    private void showReorderApps() {
        List<SiteProfile> sites = loadSites();
        if (sites.size() < 2) {
            Toast.makeText(this, "Ajoute au moins deux applications pour les réorganiser.", Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout stack = dialogStack();
        stack.addView(smallNote("Maintiens une application puis dépose-la sur une autre. Plano reste épinglé en première position."));

        final AlertDialog[] holder = new AlertDialog[1];

        for (SiteProfile site : sites) {
            LinearLayout row = settingsRow("☰  " + site.name, siteStateLabel(site), null);
            row.setTag(site.id);
            row.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("site_id", String.valueOf(v.getTag()));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    v.startDragAndDrop(data, new View.DragShadowBuilder(v), null, 0);
                } else {
                    v.startDrag(data, new View.DragShadowBuilder(v), null, 0);
                }
                return true;
            });
            row.setOnDragListener((v, event) -> {
                if (event.getAction() != DragEvent.ACTION_DROP) return true;
                if (event.getClipData() == null || event.getClipData().getItemCount() == 0) return true;

                String draggedId = String.valueOf(event.getClipData().getItemAt(0).getText());
                String targetId = String.valueOf(v.getTag());
                if (draggedId.equals(targetId)) return true;

                List<SiteProfile> current = loadSites();
                SiteProfile dragged = findById(current, draggedId);
                SiteProfile target = findById(current, targetId);
                if (dragged == null || target == null) return true;

                int from = current.indexOf(dragged);
                int to = current.indexOf(target);
                current.remove(from);
                if (to > current.size()) to = current.size();
                current.add(to, dragged);
                saveSites(current);

                if (holder[0] != null) holder[0].dismiss();
                timer.postDelayed(this::showReorderApps, 120);
                return true;
            });
            stack.addView(row);
        }

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Réorganiser")
                .setView(stack)
                .setNegativeButton("Terminer", (d,w) -> showHome("Ordre enregistré"))
                .create();
        holder[0].show();
    }

    private void confirmReanalyzeSite(SiteProfile site) {
        new AlertDialog.Builder(this)
                .setTitle("Ré-analyser " + site.name)
                .setMessage("La ré-analyse permet à Wonder Apps de vérifier à nouveau comment ce site se connecte "
                        + "(formulaire, HTTP Basic, SSO, MFA ou accès direct).\n\n"
                        + "Utilise-la si le site a changé, si la connexion automatique ne fonctionne plus ou après une modification de sa page de connexion. "
                        + "L’analyse elle-même n’envoie aucun mot de passe.")
                .setPositiveButton("Ré-analyser", (d,w) -> analyzeSite(site))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void editSiteCredentials(SiteProfile site) {
        if (site == null) return;
        if (isInternalHttp(site)) {
            showInternalHttpInfo(site);
            return;
        }
        if (isAtlassianCloudSite(site)) {
            showProfessionalConnectionInfo(site);
            return;
        }

        if ("BASIC_FORM".equals(site.authType)) {
            editTwoStepCredentials(site);
            return;
        }

        if (!site.autoConnect) {
            new AlertDialog.Builder(this)
                    .setTitle("Connexion manuelle • " + site.name)
                    .setMessage("La connexion automatique est désactivée pour "
                            + site.name + ". Tu peux la réactiver dans « Modifier le site ».")
                    .setPositiveButton("Modifier le site", (d,w) -> editSiteAddress(site))
                    .setNegativeButton("Fermer", null)
                    .show();
            return;
        }

        if (!("FORM".equals(site.authType) || "BASIC".equals(site.authType))) {
            new AlertDialog.Builder(this)
                    .setTitle("Identifiants non nécessaires")
                    .setMessage("La méthode détectée est : " + authLabel(site.authType)
                            + ". Wonder Apps ne contourne pas le SSO ni la MFA.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        EditText user = input("Identifiant", false);
        EditText pass = input(site.password.isEmpty()
                ? "Mot de passe"
                : "Nouveau mot de passe (vide = conserver)", true);
        user.setText(site.username);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Identifiants • " + site.name)
                .setMessage("Les identifiants sont chiffrés localement et utilisés "
                        + "uniquement sur l’hôte de connexion configuré.")
                .setView(form(user, pass))
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String username = user.getText().toString().trim();
                    String password = pass.getText().toString();

                    if (username.isEmpty() || (site.password.isEmpty() && password.isEmpty())) {
                        Toast.makeText(this, "Identifiant et mot de passe requis",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    List<SiteProfile> sites = loadSites();
                    SiteProfile saved = findById(sites, site.id);
                    if (saved == null) return;
                    saved.username = username;
                    if (!password.isEmpty()) saved.password = password;
                    saveSites(sites);
                    dialog.dismiss();
                    showHome("Identifiants configurés pour " + saved.name);
                }));
        dialog.show();
    }

    private void editTwoStepCredentials(SiteProfile site) {
        if (site == null || isInternalHttp(site) || isAtlassianCloudSite(site)
                || !"BASIC_FORM".equals(site.authType)) return;

        EditText firstUser = input("Étape 1 • Identifiant d’accès au site", false);
        EditText firstPass = input(site.basicPassword.isEmpty()
                ? "Étape 1 • Mot de passe d’accès"
                : "Étape 1 • Nouveau mot de passe (vide = conserver)", true);
        EditText secondUser = input("Étape 2 • Identifiant professionnel / AD", false);
        EditText secondPass = input(site.password.isEmpty()
                ? "Étape 2 • Mot de passe professionnel / AD"
                : "Étape 2 • Nouveau mot de passe (vide = conserver)", true);
        firstUser.setText(site.basicUsername);
        secondUser.setText(site.username);

        ScrollView content = new ScrollView(this);
        content.setFillViewport(false);
        content.addView(form(firstUser, firstPass, secondUser, secondPass));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Connexion en deux étapes • " + site.name)
                .setMessage("Comme Plano : accès HTTPS au site, puis formulaire "
                        + "professionnel. Les quatre valeurs sont propres à "
                        + site.name + " et chiffrées localement ; les mots de passe "
                        + "ne sont jamais copiés depuis Plano.")
                .setView(content)
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String user1 = firstUser.getText().toString().trim();
                    String pass1 = firstPass.getText().toString();
                    String user2 = secondUser.getText().toString().trim();
                    String pass2 = secondPass.getText().toString();

                    if (user1.isEmpty() || user2.isEmpty()
                            || (site.basicPassword.isEmpty() && pass1.isEmpty())
                            || (site.password.isEmpty() && pass2.isEmpty())) {
                        Toast.makeText(this, "Renseigne les deux identifiants et mots de passe",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    List<SiteProfile> sites = loadSites();
                    SiteProfile saved = findById(sites, site.id);
                    if (saved == null || !"BASIC_FORM".equals(saved.authType)
                            || isInternalHttp(saved)) return;

                    saved.basicUsername = user1;
                    if (!pass1.isEmpty()) saved.basicPassword = pass1;
                    saved.username = user2;
                    if (!pass2.isEmpty()) saved.password = pass2;
                    saved.autoConnect = true;
                    saved.loginHost = Uri.parse(saved.url).getHost();
                    saveSites(sites);
                    dialog.dismiss();
                    showHome("Connexion en deux étapes configurée pour " + saved.name);
                }));
        dialog.show();
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
                    File logo = siteLogoFile(site.id);
                    if (logo.exists()) logo.delete();
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
        editPlanoCredentials(first, null);
    }

    private void editPlanoCredentials(boolean first, Runnable afterSave) {
        EditText bu = input("Identifiant accès 1", false);
        EditText bp = input(first ? "Mot de passe accès 1" : "Nouveau mot de passe accès 1 (vide = conserver)", true);
        EditText au = input("Login AD", false);
        EditText ap = input(first ? "Mot de passe AD" : "Nouveau mot de passe AD (vide = conserver)", true);

        bu.setText(secrets.get(CredentialStore.BASIC_USER));
        au.setText(secrets.get(CredentialStore.AD_USER));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(first ? "Configurer Plano" : "Identifiants Plano")
                .setMessage(first
                        ? "Plano utilise deux étapes de connexion. Ces identifiants seront chiffrés localement sur ce téléphone."
                        : null)
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

            if (afterSave != null) {
                afterSave.run();
            } else {
                showHome("Plano configuré");
            }
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

    private void showBackupMenu() {
        LinearLayout stack = dialogStack();
        stack.addView(smallNote(
                "La sauvegarde contient tes sites, leurs réglages, leurs logos, l’ordre d’affichage, les favoris, le thème et les identifiants enregistrés. "
                        + "Le fichier est chiffré avec un mot de passe que toi seul connais. Le code PIN Wonder Apps n’est jamais exporté."));

        stack.addView(settingsRow(
                "Exporter une sauvegarde",
                "Créer un fichier .wonderbackup chiffré pour un autre téléphone",
                v -> startExport()));

        stack.addView(settingsRow(
                "Importer une sauvegarde",
                "Restaurer un fichier .wonderbackup existant",
                v -> startImport(false)));

        new AlertDialog.Builder(this)
                .setTitle("Sauvegarde / restauration")
                .setView(stack)
                .setNegativeButton("Retour", null)
                .show();
    }

    private void startExport() {
        EditText p1 = input("Mot de passe de sauvegarde", true);
        EditText p2 = input("Confirmer le mot de passe", true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Protéger la sauvegarde")
                .setMessage("Choisis un mot de passe d’au moins 8 caractères. Il sera indispensable pour restaurer le fichier sur un autre téléphone.")
                .setView(form(p1, p2))
                .setPositiveButton("Choisir l’emplacement", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = p1.getText().toString();
            String b = p2.getText().toString();

            if (a.length() < 8) {
                p1.setError("8 caractères minimum");
                return;
            }

            if (!a.equals(b)) {
                p2.setError("Les mots de passe ne correspondent pas");
                return;
            }

            pendingBackupPassword = a;
            dialog.dismiss();

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "WonderApps-backup.wonderbackup");
            startActivityForResult(intent, REQ_EXPORT_BACKUP);
        }));
        dialog.show();
    }

    private void startImport(boolean fromOnboarding) {
        importFromOnboarding = fromOnboarding;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT_BACKUP);
    }

    private JSONObject createBackupPayload() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("format", "wonderapps-data");
        payload.put("version", 2);
        payload.put("basicUser", secrets.get(CredentialStore.BASIC_USER));
        payload.put("basicPass", secrets.get(CredentialStore.BASIC_PASS));
        payload.put("adUser", secrets.get(CredentialStore.AD_USER));
        payload.put("adPass", secrets.get(CredentialStore.AD_PASS));

        String sites = secrets.get(CUSTOM_SITES);
        if (sites.isEmpty()) sites = secrets.get("custom_sites_v1");
        payload.put("sites", sites.isEmpty() ? "[]" : sites);
        payload.put("darkMode", darkMode);

        SharedPreferences prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        payload.put("planoFavorite", prefs.getBoolean(KEY_PLANO_FAVORITE, false));
        payload.put("planoRequireBiometric", prefs.getBoolean(KEY_PLANO_REQUIRE_BIO, false));
        payload.put("planoLockOnExit", prefs.getBoolean(KEY_PLANO_LOCK_EXIT, false));
        payload.put("planoBlockScreenshots", prefs.getBoolean(KEY_PLANO_BLOCK_SCREEN, false));
        payload.put("autoLockSeconds", prefs.getInt(KEY_AUTO_LOCK_SECONDS, 60));

        JSONObject logos = new JSONObject();
        List<String> ids = new ArrayList<>();
        ids.add(SITE_PLANO_ID);
        for (SiteProfile site : loadSites()) ids.add(site.id);

        for (String id : ids) {
            File logo = siteLogoFile(id);
            if (!logo.exists()) continue;
            try (InputStream in = new FileInputStream(logo);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                logos.put(id, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            }
        }
        payload.put("siteLogos", logos);

        return payload;
    }

    private void writeBackup(Uri uri, String password) {
        try {
            String encrypted = BackupCrypto.encrypt(createBackupPayload().toString(), password);
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Impossible d’ouvrir le fichier");
                out.write(encrypted.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            Toast.makeText(this, "Sauvegarde chiffrée créée", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Échec de la sauvegarde")
                    .setMessage("La sauvegarde n’a pas pu être créée.")
                    .setPositiveButton("OK", null)
                    .show();
        } finally {
            pendingBackupPassword = null;
        }
    }

    private String readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Impossible d’ouvrir le fichier");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void promptImportPassword(Uri uri, boolean fromOnboarding) {
        EditText password = input("Mot de passe de la sauvegarde", true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Déchiffrer la sauvegarde")
                .setMessage("Saisis le mot de passe utilisé lors de l’export.")
                .setView(form(password))
                .setPositiveButton("Importer", null)
                .setNegativeButton("Annuler", (d,w) -> {
                    if (fromOnboarding) timer.postDelayed(this::startOnboarding, 200);
                })
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.isEmpty()) {
                password.setError("Requis");
                return;
            }

            try {
                String encrypted = readAll(uri);
                String plain = BackupCrypto.decrypt(encrypted, value);
                JSONObject payload = new JSONObject(plain);
                restoreBackupPayload(payload);
                dialog.dismiss();

                if (fromOnboarding) {
                    chooseUnlockMethod(true, this::finishImportedOnboarding);
                } else {
                    showHome("Sauvegarde restaurée");
                    Toast.makeText(this, "Import terminé", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                password.setText("");
                password.setError("Mot de passe incorrect ou fichier invalide");
            }
        }));
        dialog.show();
    }

    private void restoreBackupPayload(JSONObject payload) throws Exception {
        if (!"wonderapps-data".equals(payload.optString("format"))) {
            throw new IllegalArgumentException("Sauvegarde incompatible");
        }

        secrets.put(CredentialStore.BASIC_USER, payload.optString("basicUser", ""));
        secrets.put(CredentialStore.BASIC_PASS, payload.optString("basicPass", ""));
        secrets.put(CredentialStore.AD_USER, payload.optString("adUser", ""));
        secrets.put(CredentialStore.AD_PASS, payload.optString("adPass", ""));
        secrets.put(CUSTOM_SITES, payload.optString("sites", "[]"));

        File[] files = getFilesDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith("site_logo_") && file.getName().endsWith(".png")) {
                    file.delete();
                }
            }
        }

        JSONObject logos = payload.optJSONObject("siteLogos");
        if (logos != null) {
            Iterator<String> keys = logos.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                String encoded = logos.optString(id, "");
                if (encoded.isEmpty()) continue;
                byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                try (OutputStream out = new FileOutputStream(siteLogoFile(id))) {
                    out.write(bytes);
                }
            }
        }

        SharedPreferences.Editor editor = getSharedPreferences(SETTINGS, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_PLANO_FAVORITE, payload.optBoolean("planoFavorite", false));
        editor.putBoolean(KEY_PLANO_REQUIRE_BIO, payload.optBoolean("planoRequireBiometric", false));
        editor.putBoolean(KEY_PLANO_LOCK_EXIT, payload.optBoolean("planoLockOnExit", false));
        editor.putBoolean(KEY_PLANO_BLOCK_SCREEN, payload.optBoolean("planoBlockScreenshots", false));
        editor.putInt(KEY_AUTO_LOCK_SECONDS, payload.optInt("autoLockSeconds", 60));
        editor.apply();

        boolean importedDark = payload.optBoolean("darkMode", false);
        loadAppLogo();
        applyThemeWithoutRestart(importedDark);
        clearWebSession();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingBackupPassword = null;
            if (requestCode == REQ_PICK_SITE_LOGO) pendingSiteLogoId = null;
            if (requestCode == REQ_IMPORT_BACKUP && importFromOnboarding) {
                importFromOnboarding = false;
                timer.postDelayed(this::startOnboarding, 250);
            }
            return;
        }

        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT_BACKUP) {
            String password = pendingBackupPassword;
            if (password != null) writeBackup(uri, password);
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            boolean fromOnboarding = importFromOnboarding;
            importFromOnboarding = false;
            promptImportPassword(uri, fromOnboarding);
        } else if (requestCode == REQ_PICK_SITE_LOGO) {
            String siteId = pendingSiteLogoId;
            pendingSiteLogoId = null;
            if (siteId != null) savePickedSiteLogo(uri, siteId);
        }
    }

    private static final String FEEDBACK_ENDPOINT = "https://submit-form.com/VdUPDDIZz";
    private static final String KEY_LAST_FEEDBACK = "last_feedback_at";

    private void showFeedbackDialog() {
        showFeedbackDialog("");
    }

    private void showFeedbackDialog(String initialText) {
        EditText idea = new EditText(this);
        idea.setHint("Décris ton idée ou l’amélioration souhaitée…");
        idea.setMinLines(5);
        idea.setGravity(Gravity.TOP | Gravity.START);
        idea.setTextColor(primary());
        idea.setHintTextColor(secondary());
        idea.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (initialText != null && !initialText.isEmpty()) {
            idea.setText(initialText);
            idea.setSelection(idea.getText().length());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Proposer une idée")
                .setMessage("Ta suggestion sera envoyée directement, sans ouvrir Outlook.")
                .setView(form(idea))
                .setPositiveButton("Envoyer", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String message = idea.getText().toString().trim();

            if (message.length() < 3) {
                idea.setError("Décris ton idée en quelques mots");
                return;
            }

            long now = System.currentTimeMillis();
            long previous = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                    .getLong(KEY_LAST_FEEDBACK, 0L);

            if (now - previous < 15000L) {
                Toast.makeText(this, "Une suggestion vient déjà d’être envoyée. Réessaie dans quelques secondes.", Toast.LENGTH_LONG).show();
                return;
            }

            Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            send.setEnabled(false);
            send.setText("Envoi…");

            submitFeedback(message, success -> runOnUiThread(() -> {
                if (success) {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE)
                            .edit()
                            .putLong(KEY_LAST_FEEDBACK, System.currentTimeMillis())
                            .apply();

                    logEvent("Suggestion envoyée", "Formspark");
                    dialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Merci 💡")
                            .setMessage("Ta suggestion a bien été envoyée.")
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    send.setEnabled(true);
                    send.setText("Réessayer");
                    new AlertDialog.Builder(this)
                            .setTitle("Envoi impossible")
                            .setMessage("Vérifie ta connexion Internet puis réessaie.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            }));
        }));

        dialog.show();
    }

    private interface FeedbackCallback {
        void done(boolean success);
    }

    private void submitFeedback(String message, FeedbackCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(FEEDBACK_ENDPOINT);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");

                JSONObject payload = new JSONObject();
                payload.put("message", message);
                payload.put("source", "Wonder Apps Android");
                payload.put("version", "3.7");

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);

                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                    out.flush();
                }

                int code = connection.getResponseCode();
                callback.done(code >= 200 && code < 300);
            } catch (Exception ex) {
                callback.done(false);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "WonderAppsFeedback").start();
    }

    private File localLogFile() {
        return new File(getFilesDir(), "wonderapps_events.log");
    }

    private void logEvent(String event, String site) {
        try (FileWriter writer = new FileWriter(localLogFile(), true)) {
            writer.write(System.currentTimeMillis() + " | " + event
                    + (site == null || site.isEmpty() ? "" : " | " + site) + "\n");
        } catch (Exception ignored) {}
    }

    private void showLocalLog() {
        StringBuilder out = new StringBuilder();
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(localLogFile()))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            int start = Math.max(0, lines.size() - 50);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                String[] parts = line.split(" \\| ", 3);
                try {
                    long when = Long.parseLong(parts[0]);
                    String date = new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
                            .format(new Date(when));
                    out.append(date).append(" • ");
                    out.append(parts.length > 1 ? parts[1] : "Événement");
                    if (parts.length > 2) out.append(" • ").append(parts[2]);
                    out.append("\n");
                } catch (Exception e) {
                    out.append(line).append("\n");
                }
            }
        } catch (Exception ignored) {}

        if (out.length() == 0) out.append("Aucun événement enregistré.");

        new AlertDialog.Builder(this)
                .setTitle("Journal local")
                .setMessage(out.toString())
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Effacer", (d,w) -> {
                    File file = localLogFile();
                    if (file.exists()) file.delete();
                    Toast.makeText(this, "Journal effacé", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showSecurity() {
        new AlertDialog.Builder(this)
                .setTitle("Sécurité")
                .setMessage("• Déverrouillage configurable : biométrie + PIN, PIN seul ou désactivé\n"
                        + "• Le code PIN est dérivé par PBKDF2 et n’est pas stocké en clair\n"
                        + "• Mots de passe chiffrés localement en AES-256-GCM\n"
                        + "• Clé cryptographique conservée dans Android Keystore\n"
                        + "• Wonder Apps ne transmet pas les mots de passe qu’il conserve aux navigateurs externes\n"
                        + "• Edge/Chrome/Samsung peuvent conserver leurs propres sessions\n"
                        + "• HTTPS requis pour les connexions intégrées et automatiques\n"
                        + "• HTTP interne *.wonderbox.vpn : navigateur externe après avertissement\n"
                        + "• Injection uniquement sur un hôte de connexion vérifié, jamais vers les fournisseurs Microsoft/Atlassian\n"
                        + "• SSO/MFA : aucune tentative de contournement\n"
                        + "• Sauvegardes exportées chiffrées par mot de passe\n\n"
                        + "Les secrets sont brièvement déchiffrés en mémoire au moment d’une connexion. "
                        + "Un appareil rooté ou compromis peut réduire cette protection.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAbout() {
        String version = "en cours";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        new AlertDialog.Builder(this)
                .setTitle("À propos de Wonder Apps")
                .setMessage("WONDER APPS • Version " + version + "\n\n"
                        + "Conception : Younes AJBILOU\n\n"
                        + "« La performance naît souvent des petites frictions "
                        + "que l’on supprime chaque jour. »\n\n"
                        + "POURQUOI CETTE APPLICATION ?\n"
                        + "Offrir aux équipes Wonderbox un point d’entrée simple "
                        + "vers les applications professionnelles, gagner du temps "
                        + "sur les connexions répétitives et rendre les outils "
                        + "plus accessibles au quotidien.\n\n"
                        + "L’automatisation est facultative et propre à chaque site ; "
                        + "elle respecte le SSO, la MFA et les contraintes VPN. "
                        + "Les identifiants confiés à Wonder Apps sont chiffrés "
                        + "localement sur l’appareil.")
                .setPositiveButton("Fermer", null)
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

    private boolean backgroundLockExpired() {
        int seconds = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt(KEY_AUTO_LOCK_SECONDS, 60);
        return SiteRoutingPolicy.shouldRelock(
                backgroundAt, System.currentTimeMillis(), seconds);
    }

    private void lockFromBackground() {
        backgroundAt = 0L;
        unlocked = false;
        unlockFallbackStarted = false;
        if (root != null) root.setVisibility(View.INVISIBLE);
        requestUnlock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (unlocked && !isFinishing()) backgroundAt = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!unlocked || backgroundAt <= 0L || isFinishing()) return;
        if (backgroundLockExpired()) {
            lockFromBackground();
        } else {
            backgroundAt = 0L;
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
