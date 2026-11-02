package com.doramablock.android;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST = 710;
    private static final String PREFS = "doramablock_state";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_BLOCKED = "blocked_count";
    private static final String PREF_ALLOWED = "allowed_count";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView blockedView;
    private TextView allowedView;
    private TextView updatedView;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(9, 17, 30));
        scroll.addView(root);

        TextView title = text("DoramaBlock", 30, true, Color.WHITE);
        root.addView(title);

        TextView subtitle = text("FILTRO DNS LOCAL • ANDROID", 11, true,
                Color.rgb(85, 221, 176));
        subtitle.setLetterSpacing(0.12f);
        root.addView(subtitle);

        statusView = text("Verificando serviço…", 18, true, Color.WHITE);
        statusView.setPadding(0, dp(24), 0, dp(8));
        root.addView(statusView);

        updatedView = text("O estado será atualizado automaticamente.", 12, false,
                Color.rgb(158, 176, 200));
        root.addView(updatedView);

        root.addView(sectionTitle("Contadores reais do serviço DNS"));
        blockedView = metric("0");
        root.addView(metricCard("Consultas DNS bloqueadas", blockedView));
        allowedView = metric("0");
        root.addView(metricCard("Respostas DNS permitidas", allowedView));

        TextView explanation = text(
                "Esses números vêm das decisões DNS registradas pelo serviço VPN. " +
                "Eles não representam a quantidade de anúncios removidos e não provam " +
                "bloqueio dentro de cada aplicativo.",
                12, false, Color.rgb(196, 209, 227));
        explanation.setPadding(0, dp(10), 0, dp(14));
        root.addView(explanation);

        root.addView(sectionTitle("Controles"));
        Button start = button("ATIVAR FILTRO DNS");
        root.addView(start);
        start.setOnClickListener(v -> requestVpnPermission());

        Button stop = button("PARAR FILTRO");
        root.addView(stop);
        stop.setOnClickListener(v -> stopVpn());

        Button refresh = button("ATUALIZAR AGORA");
        root.addView(refresh);
        refresh.setOnClickListener(v -> refreshStatus());

        Button reset = button("ZERAR CONTADORES");
        root.addView(reset);
        reset.setOnClickListener(v -> resetCounters());

        root.addView(sectionTitle("O que esta versão suporta"));
        root.addView(note(
                "• DNS IPv4 por UDP encaminhado ao resolvedor configurado.\n" +
                "• Lista local inicial de 16 domínios conhecidos.\n" +
                "• Contagem local de decisões bloqueadas e respostas permitidas."));
        root.addView(sectionTitle("Limitações conhecidas"));
        root.addView(note(
                "• Não filtra IPv6, DNS por TCP, DNS-over-HTTPS ou DNS-over-TLS.\n" +
                "• Aplicativos podem usar caminhos DNS próprios ou criptografados.\n" +
                "• Anúncios no mesmo domínio do conteúdo podem continuar aparecendo.\n" +
                "• Não há garantia de bloquear todos os anúncios."));
        root.addView(note(
                "O Android mostrará uma solicitação do sistema antes de ativar a VPN. " +
                "A permissão é controlada pelo próprio Android."));

        setContentView(scroll);
    }

    private void requestVpnPermission() {
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_REQUEST);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpn();
        } else if (requestCode == VPN_REQUEST) {
            refreshStatus();
        }
    }

    private void startVpn() {
        Intent intent = new Intent(this, AdsVpnService.class);
        intent.setAction(AdsVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updatedView.setText("Solicitação de ativação enviada ao serviço Android.");
    }

    private void stopVpn() {
        Intent intent = new Intent(this, AdsVpnService.class);
        intent.setAction(AdsVpnService.ACTION_STOP);
        startService(intent);
        updatedView.setText("Solicitação para parar enviada ao serviço Android.");
        handler.postDelayed(this::refreshStatus, 300L);
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean active = prefs.getBoolean(PREF_ACTIVE, false);
        long blocked = prefs.getLong(PREF_BLOCKED, 0L);
        long allowed = prefs.getLong(PREF_ALLOWED, 0L);

        statusView.setText(active ? "● VPN local marcada como ativa"
                : "○ Filtro DNS parado");
        statusView.setTextColor(active
                ? Color.rgb(85, 221, 176) : Color.rgb(242, 198, 109));
        blockedView.setText(String.valueOf(blocked));
        allowedView.setText(String.valueOf(allowed));
        updatedView.setText("Estado local lido em " +
                android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()));
    }

    private void resetCounters() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(PREF_BLOCKED, 0L)
                .putLong(PREF_ALLOWED, 0L)
                .apply();
        refreshStatus();
    }

    private TextView metric(String value) {
        TextView view = text(value, 28, true, Color.WHITE);
        return view;
    }

    private View metricCard(String label, TextView value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(12), dp(15), dp(12));
        card.setBackgroundColor(Color.rgb(17, 30, 48));
        TextView caption = text(label, 12, false, Color.rgb(158, 176, 200));
        card.addView(caption);
        card.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(params);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 16, true, Color.WHITE);
        view.setPadding(0, dp(20), 0, dp(9));
        return view;
    }

    private TextView note(String value) {
        TextView view = text(value, 12, false, Color.rgb(196, 209, 227));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundColor(Color.rgb(23, 40, 64));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}