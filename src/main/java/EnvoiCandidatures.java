import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Envoie automatiquement les emails de candidature (LM + CV en PJ) via Gmail SMTP.
 *
 * Prérequis :
 *   1. Activer la validation en 2 étapes sur ton compte Google
 *   2. Google Account → Sécurité → Mots de passe d'application → générer un mot de passe
 *   3. Renseigner config.properties (voir template config.properties.example)
 *
 * Exécution :
 *   mvn compile exec:java -Dexec.mainClass=EnvoiCandidatures
 */
public class EnvoiCandidatures {

    private static final String DOSSIER_BASE = "outputs";
    private static final String CSV_FILE     = "data/enValidation.csv";
    private static final String CONFIG_FILE  = "config/config.properties";
    private static final String NOM_LM      = "LM_Camille_Marsac.pdf";
    private static final String NOM_CV      = "CV_Camille_Marsac.pdf";

    // Délai entre chaque envoi pour éviter les filtres anti-spam
    private static final int DELAI_MS = 3000;

    public static void main(String[] args) throws Exception {

        PrintStream out = new PrintStream(System.out, true, "UTF-8");

        // --- Chargement de la configuration ---
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            System.err.println("Erreur : \"" + CONFIG_FILE + "\" introuvable.");
            System.err.println("Copiez config/config.properties.example → config/config.properties et remplissez-le.");
            System.exit(1);
        }
        Properties config = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            config.load(fis);
        }

        String gmailUser  = config.getProperty("gmail.user",  "").trim();
        String gmailPass  = config.getProperty("gmail.password", "").trim();
        String sujetTmpl  = config.getProperty("email.sujet", "Candidature alternance – [societe]").trim();
        String corpsTmpl  = config.getProperty("email.corps", "").trim();

        if (gmailUser.isEmpty() || gmailPass.isEmpty()) {
            System.err.println("Erreur : gmail.user et gmail.password doivent être renseignés dans config.properties");
            System.exit(1);
        }

        // --- Lecture du CSV ---
        File csv = new File(CSV_FILE);
        if (!csv.exists()) {
            System.err.println("Erreur : \"" + CSV_FILE + "\" introuvable.");
            System.exit(1);
        }
        List<Map<String, String>> lignes = lireCSV(csv);
        if (lignes.isEmpty()) {
            System.err.println("Le CSV est vide.");
            System.exit(1);
        }

        // --- Vérification des PDFs avant d'envoyer quoi que ce soit ---
        out.println("=== Vérification des fichiers ===");
        int manquants = 0;
        for (Map<String, String> ligne : lignes) {
            String societe    = ligne.get("societe");
            String email      = ligne.getOrDefault("email_destinataire", "").trim();
            String nomDossier = societe.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            File dossier      = new File(DOSSIER_BASE, nomDossier);
            File lm           = new File(dossier, NOM_LM);
            File cv           = new File(dossier, NOM_CV);

            if (email.isEmpty()) {
                System.err.println("  MANQUANT email : " + societe);
                manquants++;
            }
            if (!lm.exists()) {
                System.err.println("  MANQUANT LM    : " + lm.getAbsolutePath());
                manquants++;
            }
            if (!cv.exists()) {
                System.err.println("  MANQUANT CV    : " + cv.getAbsolutePath());
                manquants++;
            }
        }
        if (manquants > 0) {
            System.err.println("\n" + manquants + " problème(s) détecté(s). Corrigez le CSV ou relancez LettreMailing.");
            System.exit(1);
        }
        out.println("Tous les fichiers sont présents.\n");

        // --- Confirmation avant envoi ---
        out.println("=== Envoi en cours pour " + lignes.size() + " entreprise(s) ===");
        out.print("Confirmer l'envoi ? (oui/non) : ");
        Scanner scanner = new Scanner(System.in, "UTF-8");
        String rep = scanner.nextLine().trim().toLowerCase();
        scanner.close();
        if (!rep.equals("oui") && !rep.equals("o")) {
            out.println("Annulé.");
            System.exit(0);
        }
        out.println();

        // --- Configuration SMTP Gmail ---
        Properties smtpProps = new Properties();
        smtpProps.put("mail.smtp.auth",            "true");
        smtpProps.put("mail.smtp.starttls.enable", "true");
        smtpProps.put("mail.smtp.host",            "smtp.gmail.com");
        smtpProps.put("mail.smtp.port",            "587");
        smtpProps.put("mail.smtp.ssl.protocols",   "TLSv1.2");

        final String user = gmailUser;
        final String pass = gmailPass;

        Session session = Session.getInstance(smtpProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        // --- Envoi ---
        int ok = 0, erreurs = 0;

        for (Map<String, String> ligne : lignes) {
            String societe    = ligne.get("societe");
            String emailDest  = ligne.get("email_destinataire").trim();
            String nomDossier = societe.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            File dossier      = new File(DOSSIER_BASE, nomDossier);

            try {
                String sujet = personaliser(sujetTmpl, societe);
                String corps = personaliser(corpsTmpl, societe);

                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(gmailUser));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDest));
                message.setSubject(sujet);

                // Corps du message
                MimeBodyPart corpsPartie = new MimeBodyPart();
                // Remplace les \n littéraux du fichier config par de vrais sauts de ligne
                corpsPartie.setText(corps.replace("\\n", "\n"), "UTF-8");

                // Pièce jointe LM
                MimeBodyPart lmPartie = new MimeBodyPart();
                lmPartie.attachFile(new File(dossier, NOM_LM));

                // Pièce jointe CV
                MimeBodyPart cvPartie = new MimeBodyPart();
                cvPartie.attachFile(new File(dossier, NOM_CV));

                Multipart multipart = new MimeMultipart();
                multipart.addBodyPart(corpsPartie);
                multipart.addBodyPart(lmPartie);
                multipart.addBodyPart(cvPartie);

                message.setContent(multipart);
                Transport.send(message);
                TrackerCandidatures.enregistrerEnvoi(societe);

                out.println("  OK : " + societe + " → " + emailDest);
                ok++;

                if (ok < lignes.size()) Thread.sleep(DELAI_MS);

            } catch (Exception e) {
                System.err.println("  ERREUR : " + societe + " → " + e.getMessage());
                erreurs++;
            }
        }

        out.println("\n--- Résumé ---");
        out.println("Envoyés : " + ok);
        if (erreurs > 0) out.println("Erreurs  : " + erreurs);
    }

    // -------------------------------------------------------------------------
    // Remplace les placeholders dans le sujet/corps de l'email
    // -------------------------------------------------------------------------
    private static String personaliser(String tmpl, String societe) {
        return tmpl.replace("[societe]", societe);
    }

    // -------------------------------------------------------------------------
    // Lecture CSV (même logique que LettreMailing)
    // -------------------------------------------------------------------------
    private static List<Map<String, String>> lireCSV(File csv) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(csv), StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            if (headerLine == null) return result;
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

    private static String[] parseLigne(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuotes    = false;
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
}
