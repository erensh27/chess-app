/*
    Engine Arena - engine catalog + on-demand downloader.
    GPL v3 (same as the app).
*/
package app.enginearena;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.petero.droidfish.engine.EngineUtil;

/** Catalog of downloadable engines + install management. */
public class EngineManager {

    public static class EngineInfo {
        public final String id;
        public final String displayName;
        public final int ccrl;
        public EngineInfo(String id, String displayName, int ccrl) {
            this.id = id; this.displayName = displayName; this.ccrl = ccrl;
        }
        public String label() { return displayName + " (" + ccrl + ")"; }
    }

    /** Launch roster: exactly these three, nothing else. */
    public static final EngineInfo[] CATALOG = {
        new EngineInfo("stockfish", "Stockfish 19", 3631),
        new EngineInfo("patricia",  "Patricia 5.1", 3488),
        new EngineInfo("ravager",   "Ravager 2.0",  3194),
    };

    private static final String RELEASE_BASE =
        "https://github.com/erensh27/chess-app/releases/download/engines-latest/";

    public interface Progress {
        void onProgress(String msg);
        void onDone(File engineBinary);
        void onError(String err);
    }

    public static String deviceAbi() {
        for (String abi : Build.SUPPORTED_ABIS)
            if ("arm64-v8a".equals(abi)) return "arm64-v8a";
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
    }

    public static File engineDir(Context ctx, String id) {
        return new File(ctx.getFilesDir(), "engines/" + id);
    }

    public static File engineBinary(Context ctx, String id) {
        return new File(engineDir(ctx, id), "lib" + id + ".so");
    }

    public static boolean isInstalled(Context ctx, String id) {
        File f = engineBinary(ctx, id);
        return f.isFile() && f.length() > 0;
    }

    public static String downloadUrl(String id, String abi) {
        return RELEASE_BASE + id + "-" + abi + ".zip";
    }

    /** Download + unzip + chmod the engine on a background thread. */
    public static void install(Context ctx, EngineInfo info, Progress cb) {
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String abi = deviceAbi();
                cb.onProgress("Downloading " + info.displayName + "...");
                URL url = new URL(downloadUrl(info.id, abi));
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    cb.onError("Download failed (HTTP " + code + ") for " + url);
                    return;
                }
                File dir = engineDir(appCtx, info.id);
                dir.mkdirs();
                File out = engineBinary(appCtx, info.id);
                boolean found = false;
                try (ZipInputStream zis = new ZipInputStream(conn.getInputStream())) {
                    ZipEntry e;
                    byte[] buf = new byte[65536];
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.getName().endsWith(".so")) {
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                int n;
                                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                            }
                            found = true;
                        }
                        zis.closeEntry();
                    }
                }
                if (!found) { cb.onError("No engine binary inside the zip"); return; }
                EngineUtil.chmod(out.getAbsolutePath());
                cb.onProgress("Installed " + info.displayName);
                cb.onDone(out);
            } catch (Exception ex) {
                cb.onError("Install failed: " + ex.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "engine-install").start();
    }
}
