import org.json.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Scrape les offres d'alternance via :
 *  - api-adresse.data.gouv.fr  (géocodage ville → lat/lon, sans inscription)
 *  - La Bonne Alternance API   (offres alternance, sans inscription)
 *
 * Les résultats sont ajoutés à data/entreprises.csv (doublons ignorés).
 * Relancer LettreMailing ensuite pour générer les LM.
 *
 * Config dans config/config.properties :
 *   scraper.ville=Paris
 *   scraper.codepostal=75001
 *   scraper.rayon=30
 *   scraper.romes=M1805,M1810
 *
 * Exécution :
 *   mvn compile exec:java "-Dexec.mainClass=ScraperOffres"
 */
public class ScraperOffres {

    private static final String GEO_API    = "https://api-adresse.data.gouv.fr/search/";
    private static final String LBA_API    = "https://labonnealternance.apprentissage.beta.gouv.fr/api/v1/jobs/search";
    private static final String CSV_FILE   = "data/entreprises.csv";
    private static final String CONFIG_FILE = "config/config.properties";

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        Scanner scanner = new Scanner(System.in, "UTF-8");

        // --- Lecture config (system properties prioritaires sur config.properties) ---
        Properties config = chargerConfig(out);
        boolean autoMode = "true".equals(System.getProperty("scraper.auto", "false"));
        String ville      = System.getProperty("scraper.ville",      config.getProperty("scraper.ville",      "")).trim();
        String codePostal = System.getProperty("scraper.codepostal", config.getProperty("scraper.codepostal", "")).trim();
        String rayon      = System.getProperty("scraper.rayon",      config.getProperty("scraper.rayon",      "30")).trim();
        String romes      = System.getProperty("scraper.romes",      config.getProperty("scraper.romes",      "")).trim();

        // Saisie interactive seulement si pas en mode auto
        if (!autoMode) {
            if (ville.isEmpty()) {
                out.print("Ville de recherche     : "); ville = scanner.nextLine().trim();
            }
            if (codePostal.isEmpty()) {
                out.print("Code postal (optionnel): "); codePostal = scanner.nextLine().trim();
            }
            if (romes.isEmpty()) {
                out.println("Codes ROME (ex: M1805,M1810 pour dev informatique)");
                out.println("R\u00e9f\u00e9rence : https://www.francetravail.fr/candidat/metiers/les-fiches-metiers.html");
                out.print("Codes ROME : "); romes = scanner.nextLine().trim();
            }
        }

        // --- Géocodage ---
        out.println("\nGéolocalisation de « " + ville + " " + codePostal + " »...");
        double[] coords = geocoder(ville, codePostal);
        if (coords == null) {
            System.err.println("Impossible de géolocaliser cette ville. Vérifiez le nom.");
            System.exit(1);
        }
        out.printf("Coordonnées : %.4f, %.4f%n%n", coords[0], coords[1]);

        // --- Appel La Bonne Alternance ---
        out.println("Recherche d'offres (rayon " + rayon + " km)...");
        List<Map<String, String>> offres = rechercherOffres(coords[0], coords[1], rayon, romes, out);

        if (offres.isEmpty()) {
            out.println("Aucune entreprise trouvée. Essayez d'autres codes ROME ou un rayon plus grand.");
            scanner.close();
            return;
        }

        // --- Affichage ---
        out.println(offres.size() + " entreprise(s) trouvée(s) :\n");
        out.printf("%-4s %-35s %-10s %-30s%n", "#", "Société", "CP", "Email");
        out.println("-".repeat(82));
        for (int i = 0; i < offres.size(); i++) {
            Map<String, String> o = offres.get(i);
            out.printf("%-4d %-35s %-10s %-30s%n",
                i + 1,
                tronquer(o.getOrDefault("societe", ""), 34),
                o.getOrDefault("code_postal", ""),
                o.getOrDefault("email_destinataire", ""));
        }

        // --- Sélection ---
        String reponse;
        if (autoMode) {
            reponse = "tout";
            out.println("\nMode auto : ajout de toutes les entreprises trouv\u00e9es.");
        } else {
            out.println("\nEntrez les num\u00e9ros \u00e0 ajouter dans entreprises.csv");
            out.println("(ex: 1,3,5  ou  tout  pour tout prendre) :");
            out.print("> ");
            reponse = scanner.nextLine().trim();
        }
        scanner.close();

        List<Map<String, String>> selectionnes = selectionner(reponse, offres);
        if (selectionnes.isEmpty()) { out.println("Aucune sélection. Abandon."); return; }

        // --- Ajout au CSV (sans doublons) ---
        Set<String> existants = chargerSocietesExistantes();
        int ajouts = 0, ignores = 0;

        boolean ecrireHeader = !new File(CSV_FILE).exists() || new File(CSV_FILE).length() == 0;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(CSV_FILE, true), StandardCharsets.UTF_8))) {
            if (ecrireHeader)
                pw.println("titre,societe,adresse_postale,code_postal,email_destinataire");
            for (Map<String, String> o : selectionnes) {
                String societe = o.getOrDefault("societe", "").trim();
                if (societe.isEmpty() || existants.contains(societe.toLowerCase())) {
                    ignores++; continue;
                }
                pw.printf("%s,%s,%s,%s,%s%n",
                    echapper(o.getOrDefault("titre", "")),
                    echapper(societe),
                    echapper(o.getOrDefault("adresse_postale", "")),
                    echapper(o.getOrDefault("code_postal", "")),
                    echapper(o.getOrDefault("email_destinataire", "")));
                ajouts++;
            }
        }

        out.println("\n--- Résumé ---");
        out.println("Ajoutées        : " + ajouts);
        if (ignores > 0) out.println("Ignorées (déjà dans le CSV) : " + ignores);
        if (ajouts > 0)
            out.println("\nRelancez LettreMailing pour générer les lettres correspondantes.");
    }

    // -------------------------------------------------------------------------
    // Géocodage via api-adresse.data.gouv.fr
    // -------------------------------------------------------------------------
    private static double[] geocoder(String ville, String codePostal) throws Exception {
        String q = ville + (codePostal.isEmpty() ? "" : " " + codePostal);
        String url = GEO_API + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                   + "&limit=1&type=municipality";

        String body = get(url, 10);
        JSONArray features = new JSONObject(body).optJSONArray("features");
        if (features == null || features.length() == 0) return null;

        JSONArray coords = features.getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONArray("coordinates");

        return new double[]{ coords.getDouble(1), coords.getDouble(0) }; // [lat, lon]
    }

    // -------------------------------------------------------------------------
    // Recherche La Bonne Alternance
    // -------------------------------------------------------------------------
    private static List<Map<String, String>> rechercherOffres(
            double lat, double lon, String rayon, String romes, PrintStream out) throws Exception {

        String url = LBA_API
            + "?romes=" + URLEncoder.encode(romes, StandardCharsets.UTF_8)
            + "&latitude=" + lat
            + "&longitude=" + lon
            + "&radius=" + rayon
            + "&caller=AlternionApp";

        String body;
        try {
            body = get(url, 20);
        } catch (Exception e) {
            System.err.println("Erreur réseau : " + e.getMessage());
            return Collections.emptyList();
        }

        return parserReponse(body, out);
    }

    private static List<Map<String, String>> parserReponse(String json, PrintStream out) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);

            // Deux sources de données dans la réponse LBA
            String[] sources = {"lbaCompanies", "matchaJobs", "peJobs"};
            JSONObject results = root.optJSONObject("results");
            if (results == null) {
                // Certaines versions renvoient directement un tableau
                JSONArray arr = root.optJSONArray("results");
                if (arr != null) extraireTableau(arr, result);
                return result;
            }

            for (String source : sources) {
                JSONObject src = results.optJSONObject(source);
                if (src == null) continue;
                JSONArray arr = src.optJSONArray("results");
                if (arr != null) extraireTableau(arr, result);
            }

        } catch (Exception e) {
            out.println("[AVERTISSEMENT] Erreur parsing : " + e.getMessage());
            out.println("L'API La Bonne Alternance a peut-être changé de format.");
        }
        return result;
    }

    private static void extraireTableau(JSONArray arr, List<Map<String, String>> result) {
        for (int i = 0; i < arr.length(); i++) {
            Map<String, String> offre = extraireOffre(arr.optJSONObject(i));
            if (offre != null) result.add(offre);
        }
    }

    private static Map<String, String> extraireOffre(JSONObject obj) {
        if (obj == null) return null;
        try {
            Map<String, String> o = new LinkedHashMap<>();

            // Nom société (plusieurs chemins possibles selon la version de l'API)
            String nom = optString(obj, "company.name", "entreprise.nom",
                                       "company.siret", "enseigne");
            if (nom.isEmpty()) return null;
            o.put("societe", nom);

            // Adresse
            String adresse = optString(obj, "place.fullAddress", "place.address",
                                           "lieuTravail.libelle", "adresse");
            String cp = optString(obj, "place.zipCode", "lieuTravail.codePostal",
                                       "codePostal");
            // Si l'adresse contient déjà le CP, ne pas le dupliquer
            if (!cp.isEmpty() && adresse.contains(cp)) cp = "";
            o.put("adresse_postale", adresse);
            o.put("code_postal", cp);

            // Email
            String email = optString(obj, "contact.email", "email");
            o.put("email_destinataire", email);
            o.put("titre", "");

            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lit une valeur dans un JSONObject en essayant plusieurs chemins (notation pointée).
     * Ex: optString(obj, "company.name", "enseigne") essaie obj.company.name puis obj.enseigne.
     */
    private static String optString(JSONObject obj, String... chemins) {
        for (String chemin : chemins) {
            try {
                String[] parts = chemin.split("\\.");
                JSONObject cur = obj;
                for (int i = 0; i < parts.length - 1; i++) {
                    cur = cur.getJSONObject(parts[i]);
                }
                String val = cur.optString(parts[parts.length - 1], "").trim();
                if (!val.isEmpty() && !val.equals("null")) return val;
            } catch (Exception ignored) {}
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------
    private static String get(String url, int timeoutSeconds) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400)
            throw new IOException("HTTP " + response.statusCode() + " pour " + url);
        return response.body();
    }

    private static List<Map<String, String>> selectionner(String reponse,
                                                           List<Map<String, String>> offres) {
        if (reponse.equalsIgnoreCase("tout") || reponse.equalsIgnoreCase("all"))
            return new ArrayList<>(offres);
        List<Map<String, String>> sel = new ArrayList<>();
        for (String part : reponse.split(",")) {
            try {
                int idx = Integer.parseInt(part.trim()) - 1;
                if (idx >= 0 && idx < offres.size()) sel.add(offres.get(idx));
            } catch (NumberFormatException ignored) {}
        }
        return sel;
    }

    private static Set<String> chargerSocietesExistantes() {
        Set<String> set = new HashSet<>();
        File f = new File(CSV_FILE);
        if (!f.exists()) return set;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length > 1)
                    set.add(parts[1].trim().toLowerCase().replace("\"", ""));
            }
        } catch (Exception ignored) {}
        return set;
    }

    private static Properties chargerConfig(PrintStream out) {
        Properties p = new Properties();
        File f = new File(CONFIG_FILE);
        if (!f.exists()) {
            out.println("[INFO] config.properties absent — paramètres saisis manuellement.");
            return p;
        }
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