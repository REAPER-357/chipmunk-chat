package app.chipmunk.chat;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ChipmunkBackgroundPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
