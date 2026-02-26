import com.sun.net.httpserver.*;
import org.json.*;
import java.awt.Desktop;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Serveur web local pour gérer les candidatures depuis le navigateur.
 *
 * Exécution :
 *   mvn compile exec:java "-Dexec.mainClass=DashboardServer"
 *
 * Ouvre automatiquement http://localhost:8080
 */
public class DashboardServer {

    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/",           ex -> serveHtml(ex));
        server.createContext("/api/tracker",ex -> serveJson(ex, getTrackerJson()));
        server.createContext("/api/csv",    ex -> serveJson(ex, getCsvJson()));
        server.createContext("/api/run",    ex -> serveRun(ex));
        server.createContext("/api/update", ex -> serveUpdate(ex));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        out.println("Dashboard ouvert sur : http://localhost:" + PORT);
        out.println("Ctrl+C pour arrêter.\n");
        try { Desktop.getDesktop().browse(URI.create("http://localhost:" + PORT)); }
        catch (Exception ignored) {}
    }

    // ── HTML ─────────────────────────────────────────────────────────────────

    static void serveHtml(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            ex.sendResponseHeaders(404, -1); return;
        }
        byte[] b;
        try (InputStream is = DashboardServer.class.getResourceAsStream("/dashboard.html")) {
            b = (is != null) ? is.readAllBytes() : "dashboard.html introuvable".getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    static void serveJson(HttpExchange ex, String json) throws IOException {
        cors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }

    static String getTrackerJson() {
        List<Map<String, String>> rows = TrackerCandidatures.charger();
        JSONArray arr = new JSONArray();
        for (Map<String, String> row : rows) arr.put(new JSONObject(row));
        return arr.toString();
    }

    static String getCsvJson() {
        JSONArray arr = new JSONArray();
        File f = new File("data/entreprises.csv");
        if (!f.exists()) return arr.toString();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String hdr = br.readLine();
            if (hdr == null) return arr.toString();
            if (hdr.startsWith("\uFEFF")) hdr = hdr.substring(1);
            String[] headers = hdr.split(",");
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] vals = line.split(",", -1);
                JSONObject obj = new JSONObject();
                for (int i = 0; i < headers.length; i++)
                    obj.put(headers[i].trim(), i < vals.length ? vals[i].trim().replace("\"", "") : "");
                arr.put(obj);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    // ── SSE — lancement des programmes ───────────────────────────────────────

    static void serveRun(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String action = p.getOrDefault("action", "");
        cors(ex);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8), true)) {

            String mainClass;
            if      (action.equals("mailing")) mainClass = "LettreMailing";
            else if (action.equals("envoi"))   mainClass = "EnvoiCandidatures";
            else if (action.equals("scraper")) mainClass = "ScraperOffres";
            else                               mainClass = null;
            if (mainClass == null) { sse(pw, "[ERR] action inconnue : " + action); sse(pw, "[DONE]"); return; }

            String java = ProcessHandle.current().info().command().orElse("java");
            String cp   = System.getProperty("java.class.path");
            List<String> cmd = new ArrayList<>(List.of(java, "-cp", cp, "-Dfile.encoding=UTF-8"));

            if (action.equals("scraper")) {
                for (String k : List.of("ville", "codepostal", "rayon", "romes"))
                    if (p.containsKey(k) && !p.get(k).isEmpty())
                        cmd.add("-Dscraper." + k + "=" + p.get(k));
                cmd.add("-Dscraper.auto=true");
            }
            cmd.add(mainClass);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File(System.getProperty("user.dir")));
            Process proc = pb.start();

            if (action.equals("envoi")) {
                proc.getOutputStream().write("oui\n".getBytes(StandardCharsets.UTF_8));
                proc.getOutputStream().flush();
            }
            proc.getOutputStream().close();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sse(pw, line);
            }
            proc.waitFor();
            sse(pw, "[DONE]");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Mise à jour du tracker ────────────────────────────────────────────────

    static void serveUpdate(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            cors(ex); ex.sendResponseHeaders(204, -1); return;
        }
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            JSONObject data = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String societe = data.getString("societe");

            List<Map<String, String>> rows = TrackerCandidatures.charger();
            for (Map<String, String> row : rows) {
                if (societe.equals(row.get("societe"))) {
                    if (data.has("statut")) row.put("statut", data.getString("statut"));
                    if (data.has("notes"))  row.put("notes",  data.getString("notes"));
                    break;
                }
            }
            TrackerCandidatures.sauvegarder(rows);
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    static void sse(PrintWriter pw, String data) {
        pw.print("data: " + data + "\n\n");
        pw.flush();
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String s : q.split("&")) {
            int i = s.indexOf('=');
            if (i > 0) try {
                map.put(URLDecoder.decode(s.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(s.substring(i + 1), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return map;
    }

    // HTML servi depuis src/main/resources/dashboard.html (voir serveHtml)
}
