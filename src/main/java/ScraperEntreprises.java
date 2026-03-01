import org.json.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Scrape des entreprises via l'API officielle recherche-entreprises.api.gouv.fr
 * (INSEE / data.gouv.fr — gratuite, sans clé API).
 *
 * Recherche par code(s) APE + département.
 * Les résultats sont ajoutés à data/offres.csv (doublons ignorés).
 *
 * Exécution :
 *   mvn compile exec:java "-Dexec.mainClass=ScraperEntreprises"
 *
 * Config (via System properties ou config/config.properties) :
 *   scraper.departement=14
 *   scraper.ape=62.01Z,62.02A
 *   scraper.domaine=Développement informatique
 */
public class ScraperEntreprises {

    private static final String API_URL    = "https://recherche-entreprises.api.gouv.fr/search";
    private static final String CONFIG_FILE = "config/config.properties";
    private static final int    PER_PAGE    = 25;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        Scanner scanner = new Scanner(System.in, "UTF-8");

        Properties config = chargerConfig(out);
        boolean autoMode = "true".equals(System.getProperty("scraper.auto", "false"));

        String departement = System.getProperty("scraper.departement",
                             config.getProperty("scraper.departement", "")).trim();
        String apes        = System.getProperty("scraper.ape",
                             config.getProperty("scraper.ape", "")).trim();
        String domaine     = System.getProperty("scraper.domaine",
                             config.getProperty("scraper.domaine", "")).trim();
        String bucket      = System.getProperty("scraper.bucket",
                             config.getProperty("scraper.bucket", "sans_offre")).trim();
        bucket = bucket.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        new File(ScraperOffres.OFFRES_DIR).mkdirs();
        String offresFile  = ScraperOffres.OFFRES_DIR + "/" + bucket + ".csv";

        if (!autoMode) {
            if (departement.isEmpty()) {
                out.print("Département (ex: 14 pour Calvados) : ");
                departement = scanner.nextLine().trim();
            }
            if (apes.isEmpty()) {
                out.println("Code(s) APE séparés par des virgules");
                out.println("  Ex: 62.01Z (programmation), 62.02A (conseil sys. info.)");
                out.println("  Ou tapez 'info' pour voir les codes courants.");
                out.print("Code(s) APE : ");
                String rep = scanner.nextLine().trim();
                if (rep.equalsIgnoreCase("info")) {
                    afficherCodesAPE(out);
                    out.print("Code(s) APE : ");
                    rep = scanner.nextLine().trim();
                }
                apes = rep;
            }
            if (domaine.isEmpty()) {
                out.print("Domaine (étiquette libre, ex: Informatique) : ");
                domaine = scanner.nextLine().trim();
            }
        }

        if (departement.isEmpty() || apes.isEmpty()) {
            out.println("[ERREUR] Département et code APE sont obligatoires.");
            scanner.close(); return;
        }

        // Chargement des sociétés existantes (éviter doublons)
        Set<String> existants = chargerSocietesExistantes();

        List<Map<String, String>> toutes = new ArrayList<>();
        String[] codesApe = apes.split(",");
        for (String ape : codesApe) {
            ape = ape.trim();
            if (ape.isEmpty()) continue;
            out.println("\nRecherche APE " + ape + " dans le département " + departement + "...");
            List<Map<String, String>> resultats = rechercherEntreprises(departement, ape, out);
            out.println("  → " + resultats.size() + " entreprise(s) trouvée(s)");
            for (Map<String, String> r : resultats) {
                String soc = r.getOrDefault("societe", "").toLowerCase();
                if (!soc.isEmpty() && !toutes.stream().anyMatch(x -> x.getOrDefault("societe","").equalsIgnoreCase(r.get("societe"))))
                    toutes.add(r);
            }
        }

        if (toutes.isEmpty()) {
            out.println("Aucune entreprise trouvée. Essayez un autre département ou code APE.");
            scanner.close(); return;
        }

        // Filtrer celles qui existent déjà (pour l'affichage)
        List<Map<String, String>> nouvelles = new ArrayList<>();
        for (Map<String, String> e : toutes)
            if (!existants.contains(e.getOrDefault("societe","").toLowerCase()))
                nouvelles.add(e);

        out.println("\n" + toutes.size() + " entreprise(s) trouvée(s), dont "
                  + nouvelles.size() + " nouvelle(s) :\n");
        out.printf("%-4s %-40s %-10s %-25s%n", "#", "Société", "CP", "Ville");
        out.println("-".repeat(82));
        for (int i = 0; i < toutes.size(); i++) {
            Map<String, String> e = toutes.get(i);
            boolean deja = existants.contains(e.getOrDefault("societe","").toLowerCase());
            out.printf("%-4s %-40s %-10s %-25s%s%n",
                (i + 1),
                tronquer(e.getOrDefault("societe", ""), 39),
                e.getOrDefault("code_postal", ""),
                tronquer(e.getOrDefault("ville", ""), 24),
                deja ? "  [déjà dans CSV]" : "");
        }

        String reponse;
        if (autoMode) {
            reponse = "tout";
            out.println("\nMode auto : ajout de toutes les entreprises trouvées.");
        } else {
            out.println("\nEntrez les numéros à ajouter dans offres.csv");
            out.println("(ex: 1,3,5  ou  tout  pour tout prendre) :");
            out.print("> ");
            reponse = scanner.nextLine().trim();
        }
        scanner.close();

        List<Map<String, String>> selectionnes = selectionner(reponse, toutes);
        if (selectionnes.isEmpty()) { out.println("Aucune sélection. Abandon."); return; }

        // Migration CSV si nécessaire
        ScraperOffres.migrerOffresCSV(offresFile);

        int ajouts = 0, ignores = 0;
        boolean ecrireHeader = !new File(offresFile).exists() || new File(offresFile).length() == 0;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(offresFile, true), StandardCharsets.UTF_8))) {
            if (ecrireHeader)
                pw.println(ScraperOffres.OFFRES_HEADER);
            for (Map<String, String> e : selectionnes) {
                String societe = e.getOrDefault("societe", "").trim();
                if (societe.isEmpty() || existants.contains(societe.toLowerCase())) {
                    ignores++; continue;
                }
                // code_postal contient déjà "CP VILLE" dans la réponse API
                String cp = e.getOrDefault("code_postal", "");
                String ville = e.getOrDefault("ville", "");
                String cpVille = (cp + (ville.isEmpty() ? "" : " " + ville)).trim();
                pw.printf("%s,%s,%s,%s,%s,%s%n",
                    echapper(societe),
                    echapper(e.getOrDefault("adresse_postale", "")),
                    echapper(cpVille),
                    echapper(""),   // email_destinataire non fourni par l'API
                    echapper(domaine),
                    echapper(""));  // url non applicable
                ajouts++;
            }
        }

        out.println("\n--- Résumé ---");
        out.println("Ajoutées        : " + ajouts);
        if (ignores > 0) out.println("Ignorées (déjà dans le CSV) : " + ignores);
        if (ajouts > 0)
            out.println("\nOuvrez le Dashboard → onglet Offres pour ajouter les entreprises souhaitées.");
    }

    // -------------------------------------------------------------------------
    // Appel API recherche-entreprises.api.gouv.fr
    // -------------------------------------------------------------------------
    private static List<Map<String, String>> rechercherEntreprises(
            String departement, String ape, PrintStream out) throws Exception {

        List<Map<String, String>> result = new ArrayList<>();
        int page = 1;
        int total = -1;

        while (true) {
            String url = API_URL
                + "?activite_principale=" + URLEncoder.encode(ape, StandardCharsets.UTF_8)
                + "&departement=" + URLEncoder.encode(departement, StandardCharsets.UTF_8)
                + "&per_page=" + PER_PAGE
                + "&page=" + page
                + "&etat_administratif=A";   // A = actives uniquement

            String body;
            try { body = get(url, 15); }
            catch (Exception e) {
                out.println("[AVERTISSEMENT] Erreur réseau page " + page + " : " + e.getMessage());
                break;
            }

            JSONObject root = new JSONObject(body);
            if (total < 0) total = root.optInt("total_results", 0);

            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) break;

            for (int i = 0; i < results.length(); i++) {
                Map<String, String> e = extraireEntreprise(results.optJSONObject(i));
                if (e != null) result.add(e);
            }

            // Limiter à 100 résultats max pour ne pas surcharger
            if (result.size() >= total || result.size() >= 100 || results.length() < PER_PAGE) break;
            page++;
        }

        // Post-filtre : on ne conserve que les établissements dont le code postal
        // commence par le numéro de département (ex: "14" → CP "14000", "14100"…).
        // Certains appels API retournent des résultats hors département — ce filtre corrige cela.
        if (!departement.isEmpty()) {
            final String deptNum = departement.trim().replaceAll("[^0-9]", "");
            if (!deptNum.isEmpty()) {
                result.removeIf(e -> {
                    String cp = e.getOrDefault("code_postal", "").trim().replaceAll("[^0-9]", "");
                    return !cp.isEmpty() && !cp.startsWith(deptNum);
                });
            }
        }
        return result;
    }

    private static Map<String, String> extraireEntreprise(JSONObject obj) {
        if (obj == null) return null;
        try {
            Map<String, String> e = new LinkedHashMap<>();

            // Nom de l'entreprise (dénomination ou nom_complet)
            String nom = obj.optString("nom_complet", "").trim();
            if (nom.isEmpty()) nom = obj.optString("denomination", "").trim();
            if (nom.isEmpty()) return null;
            e.put("societe", nom.toUpperCase());

            // Siège social
            JSONObject siege = obj.optJSONObject("siege");
            if (siege == null) siege = new JSONObject();

            String adresse = siege.optString("adresse", "").trim();
            String cp      = siege.optString("code_postal", "").trim();
            String ville   = siege.optString("libelle_commune", "").trim();

            e.put("adresse_postale", adresse);
            e.put("code_postal", cp);
            e.put("ville", ville);

            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------
    private static void afficherCodesAPE(PrintStream out) {
        out.println("\nCodes APE courants — Informatique & Numérique :");
        out.println("  62.01Z  Programmation informatique");
        out.println("  62.02A  Conseil en systèmes et logiciels informatiques");
        out.println("  62.02B  Tierce maintenance de systèmes et d'applications");
        out.println("  62.03Z  Gestion d'installations informatiques");
        out.println("  62.09Z  Autres activités informatiques");
        out.println("  63.11Z  Traitement de données, hébergement et activités connexes");
        out.println("  63.12Z  Portails internet");
        out.println("  58.29A  Édition de logiciels systèmes et de réseau");
        out.println("  58.29B  Édition de logiciels outils");
        out.println("  58.29C  Édition de logiciels applicatifs");
        out.println("  71.12B  Ingénierie, études techniques (bureau d'études)");
        out.println("  70.22Z  Conseil pour les affaires et autres conseils de gestion\n");
    }

    private static String get(String url, int timeoutSeconds) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "AlternionApp/1.0")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400)
            throw new IOException("HTTP " + response.statusCode() + " pour " + url);
        return response.body();
    }

    private static List<Map<String, String>> selectionner(String reponse,
                                                           List<Map<String, String>> list) {
        if (reponse.equalsIgnoreCase("tout") || reponse.equalsIgnoreCase("all"))
            return new ArrayList<>(list);
        List<Map<String, String>> sel = new ArrayList<>();
        for (String part : reponse.split(",")) {
            try {
                int idx = Integer.parseInt(part.trim()) - 1;
                if (idx >= 0 && idx < list.size()) sel.add(list.get(idx));
            } catch (NumberFormatException ignored) {}
        }
        return sel;
    }

    private static Set<String> chargerSocietesExistantes() {
        Set<String> set = new HashSet<>();
        List<File> fichiers = new ArrayList<>();
        fichiers.add(new File("data/entreprises.csv"));
        File dir = new File(ScraperOffres.OFFRES_DIR);
        if (dir.exists()) {
            File[] buckets = dir.listFiles((d, n) -> n.endsWith(".csv"));
            if (buckets != null) for (File b : buckets) fichiers.add(b);
        }
        for (File f : fichiers) {
            if (!f.exists()) continue;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                br.readLine(); // skip header
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",", -1);
                    if (parts.length > 0)
                        set.add(parts[0].trim().toLowerCase().replace("\"", ""));
                }
            } catch (Exception ignored) {}
        }
        return set;
    }

    private static Properties chargerConfig(PrintStream out) {
        Properties p = new Properties();
        File f = new File(CONFIG_FILE);
        if (!f.exists()) return p;
        try (FileInputStream fis = new FileInputStream(f)) { p.load(fis); }
        catch (Exception e) { out.println("[AVERTISSEMENT] Lecture config : " + e.getMessage()); }
        return p;
    }

    private static String echapper(String val) {
        if (val.contains(",") || val.contains("\"") || val.contains("\n"))
            return "\"" + val.replace("\"", "\"\"") + "\"";
        return val;
    }

    private static String tronquer(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
