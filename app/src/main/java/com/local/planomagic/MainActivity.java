package com.local.planomagic;

import android.app.*;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

        List<SiteProfile> customSites = loadSites();
        int appCount = 1 + customSites.size();
        int autoCount = 1;
        int actionCount = 0;

        for (SiteProfile site : customSites) {
            String type = site.authType == null ? "PENDING" : site.authType;
            boolean auto = ("FORM".equals(type) || "BASIC".equals(type))
                    && !site.username.isEmpty()
                    && !site.password.isEmpty();
            if (auto) autoCount++;

            if ("PENDING".equals(type) || "UNKNOWN".equals(type)
                    || (("FORM".equals(type) || "BASIC".equals(type))
                    && (site.username.isEmpty() || site.password.isEmpty()))) {
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
                ? "Tout est prêt aujourd’hui !"
                : actionCount + " action" + (actionCount > 1 ? "s" : "") + " à vérifier");
        ready.setTextSize(14);
        ready.setTextColor(secondary());
        LinearLayout.LayoutParams readyLp = new LinearLayout.LayoutParams(-1, -2);
        readyLp.setMargins(0, dp(3), 0, dp(16));
        ready.setLayoutParams(readyLp);
        homeList.addView(ready);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setWeightSum(3f);

        stats.addView(statTile(String.valueOf(appCount), "Applications"));
        stats.addView(statTile(String.valueOf(autoCount), "Connexions auto"));
        stats.addView(statTile(String.valueOf(actionCount), "À vérifier"));

        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-1, -2);
        statsLp.setMargins(0, 0, 0, dp(20));
        stats.setLayoutParams(statsLp);
        homeList.addView(stats);

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
                "Plano",
                "Prêt • Connexion automatique",
                "AUTO",
                accent());
        card.setOnClickListener(v -> openPlano());
        homeList.addView(card);
    }

    private void addCustomCard(SiteProfile site) {
        String type = site.authType == null ? "PENDING" : site.authType;
        Uri uri = Uri.parse(site.url);
        String host = uri.getHost() == null ? site.url : uri.getHost();

        LinearLayout card = dashboardAppCard(
                site.name,
                host + " • " + authLabel(type),
                authShort(type),
                authColor(type));

        card.setOnClickListener(v -> {
            if ("PENDING".equals(type) || "UNKNOWN".equals(type)) analyzeSite(site);
            else openCustom(site);
        });

        card.setOnLongClickListener(v -> {
            showSiteActions(site);
            return true;
        });

        homeList.addView(card);
    }

    private LinearLayout dashboardAppCard(String name, String subtitle, String badgeText, int badgeColor) {
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

        TextView appIcon = new TextView(this);
        appIcon.setText(name.isEmpty() ? "A" : name.substring(0, 1).toUpperCase(Locale.ROOT));
        appIcon.setTextSize(20);
        appIcon.setTextColor(Color.WHITE);
        appIcon.setGravity(Gravity.CENTER);
        appIcon.setBackground(round(accent(), 14, Color.TRANSPARENT));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconLp.setMargins(0, 0, dp(12), 0);
        appIcon.setLayoutParams(iconLp);

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

        File file = customLogoFile();
        if (file.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                avatar.setImageBitmap(bitmap);
                return;
            }
        }
        avatar.setImageResource(R.drawable.app_icon_photo);
    }

    private void chooseAppLogo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK_LOGO);
    }

    private void savePickedLogo(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Image inaccessible");

            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IOException("Image invalide");

            int max = 1024;
            if (bitmap.getWidth() > max || bitmap.getHeight() > max) {
                float ratio = Math.min((float) max / bitmap.getWidth(), (float) max / bitmap.getHeight());
                int w = Math.max(1, Math.round(bitmap.getWidth() * ratio));
                int h = Math.max(1, Math.round(bitmap.getHeight() * ratio));
                bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
            }

            try (OutputStream out = new FileOutputStream(customLogoFile())) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out);
            }

            loadAppLogo();
            Toast.makeText(this, "Logo mis à jour", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Logo non modifié")
                    .setMessage("L’image n’a pas pu être utilisée. Choisis une image PNG, JPG ou WEBP classique.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void resetAppLogo() {
        File file = customLogoFile();
        if (file.exists()) file.delete();
        loadAppLogo();
        Toast.makeText(this, "Logo par défaut restauré", Toast.LENGTH_SHORT).show();
    }

    private void showSettingsCenter() {
        LinearLayout stack = dialogStack();

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
                "Apparence et logo",
                "Mode classique / sombre et logo affiché dans Wonder Apps",
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
                "À propos",
                "Version, auteur et objectif de l’application",
                v -> showAbout()));

        new AlertDialog.Builder(this)
                .setTitle("Réglages")
                .setView(stack)
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
                "Plano",
                "Application configurée en connexion automatique",
                v -> editPlanoCredentials(false)));

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
        content.addView(settingsRow(
                "Choisir le logo affiché",
                "Utiliser une image de ton téléphone dans l’en-tête de Wonder Apps",
                v -> chooseAppLogo()));
        content.addView(settingsRow(
                "Restaurer le logo par défaut",
                "Revenir à l’image actuellement fournie avec l’application",
                v -> resetAppLogo()));
        content.addView(smallNote("Le changement de thème modifie l’interface de Wonder Apps sans fermer le site ouvert. Le contenu du site lui-même garde son propre thème."));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Apparence et logo")
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
                    else if (which == 2) confirmReanalyzeSite(site);
                    else confirmDeleteSite(site);
                })
                .show();
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
                "La sauvegarde contient tes sites, leurs réglages, le thème, le logo personnalisé et les identifiants enregistrés. "
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
        payload.put("version", 1);
        payload.put("basicUser", secrets.get(CredentialStore.BASIC_USER));
        payload.put("basicPass", secrets.get(CredentialStore.BASIC_PASS));
        payload.put("adUser", secrets.get(CredentialStore.AD_USER));
        payload.put("adPass", secrets.get(CredentialStore.AD_PASS));

        String sites = secrets.get(CUSTOM_SITES);
        if (sites.isEmpty()) sites = secrets.get("custom_sites_v1");
        payload.put("sites", sites.isEmpty() ? "[]" : sites);
        payload.put("darkMode", darkMode);

        File logo = customLogoFile();
        if (logo.exists()) {
            try (InputStream in = new FileInputStream(logo);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                payload.put("customLogo", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            }
        }

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

        String logoB64 = payload.optString("customLogo", "");
        if (!logoB64.isEmpty()) {
            byte[] bytes = Base64.decode(logoB64, Base64.NO_WRAP);
            try (OutputStream out = new FileOutputStream(customLogoFile())) {
                out.write(bytes);
            }
            loadAppLogo();
        } else {
            File logo = customLogoFile();
            if (logo.exists()) logo.delete();
            loadAppLogo();
        }

        boolean importedDark = payload.optBoolean("darkMode", false);
        applyThemeWithoutRestart(importedDark);
        clearWebSession();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingBackupPassword = null;
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
        } else if (requestCode == REQ_PICK_LOGO) {
            savePickedLogo(uri);
        }
    }

    private void showFeedbackDialog() {
        EditText idea = new EditText(this);
        idea.setHint("Décris ton idée ou l’amélioration souhaitée…");
        idea.setMinLines(5);
        idea.setGravity(Gravity.TOP | Gravity.START);
        idea.setTextColor(primary());
        idea.setHintTextColor(secondary());
        idea.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Proposer une idée")
                .setMessage("Ton message sera préparé pour être envoyé à l’auteur de Wonder Apps.")
                .setView(form(idea))
                .setPositiveButton("Préparer l’e-mail", null)
                .setNegativeButton("Annuler", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = idea.getText().toString().trim();

            if (text.length() < 3) {
                idea.setError("Décris ton idée en quelques mots");
                return;
            }

            dialog.dismiss();
            composeFeedbackEmail(text);
        }));

        dialog.show();
    }

    private void composeFeedbackEmail(String idea) {
        String recipient = "younes.ajbilou@wonderbox.com";
        String subject = "Suggestion Wonder Apps";
        String body = "Bonjour,\n\n"
                + "Voici une idée pour améliorer Wonder Apps :\n\n"
                + idea
                + "\n\n---\nEnvoyé depuis Wonder Apps v1.8";

        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + recipient));
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, body);

        try {
            startActivity(intent);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Aucune application e-mail disponible")
                    .setMessage("Impossible d’ouvrir un client e-mail sur ce téléphone.\n\nDestinataire : " + recipient)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void showSecurity() {
        new AlertDialog.Builder(this)
                .setTitle("Sécurité")
                .setMessage("• Déverrouillage configurable : biométrie + PIN, PIN seul ou désactivé\n"
                        + "• Le code PIN est dérivé par PBKDF2 et n’est pas stocké en clair\n"
                        + "• Mots de passe chiffrés localement en AES-256-GCM\n"
                        + "• Clé cryptographique conservée dans Android Keystore\n"
                        + "• Aucun mot de passe enregistré dans Chrome/Edge\n"
                        + "• Ajout de sites limité à HTTPS\n"
                        + "• Les identifiants sont injectés uniquement sur le domaine de connexion détecté\n"
                        + "• SSO/MFA détectés : aucune tentative de contournement\n"
                        + "• Sauvegardes exportées chiffrées par mot de passe\n\n"
                        + "Les secrets sont brièvement déchiffrés en mémoire au moment d’une connexion. "
                        + "Un appareil rooté ou compromis peut réduire cette protection.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("À propos de Wonder Apps")
                .setMessage("Wonder Apps\nVersion 1.8\n\n"
                        + "Auteur :\nYounes AJBILOU\n\n"
                        + "« La performance naît souvent des petites frictions que l’on supprime chaque jour. »\n\n"
                        + "Wonder Apps a été pensé pour simplifier l’accès aux outils du quotidien, réduire les manipulations répétitives "
                        + "et améliorer la productivité, la fluidité et la performance.")
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
