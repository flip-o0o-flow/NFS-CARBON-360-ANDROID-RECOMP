// Launcher styling adapted from codepdbh/nfsmw-android (5f581c6). SPDX-License-Identifier: GPL-3.0-only
package com.nfscarbon.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Standalone Carbon launcher. Game assets stay in the player's shared folder. */
public final class MainActivity extends Activity {
    static final String GAME_FOLDER_NAME = "NFSCARBON";
    private ShaderBuilder shaderBuilder;
    private static final int IMPORT = 10;
    private static final int REQUEST_DRIVER_ZIP = 45;
    private static final int REQUEST_DRIVER_PROBE = 46;
    private boolean playAfterProbe;
    private boolean launcherResumed;
    private boolean checkingUpdates;
    private ReleaseUpdates.Result pendingUpdate;
    private boolean pendingUpdateManual;
    private static final String EXPECTED_XEX = "b1e423914f5feb0871c8903aded4d86852d34be3b831b42f5c6473c7c7304221";
    private TextView status;
    private Button play, importButton;
    private boolean busy;
    private LinearLayout checks;
    private android.widget.GridLayout optionsList;
    private Button accessButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        if (state != null) playAfterProbe = state.getBoolean("play_after_probe");
        setContentView(buildScreen());
        checkUpdates(false);
    }

    private View buildScreen() {
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A0D10, 0xFF050607, 0xFF061518}));
        float density = getResources().getDisplayMetrics().density;

        // Left: near-black panel with slanted edge; header, actions as a list with line icons, file checks at the bottom.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, dp(22), dp(78), dp(14));
        Shell.Panel panel = new Shell.Panel(density, 70);
        left.setBackground(panel);
        columns.addView(left, new LinearLayout.LayoutParams(0, -1, .4f));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(22), dp(14), 0, dp(14));
        heading.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> panel.band(t, b));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new Shell.Icon(density, Shell.Glyph.GAUGE, 28, Shell.ACCENT));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable iconBox = new GradientDrawable();
        iconBox.setColor(0xFF15191B); iconBox.setCornerRadius(dp(10)); iconBox.setStroke(dp(1), 0x1FFFFFFF);
        icon.setBackground(iconBox);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout name = new LinearLayout(this);
        name.setOrientation(LinearLayout.VERTICAL);
        name.setPadding(dp(14), 0, 0, 0);
        android.text.SpannableString title = new android.text.SpannableString("NEED FOR SPEED CARBON");
        title.setSpan(new android.text.style.ForegroundColorSpan(Shell.ACCENT), 15, 21, 0);
        TextView titleView = label("", 18, Color.WHITE, false);
        titleView.setText(title);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        titleView.setSingleLine();
        name.addView(titleView);
        status = label(T.t("Comprobando archivos…"), 13, 0xFFB5BBC1, false);
        status.setCompoundDrawablePadding(dp(7));
        status.setPadding(0, dp(3), 0, 0);
        name.addView(status);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(heading);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(50));
        accessButton = action(T.t("Permitir acceso"), Shell.Glyph.KEY, this::permission);
        LinearLayout.LayoutParams firstRow = new LinearLayout.LayoutParams(-1, dp(50));
        firstRow.topMargin = dp(16);
        left.addView(accessButton, firstRow);
        importButton = action(T.t("Importar mi copia"), Shell.Glyph.FOLDER, this::importGame);
        left.addView(importButton, rowParams);
        left.addView(action(T.t("Enviar crash o log"), Shell.Glyph.SEND, this::report), rowParams);
        left.addView(action("GitHub", Shell.Glyph.CODE, () -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(Diagnostics.ISSUES)))), rowParams);
        left.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout files = new LinearLayout(this);
        files.setOrientation(LinearLayout.VERTICAL);
        files.setPadding(dp(24), 0, 0, 0);
        left.addView(files);
        files.addView(caption(T.t("ARCHIVOS DEL JUEGO")));
        checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.HORIZONTAL);
        files.addView(checks);
        TextView note = label(T.t("Memoria interna/NFSCARBON") + "  ·  ANDROID EVOLVED · XBOX 360", 10, 0xFF6E767D, false);
        note.setPadding(0, dp(2), 0, 0);
        files.addView(note);

        // Right: settings as cards, main button bottom right.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(18), dp(20), dp(22), dp(6));
        columns.addView(right, new LinearLayout.LayoutParams(0, -1, .6f));
        right.addView(caption(T.t("AJUSTES DEL JUEGO")));
        TextView nextSession = label(T.t("Se aplican al pulsar Jugar. Con antialiasing Original se usa 1280×720. Los FPS altos y Sin límite son experimentales."), 11, 0xFF7F878E, false);
        nextSession.setPadding(0, dp(1), 0, dp(6));
        right.addView(nextSession);
        ScrollView optionsScroll = new ScrollView(this);
        optionsScroll.setVerticalScrollBarEnabled(false);
        optionsScroll.setVerticalFadingEdgeEnabled(true);
        optionsScroll.setFadingEdgeLength(dp(28));
        LinearLayout scrolled = new LinearLayout(this);
        scrolled.setOrientation(LinearLayout.VERTICAL);
        optionsList = new android.widget.GridLayout(this);
        optionsList.setColumnCount(2);
        scrolled.addView(optionsList, new LinearLayout.LayoutParams(-1, -2));
        TextView languageNote = label(T.t("Los idiomas y las voces dependen de tu copia."), 11, 0xFF7F878E, false);
        languageNote.setPadding(dp(4), dp(6), 0, dp(4));
        scrolled.addView(languageNote);
        optionsScroll.addView(scrolled);
        right.addView(optionsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        play = action(T.t("JUGAR"), null, this::play);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(330), dp(80));
        playParams.gravity = Gravity.END;
        playParams.topMargin = dp(2);
        right.addView(play, playParams);
        refreshOptions();
        return columns;
    }

    private void importGame() {
        if (busy) return;
        if (!storageAllowed()) { permission(); return; }
        new AlertDialog.Builder(this).setTitle(T.t("Importar Carbon"))
            .setMessage(T.t("Se copiarán los archivos a NFSCARBON. Necesitas unos 6 GB libres. ")
                    + T.t("La copia existente quedará como respaldo y ocupará espacio adicional."))
            .setPositiveButton(T.t("Elegir carpeta"), (dialog, which) -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT))
            .setNegativeButton(T.t("Cancelar"), null).show();
    }

    @Override public void onResume() {
        super.onResume();
        launcherResumed = true;
        refreshOptions();
        refresh();
        if (pendingUpdate != null && !busy) {
            ReleaseUpdates.Result result = pendingUpdate;
            pendingUpdate = null;
            showUpdate(result, pendingUpdateManual);
        }
    }

    @Override protected void onPause() {
        launcherResumed = false;
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("play_after_probe", playAfterProbe);
        super.onSaveInstanceState(state);
    }
    private void reportError(String message) {
        new AlertDialog.Builder(this).setTitle("NFS Carbon")
                .setMessage(message).setPositiveButton(T.t("Aceptar"), null).show();
    }

    private void checkUpdates(boolean manual) {
        if (checkingUpdates) return;
        checkingUpdates = true;
        if (optionsList != null) refreshOptions();
        ReleaseUpdates.check(this, manual, result -> {
            if (isFinishing() || isDestroyed()) return;
            checkingUpdates = false;
            refreshOptions();
            if (!launcherResumed || busy) {
                pendingUpdate = result; pendingUpdateManual = manual;
            } else showUpdate(result, manual);
        });
    }

    private void showUpdate(ReleaseUpdates.Result result, boolean manual) {
        if (result.error != null) {
            if (manual) reportError(result.error);
            return;  // Offline/rate-limited checks never affect playing.
        }
        if (!ReleaseVersion.newer(result.tag, ReleaseUpdates.installed(this))) {
            if (manual) new AlertDialog.Builder(this)
                    .setTitle(T.t("Actualizaciones")).setMessage(T.t("Tienes la versión ") + ReleaseUpdates.installed(this)
                            + T.t(". No hay una versión estable más reciente en GitHub."))
                    .setPositiveButton(T.t("Aceptar"), null).show();
            return;
        }
        android.content.SharedPreferences updates = ReleaseUpdates.prefs(this);
        if (!manual && result.tag.equals(updates.getString("notified_tag", ""))) return;
        updates.edit().putString("notified_tag", result.tag).apply();
        new AlertDialog.Builder(this)
                .setTitle(T.t("Nueva versión disponible: ") + result.tag)
                .setMessage(T.t("Puedes actualizar desde GitHub o seguir jugando con esta versión. Instala el APK ")
                        + T.t("encima del anterior para conservar tus partidas y ajustes."))
                .setPositiveButton(T.t("Actualizar"), (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.url))); }
                    catch (ActivityNotFoundException error) { reportError(T.t("No se encontró una app para abrir GitHub.")); }
                })
                .setNegativeButton(T.t("Más tarde"), null).show();
    }

    /** Option card: dark, rounded ~10 dp, thin accent line; focused = accent outline and lighter fill. */
    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(14), dp(9), dp(10), dp(9));
        view.setBackground(new Shell.Focus(getResources().getDisplayMetrics().density, 0xFF131719, 10, true));
        view.setFocusable(true);
        return view;
    }

    private TextView label(String message, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    /** Small grey, spaced caption in capitals above a value. */
    private TextView caption(String message) {
        TextView view = label(message, 10, 0xFF8E959B, true);
        view.setAllCaps(true);
        view.setLetterSpacing(.14f);
        view.setPadding(0, 0, 0, dp(2));
        return view;
    }

    /** glyph == null: the main button (parallelogram, accent gradient); otherwise a list row with a line icon. */
    private Button action(String title, Shell.Glyph glyph, Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setStateListAnimator(null);
        view.setMinHeight(0); view.setMinimumHeight(0);
        if (glyph == null) {
            view.setTextColor(0xFF04191C);  // White on turquoise is under 2:1; dark text reads.
            view.setTextSize(24);
            view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
            view.setBackground(new Shell.PlayButton(density));
            android.text.SpannableString text = new android.text.SpannableString("\u00A0  " + title);  // Triangle next to the word.
            text.setSpan(new android.text.style.ImageSpan(new Shell.Icon(density, Shell.Glyph.PLAY, 18, 0xFF04191C),
                    android.text.style.ImageSpan.ALIGN_BASELINE), 0, 1, 0);
            view.setText(text);
            view.setPadding(dp(20), 0, dp(66), 0);  // Centre on the body, not on the stripes.
        } else {
            android.content.res.ColorStateList text = new android.content.res.ColorStateList(new int[][]{
                    {android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                    new int[]{Color.WHITE, Color.WHITE, 0xFFC8CDD2});
            view.setTextColor(text);
            view.setTextSize(16);
            view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            view.setBackground(new Shell.Focus(density, 0, 0, false));
            Shell.Icon icon = new Shell.Icon(density, glyph, 24, 0xFFC8CDD2);
            icon.setTintList(text);
            view.setCompoundDrawables(icon, null, null, null);
            view.setCompoundDrawablePadding(dp(20));
            view.setPadding(dp(26), 0, dp(8), 0);
        }
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private void refreshOptions() {
        if (optionsList == null) return;
        optionsList.removeAllViews();
        GpuDrivers.Driver driver = GpuDrivers.selected(this);
        optionsList.addView(optionRow(T.t("Driver Vulkan"), driver == null ? T.t("Del sistema") : driver.name, this::chooseDriver));
        optionsList.addView(optionRow(T.t("Probar driver"), T.t("Comprobar GPU y Vulkan"), () -> probeDriver(false)));
        optionsList.addView(optionRow(T.t("Buscar actualizaciones"), checkingUpdates ? T.t("Comprobando GitHub…")
                : T.t("Versión ") + ReleaseUpdates.installed(this), () -> checkUpdates(true)));
        for (GameOptions.Option option : GameOptions.ALL)
            optionsList.addView(optionRow(option.title, option.label(GameOptions.get(this, option.key)), () -> chooseOption(option)));
    }

    private View optionRow(String title, String current, Runnable clicked) {
        LinearLayout row = card();
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(caption(title));
        TextView value = label(current, 15, Color.WHITE, true);
        value.setSingleLine();
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(value);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Shell.Icon(getResources().getDisplayMetrics().density, Shell.Glyph.CHEVRON, 18, 0xFF8E959B));
        row.addView(chevron);
        row.setOnClickListener(ignored -> { if (!busy) clicked.run(); });
        android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
        params.width = 0;
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.setLayoutParams(params);
        return row;
    }

    private void chooseDriver() {
        java.util.List<GpuDrivers.Driver> drivers = GpuDrivers.list(this);
        String[] names = new String[drivers.size() + 1];
        names[0] = T.t("Driver del sistema");
        int selected = 0;
        for (int i = 0; i < drivers.size(); ++i) {
            names[i + 1] = drivers.get(i).name;
            if (drivers.get(i).id.equals(GpuDrivers.selectedId(this))) selected = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(T.t("Driver Vulkan"))
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    GpuDrivers.select(this, which == 0 ? "" : drivers.get(which - 1).id);
                    refreshOptions(); dialog.dismiss();
                })
                .setPositiveButton(T.t("Importar ZIP"), (dialog, which) -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(picker, REQUEST_DRIVER_ZIP);
                })
                .setNeutralButton(T.t("Eliminar seleccionado"), (dialog, which) -> {
                    GpuDrivers.Driver driver = GpuDrivers.selected(this);
                    if (driver == null) return;
                    try { GpuDrivers.remove(this, driver); refreshOptions(); }
                    catch (IOException error) { reportError(error.getMessage()); }
                })
                .setNegativeButton(T.t("Volver"), null).show();
    }

    private void probeDriver(boolean playAfter) {
        if (busy) return;
        busy = true;
        playAfterProbe = playAfter;
        play.setEnabled(false);
        startActivityForResult(new Intent(this, GpuProbeActivity.class), REQUEST_DRIVER_PROBE);
    }

    private void showDriverReport(String report) {
        try {
            Diagnostics.acceptGpu(this, report);
            org.json.JSONObject gpu = new org.json.JSONObject(report);
            boolean failed = gpu.optBoolean("driverLoadFailed") || gpu.has("probeError");
            String text = "GPU: " + gpu.optString("gpu", T.t("no disponible"))
                    + "\nVulkan: " + gpu.optString("vulkan", T.t("no disponible"))
                    + T.t("\nConjuntos de descriptores: ") + gpu.optInt("maxBoundDescriptorSets")
                    + "\n" + (gpu.optBoolean("compatible") ? T.t("Funciones del renderizador nativo disponibles.")
                            : T.t("Funciones faltantes: ") + gpu.optJSONArray("missing"));
            if (failed) text = T.t("No se pudo probar el driver. ") + gpu.optString("probeError", T.t("Error al cargar Vulkan."));
            if (playAfterProbe && !failed && (!"nativo".equals(GameOptions.get(this, GameOptions.RENDERER.key)) || gpu.optBoolean("compatible"))) { playAfterProbe = false; playChecked(); return; }
            playAfterProbe = false;
            new AlertDialog.Builder(this)
                    .setTitle(T.t("Prueba del driver Vulkan")).setMessage(text)
                    .setPositiveButton(T.t("Aceptar"), null)
                    .setNeutralButton(T.t("Usar sistema"), (dialog, which) -> {
                        GpuDrivers.select(this, ""); refreshOptions();
                    }).show();
        } catch (Exception error) { playAfterProbe = false; reportError(T.t("Diagnóstico no válido: ") + error.getMessage()); }
    }

    private void chooseOption(GameOptions.Option option) {
        int selected = 0;
        for (int i = 0; i < option.values.length; i++)
            if (option.values[i].equals(GameOptions.get(this, option.key))) selected = i;
        new AlertDialog.Builder(this).setTitle(option.title)
            .setSingleChoiceItems(option.labels, selected, (choice, value) -> {
                GameOptions.set(this, option.key, option.values[value]);
                if (T.KEY.equals(option.key)) { T.restart(this); return; }
                refreshOptions(); choice.dismiss();
            }).setNegativeButton(T.t("Cerrar"), null).show();
    }

    private void showChecks() {
        checks.removeAllViews();
        File root = gameRoot();
        check("default.xex", new File(root, "default.xex").isFile());
        check("NFS", new File(root, "NFS").isDirectory());
        check("Movies", new File(root, "Movies").isDirectory() || new File(root, "MOVIES").isDirectory());
    }

    private void check(String name, boolean found) {
        TextView row = label((found ? "✓ " : "✗ ") + name, 12,
                found ? 0xFF3DD68C : 0xFFFF7070, false);
        row.setPadding(0, dp(3), 0, dp(3));
        checks.addView(row, new LinearLayout.LayoutParams(0, -2, name.equals("default.xex") ? 2 : 1));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private File gameRoot() { return new File(Environment.getExternalStorageDirectory(), GAME_FOLDER_NAME); }
    private boolean storageAllowed() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void permission() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
    }
    private static boolean valid(File root) {
        return new File(root, "default.xex").isFile() && new File(root, "NFS").isDirectory()
                && (new File(root, "Movies").isDirectory() || new File(root, "MOVIES").isDirectory());
    }
    private void refresh() {
        if (status == null || busy) return;
        boolean ready = storageAllowed() && valid(gameRoot());
        play.setEnabled(ready);
        play.setAlpha(ready ? 1f : .45f);
        accessButton.setVisibility(storageAllowed() ? View.GONE : View.VISIBLE);
        showChecks();
        GradientDrawable dot = new GradientDrawable();  // Status dot: green ready, amber something missing.
        dot.setShape(GradientDrawable.OVAL); dot.setColor(ready ? 0xFF3DD68C : 0xFFF0A63A); dot.setBounds(0, 0, dp(8), dp(8));
        status.setCompoundDrawables(dot, null, null, null);
        status.setText(!storageAllowed() ? T.t("Permite el acceso a tu copia.")
                : ready ? T.t("Tu copia está lista para iniciar.")
                : T.t("Importa tu copia extraída de Xbox 360."));
    }
    private void play() {
        if (busy || !storageAllowed() || !valid(gameRoot())) { refresh(); return; }
        // Probe both the system native path and every imported driver in a fresh process.
        if (GpuDrivers.selected(this) != null || "nativo".equals(GameOptions.get(this, GameOptions.RENDERER.key))) {
            probeDriver(true);
        } else playChecked();
    }
    private void playChecked() {
        busy = true; play.setEnabled(false); status.setText(T.t("Comprobando la edición del ejecutable…"));
        new Thread(() -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new FileInputStream(new File(gameRoot(), "default.xex"))) {
                    byte[] buffer = new byte[65536];
                    for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
                }
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                if (!EXPECTED_XEX.equals(hash.toString())) throw new IOException(
                        T.t("Esta edición de default.xex no coincide con la recompilada. Usa la copia Xbox 360 indicada en el README."));
                runOnUiThread(() -> {
                    busy = false;
                    if ("nativo".equals(GameOptions.get(this, GameOptions.RENDERER.key))
                            && !ShaderBuilder.hasLibrary(shaderFolder())) {
                        buildShaders();
                        return;
                    }
                    refresh(); Diagnostics.recordLaunch(this);
                    startActivity(new Intent(this, GameActivity.class));
                });
            } catch (Exception error) { failed(error); }
        }, "CarbonEditionCheck").start();
    }
    // Where the native renderer looks first for its library: the executable folder (REX_APP_FOLDER in GameActivity).
    private File shaderFolder() { return new File(getFilesDir(), "nfscarbon/user"); }

    /** Builds nfscarbon_shaders.nfsp from the game files (a minute or two, only once), then starts the game. */
    private void buildShaders() {
        if (shaderBuilder != null) return;
        busy = true; play.setEnabled(false); play.setAlpha(.45f);
        status.setText(T.t("Generando los shaders del renderizador nativo (solo la primera vez)…"));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shaderBuilder = new ShaderBuilder(this, gameRoot(), shaderFolder(), new ShaderBuilder.Listener() {
            @Override public void onProgress(float fraction, String text) {
                status.setText(Math.round(fraction * 100) + " % · " + text);
            }
            @Override public void onDone(File library) {
                shadersFinished();
                refresh(); Diagnostics.recordLaunch(MainActivity.this);
                startActivity(new Intent(MainActivity.this, GameActivity.class));
            }
            @Override public void onError(String message) {
                shadersFinished();
                refresh();
                status.setText(T.t("No se pudieron generar los shaders: ") + message);
            }
        });
        shaderBuilder.start();
    }

    private void shadersFinished() {
        shaderBuilder = null; busy = false;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_DRIVER_PROBE) {
            busy = false;
            refresh();
            showDriverReport(result == RESULT_OK && data != null ? data.getStringExtra("gpu_report")
                    : T.t("{\"probeError\":\"La prueba se cerró o fue cancelada. Puedes volver al driver del sistema.\",\"driverLoadFailed\":true}"));
            return;
        }
        if (request == REQUEST_DRIVER_ZIP) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri zip = data.getData();
            busy = true; play.setEnabled(false);
            status.setText(T.t("Importando driver Vulkan…"));
            new Thread(() -> {
                try {
                    GpuDrivers.Driver driver = GpuDrivers.importZip(this, zip);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        busy = false; GpuDrivers.select(this, driver.id); refreshOptions(); refresh();
                        status.setText(T.t("Driver importado: ") + driver.name + T.t(". Usa Probar driver para comprobarlo."));
                    });
                } catch (Exception error) { failed(error); }
            }, "CarbonDriverImport").start();
            return;
        }
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); busy = true; play.setEnabled(false); importButton.setEnabled(false);
        status.setText(T.t("Copiando tu juego. Deja abierta esta pantalla…"));
        new Thread(() -> {
            File staging = new File(Environment.getExternalStorageDirectory(), GAME_FOLDER_NAME + "-import-" + System.currentTimeMillis());
            try {
                DocumentFile source = DocumentFile.fromTreeUri(this, uri);
                if (source == null || !source.isDirectory()) throw new IOException(T.t("La carpeta no se puede abrir."));
                if (source.findFile("default.xex") == null || source.findFile("NFS") == null
                        || (source.findFile("Movies") == null && source.findFile("MOVIES") == null))
                    throw new IOException(T.t("Elige la carpeta del juego que contiene default.xex, NFS y Movies."));
                if (!staging.mkdirs()) throw new IOException(T.t("No se pudo crear la carpeta de importación."));
                copy(source, staging);
                if (!valid(staging)) throw new IOException(T.t("La carpeta debe contener default.xex, NFS y Movies."));
                File target = gameRoot();
                File backup = new File(target.getParentFile(), GAME_FOLDER_NAME + "-backup-" + System.currentTimeMillis());
                boolean backedUp = target.exists();
                if (backedUp && !target.renameTo(backup)) throw new IOException(T.t("No se pudo guardar la copia anterior."));
                if (!staging.renameTo(target)) {
                    if (backedUp) backup.renameTo(target);
                    throw new IOException(T.t("No se pudo instalar la carpeta copiada."));
                }
                runOnUiThread(() -> { busy = false; importButton.setEnabled(true); refresh(); });
            } catch (Exception error) { failed(error); }
        }, "CarbonImport").start();
    }
    private void copy(DocumentFile source, File target) throws IOException {
        for (DocumentFile child : source.listFiles()) {
            String name = child.getName();
            if (name == null || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
                throw new IOException(T.t("Nombre de archivo inválido."));
            File destination = new File(target, name);
            if (child.isDirectory()) {
                if (!destination.mkdir()) throw new IOException(T.t("No se pudo crear ") + name);
                copy(child, destination);
            } else {
                try (InputStream input = getContentResolver().openInputStream(child.getUri());
                        FileOutputStream output = new FileOutputStream(destination)) {
                    if (input == null) throw new IOException(T.t("No se pudo leer ") + name);
                    byte[] buffer = new byte[1024 * 1024];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
            }
        }
    }
    private void report() {
        if (busy) return;
        busy = true; status.setText(T.t("Preparando registro y diagnóstico…"));
        new Thread(() -> {
            try {
                File report = Diagnostics.createReport(this);
                runOnUiThread(() -> {
                    busy = false; refresh();
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".reports", report);
                    Intent email = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_EMAIL, new String[]{Diagnostics.EMAIL})
                        .putExtra(Intent.EXTRA_SUBJECT, T.t("NFS Carbon 360: diagnóstico de ") + Build.MODEL)
                        .putExtra(Intent.EXTRA_TEXT, Diagnostics.summary(this) + T.t("\nDescribe aquí lo que ocurrió.\n"))
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    email.setClipData(ClipData.newUri(getContentResolver(), T.t("Diagnóstico Carbon"), uri));
                    try { startActivity(Intent.createChooser(email, T.t("Enviar registro"))); }
                    catch (Exception error) { failed(error); }
                });
            } catch (Exception error) { failed(error); }
        }, "CarbonReport").start();
    }
    private void failed(Exception error) {
        runOnUiThread(() -> {
            busy = false; importButton.setEnabled(true); refresh(); status.setText(error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }
}
