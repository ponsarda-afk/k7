package fr.kawaiiapp.k7audiob;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(K7Player.class);
        super.onCreate(savedInstanceState);
    }
}
