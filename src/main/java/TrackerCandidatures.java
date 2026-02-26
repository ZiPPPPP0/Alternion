import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Tracker des candidatures.
 *
 * - Alimenté automatiquement par LettreMailing (statut "généré")
 *   et EnvoiCandidatures (statut "envoyé")
 * - Permet de mettre à jour les statuts manuellement via le terminal
 *
 * Statuts possibles :
 *   généré → envoyé → relancé → entretien → accepté / refusé
 *
 * Exécution :
 *   mvn compile exec:java "-Dexec.mainClass=TrackerCandidatures"
 */
public class TrackerCandidatures {

    static final String TRACKER_FILE = "data/tracker.csv";

    private static final String[] HEADERS = {
        "societe", "email_destinataire", "date_generation", "date_envoi", "statut", "notes"
    };

    private static final List<String> STATUTS = List.of(
        "généré", "envoyé", "relancé", "entretien", "accepté", "refusé"
    );

    // =========================================================================
    // Méthodes statiques appelées par LettreMailing et EnvoiCandidatures
    // =========================================================================

    /** Appelé par LettreMailing après génération d'une LM. */
    public static void enregistrerGeneration(String societe, String email) {
        List<Map<String, String>> lignes = charger();
        Map<String, String> existant = trouver(lignes, societe);
        if (existant == null) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("societe",           societe);
            row.put("email_destinataire", email);
            row.put("date_generation",   LocalDate.now().toString());
            row.put("date_envoi",        "");
            row.put("statut",            "généré");
            row.put("notes",             "");
            lignes.add(row);
        } else {
            existant.put("date_generation", LocalDate.now().toString());
            existant.put("statut", "généré");
        }
        sauvegarder(lignes);
    }

    /** Appelé par EnvoiCandidatures après envoi d'un email. */
    public static void enregistrerEnvoi(String societe) {
        List<Map<String, String>> lignes = charger();
        Map<String, String> existant = trouver(lignes, societe);
        if (existant != null) {
            existant.put("date_envoi", LocalDate.now().toString());
            existant.put("statut",     "envoyé");
        }
        sauvegarder(lignes);
    }

    // =========================================================================
    // Interface interactive
    // =========================================================================

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        Scanner scanner = new Scanner(System.in, "UTF-8");

        while (true) {
            List<Map<String, String>> lignes = charger();
            afficherTableau(lignes, out);

            out.println("\n[1] Changer statut  [2] Ajouter note  [3] Quitter");
            out.print("Choix : ");
            String choix = scanner.nextLine().trim();

            if (choix.equals("1")) {
                changerStatut(lignes, scanner, out);
            } else if (choix.equals("2")) {
                ajouterNote(lignes, scanner, out);
            } else if (choix.equals("3")) {
                out.println("Au revoir."); scanner.close(); return;
            } else {
                out.println("Choix invalide.");
            }
        }
    }

    private static void changerStatut(List<Map<String, String>> lignes,
                                      Scanner scanner, PrintStream out) {
        if (lignes.isEmpty()) { out.println("Aucune candidature."); return; }
        out.print("Numéro de la société : ");
        int idx = lireInt(scanner) - 1;
        if (idx < 0 || idx >= lignes.size()) { out.println("Numéro invalide."); return; }

        out.println("Statuts : " + STATUTS);
        out.print("Nouveau statut : ");
        String statut = scanner.nextLine().trim();
        if (!STATUTS.contains(statut)) { out.println("Statut inconnu."); return; }

        lignes.get(idx).put("statut", statut);
        if (statut.equals("envoyé") && lignes.get(idx).get("date_envoi").isEmpty()) {
            lignes.get(idx).put("date_envoi", LocalDate.now().toString());
        }
        sauvegarder(lignes);
        out.println("Mis à jour.");
    }

    private static void ajouterNote(List<Map<String, String>> lignes,
                                    Scanner scanner, PrintStream out) {
        if (lignes.isEmpty()) { out.println("Aucune candidature."); return; }
        out.print("Numéro de la société : ");
        int idx = lireInt(scanner) - 1;
        if (idx < 0 || idx >= lignes.size()) { out.println("Numéro invalide."); return; }

        out.print("Note : ");
        String note = scanner.nextLine().trim();
        lignes.get(idx).put("notes", note);
        sauvegarder(lignes);
        out.println("Note enregistrée.");
    }

    private static void afficherTableau(List<Map<String, String>> lignes, PrintStream out) {
        out.println("\n=== Tracker candidatures ===");
        if (lignes.isEmpty()) {
            out.println("  (aucune candidature enregistrée)");
            return;
        }
        out.printf("%-4s %-25s %-12s %-12s %-12s %-12s%n",
            "#", "Société", "Généré", "Envoyé", "Statut", "Notes");
        out.println("-".repeat(82));
        int i = 1;
        for (Map<String, String> row : lignes) {
            String notes = row.getOrDefault("notes", "");
            if (notes.length() > 12) notes = notes.substring(0, 10) + "…";
            out.printf("%-4d %-25s %-12s %-12s %-12s %-12s%n",
                i++,
                tronquer(row.getOrDefault("societe",           ""), 24),
                row.getOrDefault("date_generation", ""),
                row.getOrDefault("date_envoi",       "-"),
                row.getOrDefault("statut",           ""),
                notes);
        }
    }

    // =========================================================================
    // Lecture / écriture CSV
    // =========================================================================

    static List<Map<String, String>> charger() {
        File f = new File(TRACKER_FILE);
        List<Map<String, String>> result = new ArrayList<>();
        if (!f.exists()) return result;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            if (headerLine == null) return result;
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1);
            String[] headers = parseLigne(headerLine);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] vals = parseLigne(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++)
                    row.put(headers[i].trim(), i < vals.length ? vals[i].trim() : "");
                result.add(row);
            }
        } catch (Exception e) {
            System.err.println("Erreur lecture tracker : " + e.getMessage());
        }
        return result;
    }

    static void sauvegarder(List<Map<String, String>> lignes) {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(TRACKER_FILE), StandardCharsets.UTF_8))) {
            pw.println(String.join(",", HEADERS));
            for (Map<String, String> row : lignes) {
                List<String> vals = new ArrayList<>();
                for (String h : HEADERS)
                    vals.add(echapper(row.getOrDefault(h, "")));
                pw.println(String.join(",", vals));
            }
        } catch (Exception e) {
            System.err.println("Erreur sauvegarde tracker : " + e.getMessage());
        }
    }

    private static Map<String, String> trouver(List<Map<String, String>> lignes, String societe) {
        return lignes.stream()
            .filter(r -> societe.equalsIgnoreCase(r.get("societe")))
            .findFirst().orElse(null);
    }

    private static String echapper(String val) {
        if (val.contains(",") || val.contains("\"") || val.contains("\n"))
            return "\"" + val.replace("\"", "\"\"") + "\"";
        return val;
    }

    private static String[] parseLigne(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuotes    = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;
                } else { inQuotes = !inQuotes; }
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString()); sb.setLength(0);
            } else { sb.append(c); }
        }
        tokens.add(sb.toString());
        return tokens.toArray(new String[0]);
    }

    private static String tronquer(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static int lireInt(Scanner scanner) {
        try { return Integer.parseInt(scanner.nextLine().trim()); }
        catch (NumberFormatException e) { return -1; }
    }
}