# 📜 Déclaration d'Attribution et d'Intégrité Académique
### Module : Cloud of Things (COT) · Année 2026/2027 · SUP'COM

Ce document est un *livrable obligatoire* devant être complété et mis à jour à chaque sprint par l'équipe. Il vise à cartographier de manière transparente l'origine du code, des architectures et l'usage des outils d'assistance (IA, bibliothèques tiers, codes d'archive).

---

## 👥 1. Identification de l'Équipe
*   *Projet (Nom) :* ColdTrace : supervision de la chaîne du froid
*   *Équipe (Numéro) :* ___
*   *Membres :*
    *   Membre 1 (Prénom, Nom) : Khalil Hedhili
    *   Membre 2 (Prénom, Nom) : Oussema Kard
    *   Membre 3 (Prénom, Nom) : Yessine Abdedayem

---

## 🧠 2. Usage des Assistants de Génération (IA)
Cochez l'option qui correspond à votre réalité et détaillez le périmètre.

*   [ ] *Option A :* Aucun outil de génération de code par IA n'a été utilisé.
*   [ ] *Option B :* Utilisation modérée (Aide au débogage, génération de fonctions isolées, documentation).
*   [x] *Option C :* Utilisation intensive (Génération de structures de dossiers, squelettes d'API complets, scripts de déploiement).

**Périmètre :**

- **Oussema Kard, avec Claude Code :** squelette de l'API, endpoints REST, page de supervision, proxy, pipeline MLOps, scripts Docker et documentation.
- **Khalil Hedhili, avec Claude Code :** alertes précoces dans l'API et le moniteur, surveillance du modèle, détection de dérive, réentraînement automatique, serveur IAM et connexion, script de démarrage `start.ps1`.
- **Le rôle des membres :** dans les deux cas, le membre a défini les besoins, fait les choix de conception, lancé et vérifié chaque étape (tests, conteneurs, démonstrations) et relu le code.
- **Yessine Abdedayem, avec GitHub Copilot :** assistance sur le simulateur, la stack de développement et l'acquittement des incidents.

### 📝 Journal des invites (Prompts) notables et composants générés par IA :

| Composant / Module | Outil utilisé (ChatGPT/Claude/Copilot...) | Description du prompt ou du rôle confié à l'IA |
| :--- | :--- | :--- |
| Squelette de l'API (`middleware/api`) | Claude Code (Claude Opus 5.5) | Projet Maven WAR Jakarta EE 11 / Java 21 sur WildFly, Dockerfile multi-étapes, connexion MongoDB par variables d'environnement, écouteur MQTT/MQTTS qui enregistre les mesures, sans framework supplémentaire et sans secret dans le code |
| Contrat de messages et incidents (`middleware/api`) | Claude Code (Claude Opus 5.5) | Alignement de l'API sur le contrat de l'équipe (MQTTS, identifiants lus dans le topic) ; ajout des incidents « porte ouverte » et « capteur silencieux » au moteur d'alertes |
| Endpoints REST (`middleware/api`) | Claude Code (Claude Opus 5.5) | `GET /api/sensors`, `/api/incidents`, `/api/gateways` et proxy vers l'API de pannes du simulateur, pour tester le système sans terminal |
| Page de supervision (première version du front-office) | Claude Code (Claude Opus 5.5) | Interface de test au design « conteneur frigorifique » : tuiles capteurs, courbes, boutons de pannes, journal des incidents en direct |
| Proxy inverse (`middleware/proxy`) | Claude Code (Claude Opus 5.5) | Proxy WildFly/Undertow (hôtes virtuels et handlers reverse-proxy) qui sert `coldtrace.test`, `api.coldtrace.test`, `iam.coldtrace.test` et `mlflow.coldtrace.test` via le fichier hosts |
| Générateur de données (`mlops/generator`) | Claude Code (Claude Opus 5.5) | 30 jours de mesures réalistes avec pannes injectées et journalisées, faute d'incidents réels en nombre suffisant |
| Pipeline d'entraînement (`mlops/pipeline`) | Claude Code (Claude Opus 5.5) | Préparation des variables, modèle LightGBM d'alerte précoce, évaluation contre les règles actuelles, suivi MLflow et règle de promotion, pipeline en une commande |
| API du modèle (`mlops/serving`) | Claude Code (Claude Opus 5.5) | Service FastAPI qui charge le modèle `@production` depuis MLflow et prédit sur les mesures en direct |
| CI (`.github/workflows/mlops.yml`) | Claude Code (Claude Opus 5.5) | Tests, pipeline complet sur une base vierge, vérification de l'API du modèle et construction des images Docker à chaque push |
| Documentation (`docs/MLOPS.md`, README, `RECAP.md`) | Claude Code (Claude Opus 5.5) | Rédaction des guides de test, du récapitulatif, de la documentation MLOps et de son schéma |
| Alertes précoces (`middleware/api`, front-office) | Claude Code (Claude Opus 5.5) | Incidents `early_warning` ouverts à partir des prévisions du modèle, et leur affichage dans le moniteur |
| Surveillance, dérive et réentraînement (`mlops/`) | Claude Code (Claude Opus 5.5) | Journal des prédictions comparées à la réalité, détection de dérive des données, réentraînement automatique avec les données en direct |
| Serveur IAM et connexion (`middleware/iam`, API, WebSocket, front-office) | Claude Code (Claude Opus 5.5) | Serveur de connexion avec Jakarta Security et jetons, connexion obligatoire sur l'API, le WebSocket et le moniteur |
| Démarrage en une commande (`start.ps1`) | Claude Code (Claude Opus 5.5) | Script qui démarre toute la plateforme |
| Simulateur multi-sites et stack de développement (`gateway-iot`) | GitHub Copilot | Assistance pendant l'écriture du simulateur contrôlable et de la stack MongoDB locale |
| Acquittement des incidents (`middleware/api`, front-office) | GitHub Copilot | Assistance pendant l'écriture de l'endpoint d'acquittement et de son workflow dans l'interface |

---

## 📚 3. Emprunts, Squelettes de Cours & Plagiat
Précisez si vous avez utilisé une base de code préexistante.

*   *Code de départ enseignant / squelette du cours :*
    *   [ ] Oui (indiquer quels modules : ______________________)
    *   [ ] Non
*   *Réutilisation d'anciens projets (Années précédentes / GitHub externes) :*
    *   [ ] Oui (Lien du dépôt d'origine : https://github.com...)
    *   [ ] Non
    *   Si Oui, listez les fichiers ou concepts importés directement : ______________________

Le projet s'appuie sur des logiciels open source utilisés tels quels, sans modification : Jakarta EE, WildFly, MongoDB, Eclipse Mosquitto, Node-RED, Eclipse Paho, LightGBM, MLflow, FastAPI, pandas. Il utilise aussi les polices Big Shoulders et Martian Mono (SIL Open Font License).

---

## 🛠️ 4. Répartition Réelle de l'Écriture du Code
Indiquez la provenance réelle (en %) des lignes de code actuellement sur votre branche principale.

| Module / Composant | % Écrit à la main par l'équipe | % Généré par IA | % Emprunté / Réutilisé |
| :--- | :---: | :---: | :---: |
| *Passerelle IoT / Node-RED / Edge* | ______ % | ______ % | ______ % |
| *Backend / API REST / Middleware* | ______ % | ______ % | ______ % |
| *Sécurité (IAM / Keycloak / mTLS)* | ______ % | ______ % | ______ % |
| *Frontend / PWA* | ______ % | ______ % | ______ % |
| *Scripts d'Infra / Déploiement* | ______ % | ______ % | ______ % |
| *MLOps (pipeline, MLflow, API du modèle, CI, surveillance)* | 0 % | 100 % | 0 % |

---

## ✍️ 5. Engagement sur l'Honneur
En validant ce fichier sur notre dépôt GitHub, nous certifions sur l'honneur que les déclarations ci-dessus sont exactes, sincères et reflètent fidèlement notre processus de développement. Nous sommes conscients que la falsification de ce document ou la non-maîtrise technique du code déclaré comme "écrit par l'équipe" lors des soutenances entraînera une *pénalité immédiate et sévère sur la note globale*.

*   [ ] *Lu et approuvé par l'ensemble des membres de l'équipe.*
*   *Date de dernière mise à jour :* 16 / 11 / 2026
