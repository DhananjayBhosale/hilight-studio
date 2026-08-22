package com.hilight.studio;

import com.hilight.core.Engine;
import com.hilight.core.IHiLightService;
import com.hilight.core.Log;

public final class HiLightUserService extends IHiLightService.Stub {
    private final Engine engine = new Engine();

    public HiLightUserService() {
        try {
            engine.start();
            Log.i("Shizuku user service up, " + engine.ledCount() + " LEDs, uid "
                    + android.os.Process.myUid());
        } catch (Throwable t) {
            Log.w("engine start failed: " + t);
        }
    }

    @Override
    public void setState(String json) {
        engine.setState(json);
    }

    @Override
    public String status() {
        return engine.status();
    }

    @Override
    public int ledCount() {
        return engine.ledCount();
    }

    @Override
    public void destroy() {
        Log.i("Shizuku user service going away");
        engine.stop();
        System.exit(0);
    }
}
