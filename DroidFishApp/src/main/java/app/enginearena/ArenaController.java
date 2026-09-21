/*
    Engine Arena - engine-vs-engine game driver (UCI).
    GPL v3 (same as the app).
*/
package app.enginearena;

import android.content.Context;

import org.petero.droidfish.EngineOptions;
import org.petero.droidfish.engine.UCIEngine;
import org.petero.droidfish.engine.UCIEngineBase;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.MoveGen;
import org.petero.droidfish.gamelogic.UndoInfo;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays one engine-vs-engine game over UCI with independent clocks.
 * Each engine runs in its own process, so mirror matches (same engine
 * twice) are fully independent instances.
 */
public class ArenaController {

    public interface Listener {
        void onStatus(String msg);
        void onMove(Position pos, String san, long whiteMsLeft, long blackMsLeft);
        void onGameOver(String result, String reason);
        void onError(String err);
    }

    private final Context context;
    private final File whiteEngine;
    private final File blackEngine;
    private final String startFen;
    private final long[] baseMs = new long[2];
    private final long[] incMs = new long[2];
    private final Listener listener;

    private volatile boolean stopped = false;
    private Thread gameThread;

    public ArenaController(Context context, File whiteEngine, File blackEngine,
                           String startFen,
                           long whiteBaseMs, long whiteIncMs,
                           long blackBaseMs, long blackIncMs,
                           Listener listener) {
        this.context = context.getApplicationContext();
        this.whiteEngine = whiteEngine;
        this.blackEngine = blackEngine;
        this.startFen = startFen;
        this.baseMs[0] = whiteBaseMs;  this.incMs[0] = whiteIncMs;
        this.baseMs[1] = blackBaseMs;  this.incMs[1] = blackIncMs;
        this.listener = listener;
    }

    public void start() {
        gameThread = new Thread(this::playGame, "arena-game");
        gameThread.start();
    }

    public void stop() {
        stopped = true;
        if (gameThread != null) gameThread.interrupt();
    }

    private static final UCIEngine.Report NO_REPORT = err -> { };

    private void playGame() {
        UCIEngine white = null, black = null;
        try {
            EngineOptions opts = new EngineOptions();
            listener.onStatus("Starting " + whiteEngine.getName() + "...");
            white = UCIEngineBase.getEngine(whiteEngine.getAbsolutePath(), opts, NO_REPORT);
            white.initialize();
            listener.onStatus("Starting " + blackEngine.getName() + "...");
            black = UCIEngineBase.getEngine(blackEngine.getAbsolutePath(), opts, NO_REPORT);
            black.initialize();

            handshake(white, "White");
            handshake(black, "Black");

            Position pos = TextIO.readFEN(startFen);
            List<String> uciMoves = new ArrayList<>();
            Map<String, Integer> repCount = new HashMap<>();
            long[] clock = { baseMs[0], baseMs[1] }; // 0=white, 1=black

            while (!stopped) {
                int side = pos.whiteMove ? 0 : 1;
                UCIEngine eng = side == 0 ? white : black;
                StringBuilder cmd = new StringBuilder("position fen ").append(startFen);
                if (!uciMoves.isEmpty()) {
                    cmd.append(" moves");
                    for (String m : uciMoves) cmd.append(' ').append(m);
                }
                eng.writeLineToEngine(cmd.toString());
                eng.writeLineToEngine("go wtime " + clock[0] + " btime " + clock[1]
                        + " winc " + incMs[0] + " binc " + incMs[1]);

                long t0 = System.currentTimeMillis();
                String best = null, ponderLine;
                while (!stopped) {
                    long elapsed = System.currentTimeMillis() - t0;
                    long remaining = clock[side] - elapsed;
                    if (remaining < -250) { // grace for process latency
                        gameOver(side == 0 ? "0-1" : "1-0",
                                (side == 0 ? "White" : "Black") + " lost on time");
                        return;
                    }
                    ponderLine = eng.readLineFromEngine(200);
                    if (ponderLine == null) continue;
                    if (ponderLine.startsWith("bestmove")) {
                        String[] parts = ponderLine.split("\\s+");
                        best = parts.length > 1 ? parts[1] : null;
                        break;
                    }
                }
                if (stopped) return;
                long used = System.currentTimeMillis() - t0;
                clock[side] = Math.max(0, clock[side] - used + incMs[side]);

                if (best == null || best.equals("(none)") || best.equals("0000")) {
                    // Engine has no move: mate or stalemate
                    boolean inCheck = MoveGen.inCheck(pos);
                    if (inCheck) {
                        gameOver(side == 0 ? "0-1" : "1-0",
                                "Checkmate - " + (side == 0 ? "Black" : "White") + " wins");
                    } else {
                        gameOver("1/2-1/2", "Stalemate");
                    }
                    return;
                }

                Move move = TextIO.UCIstringToMove(best);
                if (move == null) { listener.onError("Bad move from engine: " + best); return; }
                String san = TextIO.moveToString(pos, move, false, true);
                ArrayList<Move> legal = new MoveGen().legalMoves(pos);
                boolean ok = false;
                for (Move lm : legal) if (lm.equals(move)) { ok = true; break; }
                if (!ok) {
                    gameOver(side == 0 ? "0-1" : "1-0",
                            (side == 0 ? "White" : "Black") + " played an illegal move");
                    return;
                }
                // key = FEN without halfmove/fullmove counters (recorded AFTER the move)
                String posKey = fenKey(pos);
                uciMoves.add(best);
                pos.makeMove(move, new UndoInfo());
                listener.onMove(pos, san, clock[0], clock[1]);

                // Draw detection: 50-move rule and threefold repetition
                if (pos.halfMoveClock >= 100) { gameOver("1/2-1/2", "50-move rule"); return; }
                repCount.merge(posKey, 1, Integer::sum);
                if (repCount.getOrDefault(posKey, 0) >= 3) { gameOver("1/2-1/2", "Threefold repetition"); return; }
            }
        } catch (Exception ex) {
            if (!stopped) listener.onError("Arena error: " + ex.getMessage());
        } finally {
            if (white != null) { try { white.writeLineToEngine("quit"); white.shutDown(); } catch (Exception ignored) {} }
            if (black != null) { try { black.writeLineToEngine("quit"); black.shutDown(); } catch (Exception ignored) {} }
        }
    }

    private static String fenKey(Position pos) {
        String fen = TextIO.toFEN(pos);
        String[] f = fen.split(" ");
        return f.length >= 4 ? f[0] + " " + f[1] + " " + f[2] + " " + f[3] : fen;
    }

    private void handshake(UCIEngine eng, String who) throws Exception {
        eng.writeLineToEngine("uci");
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && !stopped) {
            String l = eng.readLineFromEngine(500);
            if (l == null) continue;
            if (l.equals("uciok")) break;
        }
        eng.writeLineToEngine("isready");
        deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && !stopped) {
            String l = eng.readLineFromEngine(500);
            if (l == null) continue;
            if (l.equals("readyok")) { eng.writeLineToEngine("ucinewgame"); return; }
        }
        throw new Exception(who + " engine did not answer UCI handshake");
    }

    private void gameOver(String result, String reason) {
        if (!stopped) listener.onGameOver(result, reason);
    }
}
