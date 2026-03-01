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
    static final String CSV_ENTREPRISES  = "data/entreprises.csv";
    static final String OFFRES_DIR       = "data/offres";
    static final String CSV_HEADER       = "societe,adresse_postale,code_postal,email_destinataire";
    static final String DOSSIER_BASE     = "outputs";
    static final Set<String> BUCKETS_PROTEGES = Set.of("avec_offre", "sans_offre");

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");

        initOffresDir(out);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/",                    ex -> serveHtml(ex));
        server.createContext("/api/tracker",         ex -> serveJson(ex, getTrackerJson()));
        server.createContext("/api/csv",             ex -> serveJson(ex, getCsvJson(CSV_ENTREPRISES)));
        server.createContext("/api/offres",          ex -> serveOffres(ex));
        server.createContext("/api/buckets",         ex -> serveBuckets(ex));
        server.createContext("/api/create-bucket",   ex -> serveCreateBucket(ex));
        server.createContext("/api/del-bucket",      ex -> serveDelBucket(ex));
        server.createContext("/api/clear-bucket",    ex -> serveClearBucket(ex));
        server.createContext("/api/run",             ex -> serveRun(ex));
        server.createContext("/api/update",          ex -> serveUpdate(ex));
        server.createContext("/api/move-offre",      ex -> serveMoveOffre(ex));
        server.createContext("/api/add-entreprise",  ex -> serveAddEntreprise(ex));
        server.createContext("/api/upd-entreprise",  ex -> serveUpdEntreprise(ex));
        server.createContext("/api/del-entreprise",  ex -> serveDelEntreprise(ex));
        server.createContext("/api/open-folder",     ex -> serveOpenFolder(ex));
        server.createContext("/api/del-tracker",     ex -> serveDelTracker(ex));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        out.println("Dashboard ouvert sur : http://localhost:" + PORT);
        out.println("Ctrl+C pour arrêter.\n");
        try { Desktop.getDesktop().browse(URI.create("http://localhost:" + PORT)); }
        catch (Exception ignored) {}
    }

    /** Initialise le dossier data/offres/ et migre l'ancien data/offres.csv si nécessaire. */
    static void initOffresDir(PrintStream out) {
        new File(OFFRES_DIR).mkdirs();
        // Migration : data/offres.csv → data/offres/avec_offre.csv
        File old = new File("data/offres.csv");
        File avecOffre = new File(OFFRES_DIR, "avec_offre.csv");
        if (old.exists() && !avecOffre.exists()) {
            if (old.renameTo(avecOffre))
                out.println("[INFO] Migré data/offres.csv → data/offres/avec_offre.csv");
        }
        // Créer les fichiers de base s'ils n'existent pas
        for (String b : new String[]{"avec_offre", "sans_offre"}) {
            File f = new File(OFFRES_DIR, b + ".csv");
            if (!f.exists()) {
                try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(f), StandardCharsets.UTF_8))) {
                    pw.println(ScraperOffres.OFFRES_HEADER);
                } catch (Exception ignored) {}
            }
        }
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

    static String getCsvJson(String path) {
        JSONArray arr = new JSONArray();
        File f = new File(path);
        if (!f.exists()) return arr.toString();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String hdr = br.readLine();
            if (hdr == null) return arr.toString();
            if (hdr.startsWith("\uFEFF")) hdr = hdr.substring(1);
            String[] headers = parseLigne(hdr);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] vals = parseLigne(line);
                JSONObject obj = new JSONObject();
                for (int i = 0; i < headers.length; i++)
                    obj.put(headers[i].trim(), i < vals.length ? vals[i].trim() : "");
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
            if      (action.equals("mailing"))             mainClass = "LettreMailing";
            else if (action.equals("envoi"))               mainClass = "EnvoiCandidatures";
            else if (action.equals("scraper"))             mainClass = "ScraperOffres";
            else if (action.equals("scraper-entreprises")) mainClass = "ScraperEntreprises";
            else                                           mainClass = null;
            if (mainClass == null) { sse(pw, "[ERR] action inconnue : " + action); sse(pw, "[DONE]"); return; }

            String java = ProcessHandle.current().info().command().orElse("java");
            String cp   = buildClasspath();
            List<String> cmd = new ArrayList<>(List.of(java, "-cp", cp, "-Dfile.encoding=UTF-8"));

            if (action.equals("scraper")) {
                for (String k : List.of("ville", "codepostal", "rayon", "romes", "domaine", "bucket"))
                    if (p.containsKey(k) && !p.get(k).isEmpty())
                        cmd.add("-Dscraper." + k + "=" + p.get(k));
                cmd.add("-Dscraper.auto=true");
            }
            if (action.equals("scraper-entreprises")) {
                for (String k : List.of("departement", "ape", "domaine", "bucket"))
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

    // ── Gestion entreprises.csv ───────────────────────────────────────────────

    /** Déplace une offre (bucket → entreprises.csv) si pas déjà présente. */
    static void serveMoveOffre(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            cors(ex); ex.sendResponseHeaders(204, -1); return;
        }
        try {
            JSONObject data = new JSONObject(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String societe = data.optString("societe", "").trim();
            if (societe.isEmpty()) { serveJson(ex, "{\"error\":\"societe manquant\"}"); return; }

            List<String[]> lignes = lireCsv(CSV_ENTREPRISES);
            boolean existe = lignes.stream().anyMatch(r -> r.length > 0 && r[0].equalsIgnoreCase(societe));
            if (existe) { serveJson(ex, "{\"ok\":false,\"info\":\"d\u00e9j\u00e0 present\"}"); return; }

            String[] row = {
                societe,
                data.optString("adresse_postale", ""),
                data.optString("code_postal", ""),
                data.optString("email_destinataire", "")
            };
            ajouterLigneCsv(CSV_ENTREPRISES, row);

            // Supprime la ligne du bucket source
            String bucket = sanitizeBucket(data.optString("bucket", "avec_offre"));
            File bucketFile = new File(OFFRES_DIR, bucket + ".csv");
            if (bucketFile.exists()) {
                List<String> rawLines = new ArrayList<>();
                String header2 = null;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(bucketFile), StandardCharsets.UTF_8))) {
                    header2 = br.readLine();
                    String l;
                    while ((l = br.readLine()) != null) {
                        if (l.trim().isEmpty()) continue;
                        String[] parts = parseLigne(l);
                        if (parts.length > 0 && !parts[0].trim().equalsIgnoreCase(societe)) rawLines.add(l);
                    }
                }
                try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(bucketFile, false), StandardCharsets.UTF_8))) {
                    if (header2 != null) pw.println(header2);
                    for (String l : rawLines) pw.println(l);
                }
            }
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** Ajoute une nouvelle ligne dans entreprises.csv. */
    static void serveAddEntreprise(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            cors(ex); ex.sendResponseHeaders(204, -1); return;
        }
        try {
            JSONObject data = new JSONObject(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String[] row = {
                data.optString("societe", "").trim(),
                data.optString("adresse_postale", "").trim(),
                data.optString("code_postal", "").trim(),
                data.optString("email_destinataire", "").trim()
            };
            if (row[0].isEmpty()) { serveJson(ex, "{\"error\":\"societe requis\"}"); return; }
            ajouterLigneCsv(CSV_ENTREPRISES, row);
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** Met à jour une ligne existante (identifiée par original = ancienne valeur de societe). */
    static void serveUpdEntreprise(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            cors(ex); ex.sendResponseHeaders(204, -1); return;
        }
        try {
            JSONObject data = new JSONObject(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String original = data.optString("original", "").trim();

            List<String[]> lignes = lireCsv(CSV_ENTREPRISES);
            boolean found = false;
            for (String[] r : lignes) {
                if (r.length > 0 && r[0].equalsIgnoreCase(original)) {
                    r[0] = data.optString("societe", r[0]).trim();
                    if (r.length > 1) r[1] = data.optString("adresse_postale", r[1]).trim();
                    if (r.length > 2) r[2] = data.optString("code_postal", r[2]).trim();
                    if (r.length > 3) r[3] = data.optString("email_destinataire", r[3]).trim();
                    found = true; break;
                }
            }
            if (!found) { serveJson(ex, "{\"error\":\"entreprise introuvable\"}"); return; }
            ecrireCsv(CSV_ENTREPRISES, lignes);
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** Supprime une ligne d'entreprises.csv. */
    static void serveDelEntreprise(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            cors(ex); ex.sendResponseHeaders(204, -1); return;
        }
        try {
            JSONObject data = new JSONObject(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String societe = data.optString("societe", "").trim();

            List<String[]> lignes = lireCsv(CSV_ENTREPRISES);
            lignes.removeIf(r -> r.length > 0 && r[0].equalsIgnoreCase(societe));
            ecrireCsv(CSV_ENTREPRISES, lignes);
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** Supprime une ligne du tracker (tracker.csv). */
    static void serveDelTracker(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            cors(ex); ex.sendResponseHeaders(204, -1); return;
        }
        try {
            JSONObject data = new JSONObject(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String societe = data.optString("societe", "").trim();

            List<Map<String, String>> rows = TrackerCandidatures.charger();
            rows.removeIf(r -> societe.equalsIgnoreCase(r.get("societe")));
            TrackerCandidatures.sauvegarder(rows);
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** Ouvre l'Explorateur Windows au dossier outputs/<societe>. */
    static void serveOpenFolder(HttpExchange ex) throws IOException {
        cors(ex);
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String societe = p.getOrDefault("societe", "").trim();
        String nomDossier = societe.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        File dossier = new File(DOSSIER_BASE, nomDossier);
        if (!dossier.exists()) dossier.mkdirs();
        try {
            Desktop.getDesktop().open(dossier);
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) {
            serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    // ── Buckets (répertoires data/offres/) ───────────────────────────────────

    /** GET /api/offres?bucket=xxx → contenu JSON du bucket. */
    static void serveOffres(HttpExchange ex) throws IOException {
        cors(ex);
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String bucket = sanitizeBucket(p.getOrDefault("bucket", "avec_offre"));
        serveJson(ex, getCsvJson(new File(OFFRES_DIR, bucket + ".csv").getPath()));
    }

    /** GET /api/buckets → liste JSON [{name, label, protected}]. */
    static void serveBuckets(HttpExchange ex) throws IOException {
        cors(ex);
        File dir = new File(OFFRES_DIR);
        List<String> names = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".csv"));
        if (files != null) for (File f : files) names.add(f.getName().replaceAll("\\.csv$", ""));
        names.sort((a, b) -> {
            boolean ap = BUCKETS_PROTEGES.contains(a), bp = BUCKETS_PROTEGES.contains(b);
            if (ap && !bp) return -1;
            if (!ap && bp) return 1;
            return a.compareTo(b);
        });
        JSONArray arr = new JSONArray();
        for (String name : names) {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("label", name.replace("_", " "));
            o.put("protected", BUCKETS_PROTEGES.contains(name));
            arr.put(o);
        }
        serveJson(ex, arr.toString());
    }

    /** POST /api/create-bucket {name} → crée un nouveau fichier CSV vide. */
    static void serveCreateBucket(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) { cors(ex); ex.sendResponseHeaders(204, -1); return; }
        try {
            JSONObject data = new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String raw = data.optString("name", "").trim();
            if (raw.isEmpty()) { serveJson(ex, "{\"error\":\"nom requis\"}"); return; }
            String safe = raw.toLowerCase().replace(" ", "_").replaceAll("[^a-z0-9_\\-]", "").replaceAll("^_+|_+$", "");
            if (safe.isEmpty()) { serveJson(ex, "{\"error\":\"nom invalide\"}"); return; }
            File f = new File(OFFRES_DIR, safe + ".csv");
            if (!f.exists()) {
                try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
                    pw.println(ScraperOffres.OFFRES_HEADER);
                }
            }
            JSONObject resp = new JSONObject();
            resp.put("ok", true); resp.put("name", safe); resp.put("label", safe.replace("_", " "));
            serveJson(ex, resp.toString());
        } catch (Exception e) { serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
    }

    /** POST /api/del-bucket {name} → supprime un bucket non protégé. */
    static void serveDelBucket(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) { cors(ex); ex.sendResponseHeaders(204, -1); return; }
        try {
            JSONObject data = new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String name = sanitizeBucket(data.optString("name", ""));
            if (BUCKETS_PROTEGES.contains(name)) { serveJson(ex, "{\"error\":\"r\u00e9pertoire prot\u00e9g\u00e9\"}"); return; }
            new File(OFFRES_DIR, name + ".csv").delete();
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) { serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
    }

    /** POST /api/clear-bucket {name} → réécrit le fichier avec seulement le header. */
    static void serveClearBucket(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) { cors(ex); ex.sendResponseHeaders(204, -1); return; }
        try {
            JSONObject data = new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String name = sanitizeBucket(data.optString("name", "avec_offre"));
            File f = new File(OFFRES_DIR, name + ".csv");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
                pw.println(ScraperOffres.OFFRES_HEADER);
            }
            serveJson(ex, "{\"ok\":true}");
        } catch (Exception e) { serveJson(ex, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
    }

    private static String sanitizeBucket(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("^_+|_+$", "");
    }

    // ── Utilitaires CSV ───────────────────────────────────────────────────────

    /** Lit toutes les lignes de données (sans header) d'un CSV. */
    static List<String[]> lireCsv(String path) {
        List<String[]> result = new ArrayList<>();
        File f = new File(path);
        if (!f.exists()) return result;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) result.add(parseLigne(line));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Réécrit entièrement un CSV avec le header standard. */
    static void ecrireCsv(String path, List<String[]> lignes) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(path, false), StandardCharsets.UTF_8))) {
            pw.println(CSV_HEADER);
            for (String[] r : lignes)
                pw.printf("%s,%s,%s,%s%n",
                    echapper(r.length > 0 ? r[0] : ""),
                    echapper(r.length > 1 ? r[1] : ""),
                    echapper(r.length > 2 ? r[2] : ""),
                    echapper(r.length > 3 ? r[3] : ""));
        }
    }

    /** Ajoute une ligne à la fin d'un CSV (crée le fichier avec header si nécessaire). */
    static void ajouterLigneCsv(String path, String[] row) throws IOException {
        File f = new File(path);
        boolean ecrireHeader = !f.exists() || f.length() == 0;
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
            if (ecrireHeader) pw.println(CSV_HEADER);
            pw.printf("%s,%s,%s,%s%n",
                echapper(row.length > 0 ? row[0] : ""),
                echapper(row.length > 1 ? row[1] : ""),
                echapper(row.length > 2 ? row[2] : ""),
                echapper(row.length > 3 ? row[3] : ""));
        }
    }

    static String echapper(String val) {
        if (val.contains(",") || val.contains("\"") || val.contains("\n"))
            return "\"" + val.replace("\"", "\"\"") + "\"";
        return val;
    }

    /** Parser CSV minimal supportant les guillemets. */
    static String[] parseLigne(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens.toArray(new String[0]);
    }

    // ── Utilitaires HTTP ──────────────────────────────────────────────────────

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

    /**
     * Reconstruit le classpath depuis le classloader réel.
     * Nécessaire car exec:java (Maven) charge les classes via un URLClassLoader interne —
     * System.getProperty("java.class.path") ne contient pas target/classes/ ni les dépendances.
     */
    static String buildClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        ClassLoader cl = DashboardServer.class.getClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) cl).getURLs()) {
                    try { entries.add(new File(url.toURI()).getAbsolutePath()); }
                    catch (Exception ignored) {}
                }
            }
            cl = cl.getParent();
        }
        if (entries.isEmpty()) return System.getProperty("java.class.path", "");
        return String.join(File.pathSeparator, entries);
    }

    // HTML servi depuis src/main/resources/dashboard.html (voir serveHtml)
}
