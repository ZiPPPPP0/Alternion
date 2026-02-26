import org.apache.poi.xwpf.usermodel.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LettreMailing {

    private static final String PH_TITRE    = "[titre]";
    private static final String PH_SOCIETE  = "[societe]";
    private static final String PH_ADRESSE  = "[adresse_postale]";
    private static final String PH_CP_VILLE = "[code_postal]";

    private static final String DOSSIER_BASE = "C:\\Users\\camil\\Desktop\\Alternance_2026";
    private static final String NOM_FICHIER  = "LM_Camille_Marsac";
    private static final String CSV_FILE     = "data/entreprises.csv";
    private static final String MODELE_FILE  = "templates/lettre_modele.docx";
    private static final String CV_FILE      = "templates/CV_Camille_Marsac.pdf";

    public static void main(String[] args) throws Exception {

        PrintStream out = new PrintStream(System.out, true, "UTF-8");

        // --- Vérification du modèle ---
        File modele = new File(MODELE_FILE);
        if (!modele.exists()) {
            System.err.println("Erreur : \"" + MODELE_FILE + "\" introuvable dans : "
                    + modele.getAbsolutePath());
            System.exit(1);
        }

        // --- Lecture du CSV ---
        File csv = new File(CSV_FILE);
        if (!csv.exists()) {
            System.err.println("Erreur : \"" + CSV_FILE + "\" introuvable dans : "
                    + csv.getAbsolutePath());
            System.err.println("Format attendu (première ligne = en-têtes) :");
            System.err.println("  titre,societe,adresse_postale,code_postal,email_destinataire");
            System.exit(1);
        }

        List<Map<String, String>> lignes = lireCSV(csv);
        if (lignes.isEmpty()) {
            System.err.println("Le fichier CSV est vide ou ne contient que l'en-tête.");
            System.exit(1);
        }

        out.println("=== Génération des lettres ===");
        out.println(lignes.size() + " entrée(s) trouvée(s) dans le CSV.\n");

        // --- Détection de LibreOffice ---
        String soffice = detecterLibreOffice();
        if (soffice == null) {
            out.println("[AVERTISSEMENT] LibreOffice non trouvé → les fichiers seront générés en .docx uniquement.");
            out.println("  Installez LibreOffice pour activer la conversion PDF.\n");
        }

        int ok = 0, erreurs = 0;

        for (Map<String, String> ligne : lignes) {
            String societe = ligne.get("societe");
            try {
                genererLettre(modele, ligne, soffice, out);
                TrackerCandidatures.enregistrerGeneration(
                    societe, ligne.getOrDefault("email_destinataire", ""));
                out.println("  OK : " + societe);
                ok++;
            } catch (Exception e) {
                System.err.println("  ERREUR pour \"" + societe + "\" : " + e.getMessage());
                erreurs++;
            }
        }

        out.println("\n--- Résumé ---");
        out.println("Succès  : " + ok);
        if (erreurs > 0) out.println("Erreurs : " + erreurs);
        out.println("Dossier : " + DOSSIER_BASE);
    }

    // -------------------------------------------------------------------------
    // Génère une lettre (DOCX + PDF si LibreOffice disponible) pour une ligne
    // -------------------------------------------------------------------------
    private static void genererLettre(File modele, Map<String, String> data,
                                      String soffice, PrintStream out) throws Exception {

        Map<String, String> remplacements = new LinkedHashMap<>();
        remplacements.put(PH_TITRE,    data.getOrDefault("titre", ""));
        remplacements.put(PH_SOCIETE,  data.getOrDefault("societe", ""));
        remplacements.put(PH_ADRESSE,  data.getOrDefault("adresse_postale", ""));
        remplacements.put(PH_CP_VILLE, data.getOrDefault("code_postal", ""));

        String nomDossier = data.get("societe").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        File dossierSortie = new File(DOSSIER_BASE, nomDossier);
        dossierSortie.mkdirs();

        File docx = new File(dossierSortie, NOM_FICHIER + ".docx");

        try (FileInputStream fis = new FileInputStream(modele);
             XWPFDocument doc    = new XWPFDocument(fis)) {

            remplacerDansDocument(doc, remplacements);

            try (FileOutputStream fos = new FileOutputStream(docx)) {
                doc.write(fos);
            }
        }

        if (soffice != null) {
            convertirEnPdf(soffice, docx, dossierSortie, out);
            docx.delete();
        }

        // --- Copie du CV ---
        File cvSource = new File(CV_FILE);
        if (cvSource.exists()) {
            File cvDest = new File(dossierSortie, cvSource.getName());
            try (InputStream in   = new FileInputStream(cvSource);
                 OutputStream dst = new FileOutputStream(cvDest)) {
                in.transferTo(dst);
            }
        } else {
            System.err.println("  [AVERTISSEMENT] CV introuvable : " + cvSource.getAbsolutePath());
        }
    }

    // -------------------------------------------------------------------------
    // Lecture du CSV (séparateur virgule, guillemets optionnels, UTF-8)
    // -------------------------------------------------------------------------
    private static List<Map<String, String>> lireCSV(File csv) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(csv), StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            if (headerLine == null) return result;
            // Supprime BOM UTF-8 éventuel (Excel en ajoute parfois)
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1);

            String[] headers = parseLigne(headerLine);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] valeurs = parseLigne(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i].trim(), i < valeurs.length ? valeurs[i].trim() : "");
                }
                result.add(row);
            }
        }
        return result;
    }

    // Gère les champs entre guillemets et les virgules dans les valeurs
    private static String[] parseLigne(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuotes    = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++; // guillemet doublé = guillemet littéral
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

    // -------------------------------------------------------------------------
    // Conversion PDF via LibreOffice headless
    // -------------------------------------------------------------------------
    private static String detecterLibreOffice() {
        String[] candidats = {
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe"
        };
        for (String chemin : candidats) {
            if (new File(chemin).exists()) return chemin;
        }
        return null;
    }

    private static void convertirEnPdf(String soffice, File docx,
                                       File dossierSortie, PrintStream out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            soffice, "--headless", "--convert-to", "pdf",
            "--outdir", dossierSortie.getAbsolutePath(),
            docx.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Vide le flux pour éviter le blocage
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        int code = process.waitFor();
        if (code != 0) throw new RuntimeException("LibreOffice a retourné le code " + code);
    }

    // -------------------------------------------------------------------------
    // Remplacement dans toutes les zones du document
    // -------------------------------------------------------------------------
    private static void remplacerDansDocument(XWPFDocument doc, Map<String, String> map) {
        for (XWPFParagraph para : doc.getParagraphs()) remplacerDansParagraphe(para, map);
        for (XWPFTable table : doc.getTables())         remplacerDansTable(table, map);
        for (XWPFHeader h : doc.getHeaderList()) {
            for (XWPFParagraph p : h.getParagraphs()) remplacerDansParagraphe(p, map);
            for (XWPFTable t   : h.getTables())       remplacerDansTable(t, map);
        }
        for (XWPFFooter f : doc.getFooterList()) {
            for (XWPFParagraph p : f.getParagraphs()) remplacerDansParagraphe(p, map);
            for (XWPFTable t   : f.getTables())       remplacerDansTable(t, map);
        }
    }

    private static void remplacerDansTable(XWPFTable table, Map<String, String> map) {
        for (XWPFTableRow row : table.getRows())
            for (XWPFTableCell cell : row.getTableCells())
                for (XWPFParagraph para : cell.getParagraphs())
                    remplacerDansParagraphe(para, map);
    }

    private static void remplacerDansParagraphe(XWPFParagraph para, Map<String, String> map) {
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : runs) { String t = r.getText(0); if (t != null) sb.append(t); }
        String texte = sb.toString();

        boolean contient = false;
        for (String cle : map.keySet()) { if (texte.contains(cle)) { contient = true; break; } }
        if (!contient) return;

        for (Map.Entry<String, String> e : map.entrySet())
            texte = texte.replace(e.getKey(), e.getValue());

        boolean premier = false;
        for (XWPFRun run : runs) {
            if (run.getEmbeddedPictures() != null && !run.getEmbeddedPictures().isEmpty()) continue;
            if (!premier) { run.setText(texte, 0); premier = true; }
            else if (run.getText(0) != null) run.setText("", 0);
        }
    }
}
