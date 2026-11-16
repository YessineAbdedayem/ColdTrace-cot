# Migration du Front Office vers WildFly/Undertow

Audit et vérifications : **16 novembre 2026**, branche `main`, référence initiale
`ffe3970`. L'arbre de travail était propre. Aucun commit, push ou changement de branche.

**État : migration implémentée et validée localement sur une stack isolée ;
validation globale partielle.** Le modèle MLOps n'a pas été entraîné et raccordé
en direct à cette stack, et le déploiement HTTPS/production n'a pas été exécuté.
Les tests unitaires MLOps et les règles d'alertes prédictives passent.
Aucune base ou volume existant n'a été réinitialisé.

## 1. Audit avant modification

| Élément | Architecture observée |
|---|---|
| Front Office | PWA HTML/CSS/JS sans framework ; fichiers dans `frontends/front-office` ; Nginx sur le port interne 80, publié sur 127.0.0.1:3000 par défaut |
| REST | Navigateur → même origine `/api/*` → Nginx frontend → `api:8080/api/*` |
| WebSocket | Navigateur → `/ws/alerts?access_token=...` → Nginx frontend → API ; reconnexion gérée dans `monitor.js` |
| IAM | Application séparée, Maven/WildFly ; `/auth/*` transmis par le Nginx frontend vers `iam:8080` ; jetons RS256, rôles admin/quality/operator |
| API | Maven, Jakarta EE 11, WildFly 42, JDK 21 ; `coldtrace-api.war` installé comme `ROOT.war`, contexte racine, Jakarta REST sous `/api` |
| IAM déployé | `coldtrace-iam.war` installé comme `ROOT.war` dans un autre conteneur ; aucune fusion avec l'API |
| Proxy extérieur | Nginx optionnel sur 127.0.0.1:80 ; domaines front/API/IAM/MLflow et route modèle `/ml/` ; HTTP uniquement, aucune terminaison TLS web configurée |
| IoT et MLOps | MQTT/MQTTS, Node-RED, MongoDB, simulateur ; MLOps sur réseau Compose partagé ; aucun changement nécessaire |
| Back Office | Seulement `.gitkeep` ; aucun frontend à activer |
| PWA | Scope et manifeste à `/` ; service worker v7 ; liste explicite des seuls assets publics ; aucune mise en cache des routes API/IAM/WebSocket |

Le WAR API occupe déjà le contexte racine. Un serveur WildFly frontend dédié
conserve cette séparation et évite de déplacer le WAR, de changer son hôte virtuel,
ou d'introduire une collision à `/`. Cela garde trois processus WildFly :
frontend, API et IAM ; ce choix demande plus de mémoire qu'un serveur consolidé.

Les principaux risques étaient le routage des préfixes, l'upgrade WebSocket,
les en-têtes Authorization, les MIME PWA, le changement d'origine, les expressions CLI
persistées sans résolution, les ressources déjà créées et les journaux contenant le jeton.

## 2. Flux avant et après

Avant :

```text
Navigateur → [proxy de domaines optionnel] → frontend Nginx:80
                                             ├─ fichiers PWA
                                             ├─ /api/* et /ws/alerts → API WildFly:8080
                                             └─ /auth/* → IAM WildFly:8080
```

Après :

```text
Navigateur → [proxy de domaines optionnel] → frontend WildFly/Undertow:8080
                                             ├─ file handler → /opt/jboss/frontend
                                             ├─ /api/* et /ws/* → API WildFly:8080
                                             └─ /auth/* → IAM WildFly:8080
```

L'origine publique, les URL du navigateur et les ports hôte restent identiques.
Le proxy extérieur cible désormais `front-office:8080`.
Les deux WAR existants et leurs contrôles de sécurité restent inchangés.
Les handlers de proxy rétablissent explicitement les préfixes `/api`, `/auth`, `/ws`
sur les destinations. Ils ne réessaient pas les requêtes après une erreur
(`max-retries=0`), pour éviter de rejouer un acquittement ou une commande.

## 3. Scripts du professeur

Les trois originaux lus depuis `D:/USER` sont conservés sans modification dans
`docs/wildfly-reference/`. Ce sont des références ; ils ne sont jamais exécutés
automatiquement.

Les fichiers default-host.properties, add-host.properties et add-api.properties
ne sont pas fournis dans le dossier des scripts. Les propriétés versionnées ont
donc été établies à partir des chemins, ports et déploiements réellement audités.

| Script | Analyse et adaptation |
|---|---|
| `default-host.cli` | Approche conservée : file handler Undertow et alias du default-host. Version adaptée dans `frontends/front-office/wildfly/`, gardes read-resource/add/write-attribute, propriétés résolues côté CLI |
| `add-host.cli` | Configuration optionnelle du futur Back Office préparée, jamais exécutée par l'image ; nécessite d'abord un véritable artefact public |
| `add-api.cli` | Non appliqué : les applications existent déjà dans leurs conteneurs, sous `ROOT.war`. Le nom versionné fictif et le default-web-module de l'exemple créeraient une architecture différente |

Les originaux supposent des filtres partagés et ne sont pas tous idempotents.
Leur HSTS preload, redirection fondée sur le port et CORS `*` avec credentials ne
sont pas repris. Les appels restent de même origine : aucun CORS n'est ajouté.
Il n'y a pas de repli SPA global ; un asset absent reste une erreur HTTP, et une
réponse API n'est jamais remplacée par index.html.

Le script de build démarre un serveur embarqué, applique les opérations, puis l'arrête.
**`--resolve-parameter-values` est indispensable** avec `--properties` :
sans lui, certaines expressions acceptées au build restent non résolues au démarrage.
Une application répétée a été exécutée avec succès sur WildFly 42, puis un reload
et de nouveaux tests HTTP ont réussi. Les opérations demandant reload ne le lancent
pas automatiquement : le déploiement en image redémarre naturellement le serveur.

Références techniques consultées :
[modèle de proxy Undertow](https://docs.wildfly.org/35/wildscribe/subsystem/undertow/configuration/handler/reverse-proxy/host/index.html)
et [file handler](https://docs.wildfly.org/38/feature-pack/doc/reference/subsystem/undertow/configuration/handler/file/index.html).
La compatibilité effective a été testée avec l'image WildFly **42.0.0.Final-jdk21**.

## 4. Propriétés et artefacts

| Propriété | Développement |
|---|---|
| `default.path` | `/opt/jboss/frontend`, chemin du conteneur |
| `default.aliases` | localhost, 127.0.0.1, front-office, coldtrace.test, www.coldtrace.test |
| `api.host`, `api.port` | api, 8080 |
| `iam.host`, `iam.port` | iam, 8080 |
| Back Office optionnel | `host.key=admin`, `host.path=/opt/jboss/back-office`, `host.aliases=admin.coldtrace.test` |

`default.domains`, `host.domains`, `api.domains` ne sont pas nécessaires :
ils servaient au CORS des originaux. Aucun `api.version` n'est inventé ;
le finalName Maven est `coldtrace-api`, la version Maven `0.1.0-SNAPSHOT`,
et le nom de déploiement effectif `ROOT.war`.

L'image copie uniquement index.html, monitor.css, monitor.js, pwa.js, sw.js,
manifest.webmanifest et icons dans la racine publique.
Les scripts CLI, propriétés, sources Java, tests, README et secrets sont hors de cette racine.
Le listing des répertoires et le suivi des liens symboliques sont désactivés.
L'image de développement peut recevoir des montages publics individuels en lecture seule.

Pour une image de production personnalisée :

```powershell
Copy-Item frontends/front-office/wildfly/production.properties.example frontends/front-office/wildfly/production.properties
# Adapter les domaines et les destinations privées, sans y placer de secret.
docker build -f frontends/front-office/Dockerfile.wildfly --build-arg FRONTEND_PROPERTIES=production.properties -t coldtrace-front-office-undertow:production frontends/front-office
```

Le fichier production.properties est ignoré par Git.
Installer cette image sur l'infrastructure cible nécessite un registre, une politique de
déploiement et un ingress HTTPS existants. Aucun certificat ni secret de production n'est inventé.

Une réapplication locale explicite, dans un conteneur de développement :

```powershell
docker compose -f gateway-iot/docker-compose.yml exec -T front-office /opt/jboss/wildfly/bin/jboss-cli.sh --connect --resolve-parameter-values --file=/opt/jboss/frontend-config/default-host.cli --properties=/opt/jboss/frontend-config/frontend.properties
docker compose -f gateway-iot/docker-compose.yml exec -T front-office /opt/jboss/wildfly/bin/jboss-cli.sh --connect --command=:reload
```

Employer les propriétés de l'environnement effectivement déployé, plutôt que celles
de développement, sur un serveur personnalisé. Pour un contrôleur distant déjà
configuré et privé, employer `--controller=remote+https://<hote>:9993` avec
authentification et confiance TLS appropriées ; ce port n'est pas ouvert par Compose.

## 5. Démarrage et ports

Voir [LANCEMENT-LOCAL.md](LANCEMENT-LOCAL.md). Avec les secrets et certificats
existants préparés, depuis la racine :

```powershell
docker compose -f gateway-iot/docker-compose.yml config --quiet
docker compose -f gateway-iot/docker-compose.yml up -d --build
# Seulement si les domaines locaux sont utilisés :
docker compose -f middleware/proxy/docker-compose.yml up -d --force-recreate
```

| Adresse | Usage |
|---|---|
| `http://localhost:3000/` | PWA, défaut FRONT_OFFICE_PORT ; port interne 8080 |
| `http://localhost:18080/api/health` | API sur le poste audité ; API_PORT vaut 18080, défaut 8080 |
| `http://localhost:8081/health` | IAM, défaut IAM_PORT |
| `http://localhost:8001/` | API modèle, si le MLOps est démarré |
| Domaines locaux existants | Proxy optionnel ; les routes API/IAM/MLflow/modèle ne changent pas |

Les variables IAM de bootstrap sont absentes du .env local audité. Elles n'ont pas été
ajoutées. Des comptes déjà créés en base peuvent néanmoins exister ; leurs mots de
passe ne sont pas modifiés par le bootstrap. Le démarrage de la stack personnelle
et son authentification n'ont pas été substitués aux tests isolés.

## 6. PWA, IAM et sécurité

- Origine, chemins, manifeste, portée `/`, cache v7, interface et logique JS inchangés.
  Une migration de serveur à origine identique n'exige pas de changer le cache.
- Les réponses ont Cache-Control: no-store ; sw.js a un MIME JavaScript et
  Service-Worker-Allowed: / ; le manifeste est application/manifest+json.
- Le service worker conserve sa liste explicite d'assets. Il n'intercepte jamais
  API, auth, WebSocket ou commandes ; aucune commande n'est mise en attente.
- Authorization, méthode, corps et query string sont conservés jusqu'aux WAR.
  Les rôles et la signature des acquittements restent vérifiés dans l'API.
- Le jeton WebSocket reste dans la query pour compatibilité. Aucun access-log
  Undertow n'est activé ; si un journal est ajouté sur cet hôte, le CLI exclut /ws.
  Chaque virtual host du proxy extérieur exclut /ws/alerts de son propre journal,
  pour remplacer aussi les directives héritées du nginx.conf de base.
- Aucun port de management WildFly n'est publié. Aucun nouveau service interne
  n'est exposé sur le réseau public. Les ports de développement restent liés à 127.0.0.1.
- La résolution DNS Java est bornée pour permettre la reconnexion aux conteneurs
  API/IAM remplacés ; la PWA conserve ses reconnexions et son timeout.
- localhost HTTP est adapté aux tests PWA. HTTP sur coldtrace.test ou une IP LAN
  ne bénéficie pas de cette exception. La production exige HTTPS de confiance ;
  l'ingress doit transmettre les upgrades et protéger ses journaux.

Si l'origine reste localhost:3000, recharger en ligne suffit. Si elle change :
terminer les formulaires, se déconnecter, ouvrir la nouvelle URL HTTPS, s'identifier
et réinstaller la PWA. Les caches et installations ne traversent pas les origines.
Ne désinscrire l'ancien worker et ne supprimer que ses caches coldtrace-shell-* que
si cette ancienne installation est abandonnée ; ne pas vider tout le stockage navigateur.

## 7. CI et résultats exécutés

La nouvelle workflow GitHub Actions `frontend-hosting.yml` complète `mlops.yml` :
tests Node, certificats jetables, builds Java avec tests, image frontend,
stack intégrée isolée, HTTP/IAM/WebSocket, réapplication CLI et reload, navigateur,
proxy et absence de jeton dans ses logs. Elle produit les deux WAR, l'archive statique,
la configuration et les résultats. Elle ne publie ni ne déploie automatiquement en production.
L'exécution sur GitHub n'a pas été déclenchée : aucun push n'est autorisé.

La stack `tests/hosting.compose.yml` utilise les vrais services. MongoDB, les clés IAM,
les contextes Node-RED et les données brokers sont éphémères, sur un réseau séparé.
Elle ne charge aucun .env utilisateur. Les mots de passe indiqués dans cette fixture
ne sont destinés qu'à ces comptes jetables. Les certificats existants sont lus sans modification ;
la CI génère ses propres certificats dans RUNNER_TEMP.

| Vérification | Résultat réel |
|---|---|
| Compose normal, développement, rollback frontend/proxy et stack isolée | Valides |
| Build frontend et démarrage WildFly 42 | Réussis, sans services en erreur |
| CLI répété, reload, HTTP après reload | Réussis |
| Assets publics, comparaison des octets, MIME et cache | Réussis |
| Chemins privés et assets absents | 404 ; répertoire icons sans listing (403) |
| API Java | **47 tests réussis**, dont 11 sur les alertes prédictives |
| IAM Java | **16 tests réussis** |
| Simulateur Node | **7 tests réussis** |
| Service worker Node | **7 tests réussis** |
| Générateur Python | **7 tests réussis** |
| Pipeline Python, dérive et réentraînement | **34 tests réussis**, avertissements de dépréciation de dépendances |
| Serving Python | **11 tests réussis**, un avertissement de dépréciation |
| HTTP réel IAM/API | Login, 401, /auth/me, dix capteurs, trois sites, mesures MQTT, incidents, gateways : réussis |
| Autorisations et mutations isolées | Refus quality, contrôle operator, événement WebSocket, acquittement attribué, fermeture d'incident : réussis |
| Navigateur Chrome installé | **22 vérifications réussies**, cinq tailles d'écran, installation, offline/recovery, API indisponible, reconnexion WS, logout, mises à jour protégées ; zéro erreur page/worker |
| Proxy extérieur | nginx -t, domaine, MIME, login, 401, requête authentifiée, upgrade WS 101 : réussis ; aucune query access_token dans les logs |
| Image Nginx de rollback | Build, fichier statique, login, REST protégé et upgrade WS : réussis |
| Diff et PowerShell | git diff --check et analyse syntaxique start.ps1 réussis |

Les premières tentatives ont révélé les expressions CLI non résolues et des tests
navigateur obsolètes. Ils ont été corrigés puis réexécutés.
Le Chromium Playwright attendu était absent ; le navigateur Chrome installé a permis
l'exécution complète. La fixture de mise à jour est volontairement limitée à pwa.js/sw.js,
sans démarrer monitor.js contre un faux IAM. Le véritable login est testé séparément
contre la stack réelle.

Pour reproduire :

```powershell
docker compose -f frontends/front-office/tests/hosting.compose.yml --profile proxy up -d --build
$env:FRONT_OFFICE_URL = 'http://127.0.0.1:13002'
$env:PWA_TEST_USER = 'operateur'
$env:PWA_TEST_PASSWORD = 'Integration-only-123!'
$env:HOSTING_MUTATIONS = '1'
node frontends/front-office/tests/hosting-smoke.cjs
node frontends/front-office/tests/proxy-smoke.cjs
$env:PWA_TEST_ACTIONS = '1'
# Si Chromium Playwright n'est pas installé :
$env:BROWSER_EXECUTABLE = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
node frontends/front-office/tests/pwa-browser.cjs
docker compose -f frontends/front-office/tests/hosting.compose.yml --profile proxy down
```

Ces identifiants ne conviennent qu'à cette stack de test.
Ne jamais activer HOSTING_MUTATIONS ou PWA_TEST_ACTIONS sur des données partagées.
Résultats et captures navigateur : `frontends/front-office/test-results/`, ignorés par Git.

## 8. Limites et travail restant

- Aucun entraînement MLflow ni scénario early_warning de bout en bout avec un modèle
  chargé n'a été exécuté pendant cette migration. Les 52 tests Python et les 11 tests
  Java prédictifs réussissent ; les fichiers MLOps et leurs routes ne sont pas modifiés.
- Pas de déploiement de production ni de test HTTPS sur un domaine réel.
  Certificats web, registre d'images, secrets CI/CD et infrastructure restent à fournir.
- L'expiration des JWT est couverte par les tests Java ; le timer d'expiration de
  session du navigateur n'a pas été testé en attendant huit heures.
- Le Back Office reste désactivé ; son CLI doit être validé avec sa future application.
- La révocation immédiate des JWT déjà émis reste une limite préexistante de l'IAM,
  hors périmètre de cette migration.
- La démonstration Thaw trop rapide et les limites des données synthétiques restent
  celles documentées dans MLOPS.md.
- La CI est préparée, mais son exécution distante n'est pas revendiquée.
- La migration n'a pas démarré la stack personnelle ni modifié son .env.

## 9. Retour arrière sans perte de données

Le Dockerfile et nginx.conf historiques du frontend restent présents.
Les overrides de rollback restaurent le port interne 80 et la destination du proxy.
Les fichiers initiaux restent aussi récupérables dans Git à `ffe3970`, sans
nécessiter de réinitialisation de branche ni d'écrasement de fichiers de collègues.

Depuis la racine :

```powershell
docker compose -f gateway-iot/docker-compose.yml -f gateway-iot/docker-compose.frontend-nginx.yml config --quiet
docker compose -f gateway-iot/docker-compose.yml -f gateway-iot/docker-compose.frontend-nginx.yml up -d --build --no-deps front-office
# Si le proxy extérieur est utilisé :
docker compose -f middleware/proxy/docker-compose.yml -f middleware/proxy/docker-compose.frontend-nginx.yml up -d --force-recreate
```

Vérifier localhost:3000, le login et les alertes.
Les volumes MongoDB, brokers et clés IAM ne sont pas concernés.
Conserver ces overrides pour les démarrages suivants tant que le rollback est actif ;
le script .\start emploie la configuration Undertow par défaut.

Pour revenir à Undertow :

```powershell
docker compose -f gateway-iot/docker-compose.yml up -d --build --no-deps front-office
docker compose -f middleware/proxy/docker-compose.yml up -d --force-recreate
```

Ne pas employer down -v, prune ou une suppression de base.

## 10. Fichiers et état Git

Branche : **main**. **9 fichiers modifiés, 21 créés, aucun supprimé**.
Tous les changements restent non committés. Les fichiers publics de la PWA, les sources
Java, les schémas et la configuration MLOps n'ont pas été modifiés.

| État | Fichier | Rôle |
|---|---|---|
| Modifié | README.md | Architecture et liens de migration |
| Modifié | frontends/front-office/README.md | Hébergement, routes, développement et tests |
| Modifié | frontends/front-office/tests/pwa-browser.cjs | Login réel, requêtes authentifiées, nouveau formulaire, logout et fixture PWA |
| Modifié | gateway-iot/README.md | Serveur frontend actuel |
| Modifié | gateway-iot/docker-compose.yml | Image WildFly et port interne 8080 |
| Modifié | middleware/api/README.md | Description du routage actuel |
| Modifié | middleware/proxy/README.md | Destination Undertow et contraintes HTTPS |
| Modifié | middleware/proxy/nginx.conf | Port Undertow et exclusion des logs WebSocket |
| Modifié | start.ps1 | Libellé du serveur frontend dans le récapitulatif |
| Créé | .github/workflows/frontend-hosting.yml | CI GitHub Actions et artefacts |
| Créé | docs/LANCEMENT-LOCAL.md | Commandes Windows et préconditions |
| Créé | docs/WILDFLY-FRONTEND-MIGRATION.md | Audit, choix, tests, limites et rollback |
| Créé | docs/wildfly-reference/default-host.cli | Original du professeur, inchangé |
| Créé | docs/wildfly-reference/add-host.cli | Original du professeur, inchangé |
| Créé | docs/wildfly-reference/add-api.cli | Original du professeur, inchangé |
| Créé | frontends/front-office/Dockerfile.wildfly | Image statique dédiée, CLI embarqué et DNS |
| Créé | frontends/front-office/tests/hosting.compose.yml | Stack réelle éphémère séparée |
| Créé | frontends/front-office/tests/hosting-seed.js | Données de test uniquement dans MongoDB éphémère |
| Créé | frontends/front-office/tests/hosting-smoke.cjs | HTTP, sécurité, mesures et incidents réels |
| Créé | frontends/front-office/tests/proxy-smoke.cjs | Routage et upgrade du proxy extérieur |
| Créé | frontends/front-office/wildfly/.gitignore | Propriétés privées de déploiement non versionnées |
| Créé | frontends/front-office/wildfly/default-host.cli | Configuration idempotente du frontend |
| Créé | frontends/front-office/wildfly/frontend.properties | Valeurs Docker de développement |
| Créé | frontends/front-office/wildfly/production.properties.example | Exemple sans secrets pour la production |
| Créé | frontends/front-office/wildfly/add-host.cli | Hôte futur Back Office, désactivé par défaut |
| Créé | frontends/front-office/wildfly/back-office.properties.example | Exemple pour le futur Back Office |
| Créé | gateway-iot/docker-compose.frontend-dev.yml | Assets individuels montés read-only |
| Créé | gateway-iot/docker-compose.frontend-nginx.yml | Retour à l'image Nginx historique |
| Créé | middleware/proxy/docker-compose.frontend-nginx.yml | Configuration proxy du rollback |
| Créé | middleware/proxy/nginx.frontend-nginx.conf | Destination frontend:80, avec protection des logs |
