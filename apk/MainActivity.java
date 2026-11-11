package fr.kawaiiapp.k7audiob;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(K7Player.class);
        super.onCreate(savedInstanceState);
        // Le baladeur est dessiné au pixel près : on ignore la taille de police système d'Android,
        // qui grossit les chiffres et les fait sortir de leurs cases (Chrome, lui, ne le fait pas).
        try { getBridge().getWebView().getSettings().setTextZoom(100); } catch (Exception ignored) { }
    }
}
