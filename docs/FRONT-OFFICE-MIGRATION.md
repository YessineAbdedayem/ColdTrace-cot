# Migration du moniteur vers le Front Office

Migration réalisée le 15 novembre 2026, sans commit ni push.

## Résultat

Le tableau de bord existant est maintenant servi indépendamment de WildFly, à
**http://localhost:3000/**. Ses fichiers HTML, CSS et JavaScript sont identiques
octet pour octet à ceux du moniteur initial. Le backend conserve son port :
**http://localhost:18080/** sur ce PC, configurable par `API_PORT`.

```text
ColdTrace-cot/
├── README.md
├── docs/
│   ├── FRONT-OFFICE-MIGRATION.md
│   ├── LANCEMENT-LOCAL.md
│   └── ... documents existants conservés
├── frontends/
│   ├── front-office/
│   │   ├── Dockerfile
│   │   ├── nginx.conf
│   │   ├── README.md
│   │   ├── index.html
│   │   ├── monitor.css
│   │   └── monitor.js
│   └── back-office/.gitkeep
├── middleware/
│   ├── api/
│   │   ├── pom.xml, Dockerfile, README.md, RECAP.md
│   │   └── src/
│   │       ├── main/java/              # aucune modification Java
│   │       ├── main/webapp/
│   │       │   ├── index.html          # page d'information API
│   │       │   └── WEB-INF/beans.xml
│   │       └── test/java/              # tests existants inchangés
│   ├── core/.gitkeep
│   └── iam/.gitkeep
└── gateway-iot/
    ├── docker-compose.yml
    ├── .env.example
    ├── README.md
    └── ... passerelle, brokers, simulateur et MongoDB conservés
```

## Audit et choix

L'état Git initial était propre sur `main`, commit `344a18d`. Les trois fichiers
du moniteur ont été inspectés, ainsi que leurs appels réseau, les routes backend,
Compose, les tests et les instructions de lancement.

Les appels REST utilisent `/api/sensors`, `/api/incidents`, `/api/gateways`,
`/api/dev/faults`, `/api/dev/faults/clear` et `/api/incidents/{id}/ack`.
Le WebSocket utilise le protocole et l'hôte de la page avec `/ws/alerts`.
Le chargement de CSS/JavaScript est relatif au document HTML. Aucune fonctionnalité
ne dépend donc du préfixe `/monitor/` lui-même, mais le serveur doit transmettre
les chemins `/api/` et `/ws/alerts` au backend.

Nginx fournit ce serveur et ce relais sur une seule origine navigateur : aucun
hostname Docker n'est placé dans le JavaScript, aucune règle CORS du backend n'est
assouplie et aucune dépendance JavaScript d'exécution n'est ajoutée. Les en-têtes
d'upgrade WebSocket sont configurés explicitement. Le DNS Docker est relu pour
permettre la reprise après remplacement du conteneur API.

L'ancien `http://localhost:<API_PORT>/monitor/` ne sert plus le moniteur. Il faut
utiliser la nouvelle URL frontend. Aucune redirection avec un port frontend fixe
n'est ajoutée au WAR ; les deux ports restent configurables indépendamment.

## Fichiers déplacés

Déplacements réalisés avec `git mv` :

| Ancien chemin | Nouveau chemin |
|---|---|
| `middleware/api/src/main/webapp/monitor/index.html` | `frontends/front-office/index.html` |
| `middleware/api/src/main/webapp/monitor/monitor.css` | `frontends/front-office/monitor.css` |
| `middleware/api/src/main/webapp/monitor/monitor.js` | `frontends/front-office/monitor.js` |

Le `.gitkeep` du Front Office a été retiré, car ce dossier contient désormais les fichiers réels.

## Fichiers créés

- `frontends/front-office/Dockerfile` : image Nginx avec uniquement les trois fichiers du moniteur copiés dans la racine web et la configuration proxy.
- `frontends/front-office/nginx.conf` : fichiers statiques, relais REST et WebSocket vers `api:8080`, résolution DNS Docker renouvelée.
- `frontends/front-office/README.md` : démarrage, configuration, architecture réseau et vérifications visuelles.
- `docs/LANCEMENT-LOCAL.md` : instructions PowerShell pour ce PC et nouvelle URL.
- `docs/FRONT-OFFICE-MIGRATION.md` : présent rapport.

## Fichiers modifiés

- `gateway-iot/docker-compose.yml` : ajout du seul service `front-office`, port local `FRONT_OFFICE_PORT`/3000, contexte de build frontend et commentaire de présentation. Les sept définitions de services préexistantes, tâche `dev-seed` incluse, gardent exactement la même configuration résolue.
- `gateway-iot/.env.example` : documentation de la variable facultative `FRONT_OFFICE_PORT=3000` ; aucun changement au `.env` local.
- `gateway-iot/scripts/smoke-test.sh` : vérifie aussi la présence du frontend, sa page et son relais de santé API ; les anciens scénarios restent présents.
- `middleware/api/src/main/webapp/index.html` : remplace la redirection vers le moniteur par une page d'information API avec lien vers le health check.
- `README.md` : arborescence, nombre de services et nouvelle URL.
- `middleware/api/README.md` : décrit le frontend indépendant et la suppression de l'ancien chemin statique.
- `gateway-iot/README.md` : décrit le nouveau service et pointe vers son README.

## Démarrage et communication

Ouvrir Docker Desktop, puis depuis la racine du dépôt :

```powershell
cd gateway-iot
docker compose up -d --build
```

Ouvrir **http://localhost:3000/**. Les lancements suivants peuvent utiliser
`docker compose up -d` si aucune source n'a changé.

`FRONT_OFFICE_PORT` dans `.env` configure le port exposé par Nginx. Le navigateur
appelle son propre serveur : Nginx transmet `/api/*` et `/ws/alerts` à `api:8080`
dans Docker, indépendamment du port API exposé sur Windows. Le proxy conserve les
méthodes, corps JSON et paramètres des requêtes. Les boutons de panne continuent
de passer par `DevFaultResource`, qui relaie vers le simulateur si activé.

## Validations réalisées

| Contrôle | Résultat |
|---|---|
| `docker compose config --quiet` | Réussi |
| Build Docker API et Front Office | Réussi ; Maven `package` exécute les tests dans l'image Java 21 |
| `nginx -t` dans le conteneur | Réussi |
| Services | Sept services permanents en cours d'exécution ; tâche ponctuelle `dev-seed` conservée |
| Source du moniteur | Trois fichiers identiques aux empreintes initiales |
| Backend Java | 24 fichiers Java identiques aux empreintes initiales, tests inclus |
| POM, Dockerfile backend, passerelle et configuration existante | Conservés ; aucune nouvelle dépendance Maven |
| WAR déployé | Inspection de l'archive : aucun fichier `monitor/` embarqué |
| Services préexistants et volumes | Configuration Compose résolue identique avant/après, en dehors de l'ajout du nouveau service |
| `.env` local et certificats | Empreintes identiques avant/après |
| Tests Node.js | 7 réussis, 0 échec |
| Tests Maven locaux | 25 réussis, 0 échec, 0 erreur, 0 ignoré, sous le JDK 25 de VS Code ; cible Java 21 conservée |
| Syntaxe JavaScript | `node --check frontends/front-office/monitor.js` réussi |
| Syntaxe du smoke test | `bash -n` réussi |
| Différences Git | `git diff --check` et contrôle de l'index réussis |

Test navigateur automatisé dans **Chrome headless**, avec Playwright installé
uniquement dans un répertoire temporaire Windows, sans dépendance ajoutée au dépôt :

- trois sites et dix cartes affichés ; indicateurs API et WebSocket connectés ;
- température et humidité réelles correctement rendues ; mesures renouvelées ;
- historique existant chargé par le relais REST ;
- Door sur S02 : porte affichée ouverte et incident visible dans le journal ;
- formulaire d'acquittement : opérateur et commentaire persistés, incident toujours ouvert ;
- Heat sur S03 : dépassement et incident de température ;
- Mute sur S08 : incident de silence après le délai existant ;
- Reset : retour des capteurs testés à la normale et fermeture des incidents ;
- événements WebSocket réels d'ouverture, d'acquittement et de fermeture reçus à travers Nginx ;
- reconnexion WebSocket après redémarrage du seul proxy frontend ;
- historique et capteurs toujours disponibles après rechargement ;
- aucune erreur JavaScript non interceptée dans le navigateur.

Les scénarios ont créé de vrais incidents portant le commentaire de validation
de migration. Ces enregistrements restent dans la base. Les pannes initiales des
capteurs testés ont été rétablies ; la panne de démonstration S01 déjà active avant
le test a été conservée. Aucune mesure ou incident existant n'a été supprimé.

## Limites et vérifications restantes

- Le smoke test complet n'a pas été exécuté sur la base existante : il contient des suppressions ciblées d'incidents et réinitialise globalement les pannes. Les scénarios de migration ont été vérifiés séparément dans Chrome, sans ces suppressions.
- Les nouvelles vérifications shell de la page et du relais REST ont été exécutées séparément avec succès ; les anciens scénarios destructifs du script n'ont pas été lancés.
- La redirection de l'ancien `/monitor/` n'est pas conservée. Mettre à jour les favoris avec la nouvelle URL.
- Le test de reconnexion interrompt le proxy frontend, sans arrêt de MQTT ou MongoDB. Aucun test de coupure cloud supplémentaire n'a été effectué pour cette migration.
- L'affichage a été contrôlé dans Chrome à 1440 px ; aucun nouveau test spécifique mobile ou autre navigateur n'est affirmé.
- Le Back Office reste vide ; aucune authentification, PWA ou fonctionnalité métier supplémentaire n'est implémentée.
- Aucun commit ni push n'a été effectué.
