# Alternion

Automatisation des candidatures alternance — génération de lettres de motivation personnalisées, envoi Gmail, scraper d'offres et dashboard de suivi.

## Prérequis

- Java 21+
- Maven 3.8+
- LibreOffice (pour la conversion PDF)
- Un compte Gmail avec [mot de passe d'application](https://myaccount.google.com/apppasswords) (validation 2 étapes requise)

## Installation

```bash
git clone <url-du-repo>
cd Alternion
cp config/config.properties.example config/config.properties
# Remplir config/config.properties avec vos identifiants Gmail
mvn compile
```

## Configuration

Copier `config/config.properties.example` en `config/config.properties` et renseigner :

| Clé | Description |
|-----|-------------|
| `gmail.user` | Adresse Gmail |
| `gmail.password` | Mot de passe d'application (16 caractères) |
| `email.sujet` | Sujet de l'email (supporte `[societe]`) |
| `email.corps` | Corps de l'email (supporte `[societe]`) |
| `scraper.ville` | Ville de recherche (ex : `Lyon`) |
| `scraper.codepostal` | Code postal (ex : `69001`) |
| `scraper.rayon` | Rayon en km (défaut : `30`) |
| `scraper.romes` | Codes ROME séparés par virgule (ex : `M1805,M1810`) |

## Fichiers requis

| Fichier | Description |
|---------|-------------|
| `templates/lettre_modele.docx` | Modèle Word avec placeholders |
| `templates/CV_Camille_Marsac.pdf` | CV copié dans chaque dossier de candidature |
| `data/entreprises.csv` | Liste des entreprises cibles |

### Format `data/entreprises.csv`

```
titre,societe,adresse_postale,code_postal,email_destinataire
Monsieur,Acme Corp,12 rue de la Paix,75001,contact@acme.fr
```

### Placeholders dans `templates/lettre_modele.docx`

`[societe]` `[adresse_postale]` `[code_postal]`

## Utilisation

### Dashboard (recommandé)

```powershell
mvn exec:java "-Dexec.mainClass=DashboardServer"
```

Ouvre automatiquement `http://localhost:8080` — interface pour lancer toutes les actions et suivre les candidatures.

### Ligne de commande

```powershell
# Générer les LM + copier le CV dans chaque dossier
mvn exec:java

# Envoyer les emails (demande confirmation)
mvn exec:java "-Dexec.mainClass=EnvoiCandidatures"

# Scraper des offres depuis La Bonne Alternance
mvn exec:java "-Dexec.mainClass=ScraperOffres"

# Gérer le tracker en mode interactif
mvn exec:java "-Dexec.mainClass=TrackerCandidatures"
```

## Structure du projet

```
Alternion/
├── pom.xml                          ← Maven (ne pas déplacer)
├── README.md
├── templates/
│   ├── lettre_modele.docx           ← modèle Word
│   └── CV_Camille_Marsac.pdf        ← CV source
├── config/
│   ├── config.properties.example    ← template de configuration
│   └── config.properties            ← vos identifiants (non commité)
├── data/
│   ├── entreprises.csv              ← liste des entreprises cibles
│   └── tracker.csv                  ← suivi des candidatures (auto-généré)
└── src/main/
    ├── java/
    │   ├── LettreMailing.java        ← génération LM PDF
    │   ├── EnvoiCandidatures.java    ← envoi Gmail
    │   ├── TrackerCandidatures.java  ← suivi des candidatures
    │   ├── ScraperOffres.java        ← scraper La Bonne Alternance
    │   └── DashboardServer.java      ← serveur web local (port 8080)
    └── resources/
        └── dashboard.html            ← interface du dashboard
```

## Sortie

Les lettres et CVs sont générés dans :
```
C:\Users\camil\Desktop\Alternance_2026\<NomSociété>\
    LM_Camille_Marsac.pdf
    CV_Camille_Marsac.pdf
```
