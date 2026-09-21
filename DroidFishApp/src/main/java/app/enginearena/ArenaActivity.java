/*
    Engine Arena - arena match setup + live board.
    GPL v3 (same as the app).
*/
package app.enginearena;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.petero.droidfish.DroidFish;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.ChessBoardPlay;

import java.io.File;
import java.util.Locale;

public class ArenaActivity extends Activity {

    private Spinner whiteSpinner, blackSpinner;
    private EditText whiteMin, whiteInc, blackMin, blackInc, fenInput;
    private Button startStopBtn, classicBtn;
    private TextView status, whiteClock, blackClock, movesView;
    private ChessBoardPlay board;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ArenaController controller;
    private boolean running = false;
    private final StringBuilder moveList = new StringBuilder();
    private int moveNo = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Engine Arena");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        whiteSpinner = engineSpinner();
        blackSpinner = engineSpinner();
        blackSpinner.setSelection(1); // default: two different engines
        root.addView(labeled("White", whiteSpinner));
        root.addView(labeled("Black", blackSpinner));

        LinearLayout clocks = new LinearLayout(this);
        whiteMin = num("5"); whiteInc = num("2");
        blackMin = num("5"); blackInc = num("2");
        clocks.addView(labeled("W min", whiteMin, 1));
        clocks.addView(labeled("W inc", whiteInc, 1));
        clocks.addView(labeled("B min", blackMin, 1));
        clocks.addView(labeled("B inc", blackInc, 1));
        root.addView(clocks);

        fenInput = new EditText(this);
        fenInput.setSingleLine(true);
        fenInput.setText(TextIO.startPosFEN);
        root.addView(labeled("Start FEN", fenInput));

        status = new TextView(this);
        status.setText("Select engines. They download on first use.");
        status.setTextColor(Color.DKGRAY);
        root.addView(status);

        LinearLayout btns = new LinearLayout(this);
        startStopBtn = new Button(this);
        startStopBtn.setText("Start match");
        startStopBtn.setOnClickListener(v -> onStartStop());
        classicBtn = new Button(this);
        classicBtn.setText("Classic board");
        classicBtn.setOnClickListener(v ->
            startActivity(new android.content.Intent(this, DroidFish.class)));
        btns.addView(startStopBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        btns.addView(classicBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(btns);

        whiteClock = clockView(); blackClock = clockView();
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.addView(whiteClock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clockRow.addView(blackClock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(clockRow);

        board = new ChessBoardPlay(this, null);
        root.addView(board, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 10));

        movesView = new TextView(this);
        movesView.setTextSize(13);
        movesView.setTextColor(Color.BLACK);
        ScrollView sv = new ScrollView(this);
        sv.addView(movesView);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4));

        setContentView(root);
        try { board.setPosition(TextIO.readFEN(TextIO.startPosFEN)); } catch (Exception ignored) {}

        AdapterView.OnItemSelectedListener sel = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { ensureInstalled(i); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        whiteSpinner.setOnItemSelectedListener(sel);
        blackSpinner.setOnItemSelectedListener(sel);
        ensureInstalled(whiteSpinner.getSelectedItemPosition());
        ensureInstalled(blackSpinner.getSelectedItemPosition());
    }

    private EngineManager.EngineInfo selected(Spinner s) {
        return EngineManager.CATALOG[s.getSelectedItemPosition()];
    }

    private void ensureInstalled(int idx) {
        EngineManager.EngineInfo info = EngineManager.CATALOG[idx];
        if (EngineManager.isInstalled(this, info.id)) {
            setStatus(info.displayName + " ready.");
            return;
        }
        setStatus("Installing " + info.label() + "...");
        EngineManager.install(this, info, new EngineManager.Progress() {
            @Override public void onProgress(String msg) { ui.post(() -> setStatus(msg)); }
            @Override public void onDone(File engineBinary) {
                ui.post(() -> setStatus(info.displayName + " ready."));
            }
            @Override public void onError(String err) {
                ui.post(() -> setStatus(err));
            }
        });
    }

    private void onStartStop() {
        if (running) {
            if (controller != null) controller.stop();
            running = false;
            startStopBtn.setText("Start match");
            setStatus("Stopped.");
            return;
        }
        EngineManager.EngineInfo w = selected(whiteSpinner);
        EngineManager.EngineInfo b = selected(blackSpinner);
        if (!EngineManager.isInstalled(this, w.id) || !EngineManager.isInstalled(this, b.id)) {
            setStatus("Engines still downloading...");
            return;
        }
        String fen = fenInput.getText().toString().trim();
        if (fen.isEmpty()) fen = TextIO.startPosFEN;
        try {
            TextIO.readFEN(fen); // validate
        } catch (Exception ex) {
            new AlertDialog.Builder(this).setTitle("Bad FEN").setMessage(ex.getMessage())
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        long wBase = parseMin(whiteMin), wInc = parseSec(whiteInc);
        long bBase = parseMin(blackMin), bInc = parseSec(blackInc);
        moveList.setLength(0); moveNo = 1;
        movesView.setText("");
        final String finalFen = fen;
        controller = new ArenaController(this,
                EngineManager.engineBinary(this, w.id),
                EngineManager.engineBinary(this, b.id),
                finalFen, wBase, wInc, bBase, bInc,
                new ArenaController.Listener() {
                    @Override public void onStatus(String msg) { ui.post(() -> setStatus(msg)); }
                    @Override public void onMove(Position pos, String san, long wMs, long bMs) {
                        ui.post(() -> {
                            board.setPosition(new Position(pos));
                            appendMove(san);
                            whiteClock.setText(fmt(wMs));
                            blackClock.setText(fmt(bMs));
                        });
                    }
                    @Override public void onGameOver(String result, String reason) {
                        ui.post(() -> {
                            running = false;
                            startStopBtn.setText("Start match");
                            setStatus(result + " - " + reason);
                        });
                    }
                    @Override public void onError(String err) {
                        ui.post(() -> {
                            running = false;
                            startStopBtn.setText("Start match");
                            setStatus("Error: " + err);
                        });
                    }
                });
        running = true;
        startStopBtn.setText("Stop");
        whiteClock.setText(fmt(wBase));
        blackClock.setText(fmt(bBase));
        controller.start();
        setStatus(w.displayName + " vs " + b.displayName);
    }

    private void appendMove(String san) {
        if (moveNo % 2 == 1) moveList.append((moveNo / 2 + 1)).append(". ");
        moveList.append(san).append(' ');
        moveNo++;
        movesView.setText(moveList.toString());
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private long parseMin(EditText e) { return (long)(parseDouble(e, 5) * 60_000); }
    private long parseSec(EditText e) { return (long)(parseDouble(e, 2) * 1_000); }
    private double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private void setStatus(String msg) { status.setText(msg); }

    private Spinner engineSpinner() {
        Spinner s = new Spinner(this);
        String[] labels = new String[EngineManager.CATALOG.length];
        for (int i = 0; i < labels.length; i++) labels[i] = EngineManager.CATALOG[i].label();
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        return s;
    }

    private TextView clockView() {
        TextView t = new TextView(this);
        t.setTextSize(24);
        t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        t.setTextColor(Color.BLACK);
        t.setText("0:00");
        return t;
    }

    private EditText num(String def) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(def);
        return e;
    }

    private LinearLayout labeled(String label, View v) { return labeled(label, v, -1); }
    private LinearLayout labeled(String label, View v, int weight) {
        LinearLayout l = new LinearLayout(this);
        TextView t = new TextView(this);
        t.setText(label + ": ");
        t.setTextColor(Color.BLACK);
        l.addView(t);
        if (weight > 0) l.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        else l.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.stop();
        super.onDestroy();
    }
}
