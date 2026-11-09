# ColdTrace : Supervision de la chaîne du froid

Projet **Cloud of Things** - École Supérieure des Communications de Tunis (Sup'Com)
Année universitaire 2026-2027

**Réalisé par :** Khalil Hedhili, Oussema Kard, Yessine Abdedayem
**Encadré par :** Dr. Ing. Mohamed-Bécha Kaâniche

## Présentation

ColdTrace est une solution Cloud of Things de supervision de la chaîne du froid
(entrepôts, chambres froides, transport frigorifique). Des capteurs connectés
mesurent la température, l'humidité et l'ouverture des portes. Une passerelle IoT
transmet les mesures au cloud, qui les stocke, les analyse et déclenche des alertes
en temps réel.

Deux applications PWA sont prévues :
- **front-office** : tableau de bord temps réel, historique, acquittement des alertes ;
- **back-office** : gestion des capteurs, des sites, des seuils et des utilisateurs.

## Technologies

| Couche | Technologies |
|---|---|
| Back-end / middleware | Jakarta EE 11 (WildFly), Jakarta REST, WebSocket, Security, MongoDB |
| IoT | Mosquitto (MQTTS), Node-RED, ESP32 (Arduino / C++), Raspberry Pi 4 |
| Front-end | PWA (HTML5, CSS3, JavaScript), Chart.js |
| Outils | GitHub, Postman, Cloud IaaS (Ubuntu) |
| Méthodologie | eXtreme Programming |

## Structure du dépôt

```
.
├── README.md
├── livrable1-cahier-des-charges/   # Cahier des charges (PDF + source LaTeX)
├── livrable2-cahier-conceptuel/    # À venir
├── api-rest/                       # Jakarta EE 11 : ingestion MQTT, alertes, WebSocket
├── pwa-front-office/               # À venir
├── pwa-back-office/                # À venir
├── gateway-iot/                    # Prototype fonctionnel : Node-RED, brokers MQTT, simulateur
└── docs/                           # À venir : documentation technique, manuel, vidéos
```

## Avancement des livrables

| Livrable | État |
|---|---|
| Cahier des charges | Remis (v1.0, 24/10/2026) |
| Cahier conceptuel | Prévu 25/10 - 07/11/2026 |
| Passerelle IoT et protocoles | Prévu 08/11 - 21/11/2026 |
| API REST et middleware | Prévu 22/11 - 12/12/2026 |
| PWA, intégration et tests | Prévu 13/12/2026 - 31/01/2027 |

## Tester le système pas à pas

Le simulateur remplace les capteurs ESP32 et Node-RED remplace le Raspberry Pi : aucun matériel n'est nécessaire.

```
simulateur ──> site-broker ──> node-red (passerelle) ──MQTTS──> cloud-broker ──> api ──> mongo
(10 capteurs, 3 sites)                                                            └──> WebSocket /ws/alerts
```

- **Où lancer :** les commandes se lancent depuis `gateway-iot/`.
- **Shell :** elles sont écrites pour Git Bash. Sous PowerShell, l'API de pannes du simulateur s'appelle avec
  `Invoke-RestMethod`, comme au test 5.
- **Plus de détails :** `gateway-iot/README.md` décrit la passerelle et `api-rest/README.md` décrit l'API.

### 0. Démarrer et lancer les vérifications automatiques

La première fois seulement :

```bash
cp .env.example .env          # puis remplacer chaque mot de passe "change-me"
./scripts/gen-certs.sh        # CA de développement + certificat du cloud-broker
```

Si le port 8080 est déjà pris sur la machine (par exemple par Oracle), mettre `API_PORT=18080` dans `.env` et utiliser
ce port dans les tests ci-dessous.

```bash
docker compose up -d --build  # 6 services + dev-seed (enregistre les capteurs puis s'arrête)
docker compose ps             # site-broker, cloud-broker, node-red, simulator, mongo, api : tous "Up"
./scripts/smoke-test.sh       # tests automatiques de bout en bout : chaque ligne doit afficher PASS
```

`./scripts/run-all.sh` fait tout en une commande : la première préparation, le démarrage et le smoke test.

**Attendu :** `All smoke checks passed.` Les tests manuels ci-dessous montrent la même chose étape par étape, par
exemple pour une démonstration.

### Le plus simple : la page de supervision

Ouvrir **http://localhost:8080/monitor/** (ou le port de `API_PORT`). Elle remplace la plupart des commandes de cette
section :

- **Capteurs :** chaque capteur est affiché avec sa température, son humidité, sa porte, l'âge de sa dernière mesure
  et une courbe des 2 dernières minutes. L'affichage se rafraîchit toutes les 5 s.
- **Pannes :** les boutons **Door / Heat / Mute / Reset** de chaque capteur déclenchent les pannes des tests 5 à 8, et
  « Clear all faults » les annule toutes.
- **Incidents :** le journal montre les incidents ouverts et, en direct par WebSocket, chaque ouverture et fermeture
  (test 9).
- **En-tête :** il indique l'état de l'API, de la liaison d'alertes et de chaque passerelle.

Les commandes ci-dessous restent utiles pour vérifier en détail, et pour ce que la page ne peut pas faire, comme la
coupure du cloud (test 10).

### 1. L'API fonctionne

```bash
curl http://localhost:8080/api/health        # {"status":"ok"}
docker compose logs api | grep -E "MongoDB|MQTT|Subscribed"
```

**Attendu :**
- `MongoDB client for hosts [mongo:27017], database 'coldtrace', user 'coldtrace-app'`
- `Connected to MQTT broker ssl://cloud-broker:8883`
- `Subscribed to coldtrace/+/+/telemetry, coldtrace/+/gateway/status (QoS 1)`

Pour suivre le journal en direct pendant les tests suivants : `docker compose logs -f api`.

### 2. Voir la base de données

Au choix :

- **MongoDB Compass** (application de bureau). Se connecter avec
  `mongodb://<MONGO_APP_USER>:<MONGO_APP_PASSWORD>@localhost:27017/coldtrace?authSource=coldtrace`, avec les valeurs
  de `gateway-iot/.env`. On met bien `localhost` ici, car Compass tourne sur la machine et non dans Docker. La base
  est le MongoDB local dans Docker, pas MongoDB Atlas.
- **mongosh** dans le conteneur :
  ```bash
  docker compose exec mongo mongosh -u <MONGO_APP_USER> -p <MONGO_APP_PASSWORD> --authenticationDatabase coldtrace coldtrace
  ```

### 3. Les mesures des 10 capteurs arrivent

Dans Compass, ouvrir `coldtrace` puis `measurements` et cliquer plusieurs fois sur **Refresh**. Dans mongosh :

```js
db.measurements.countDocuments()                      // augmente d'environ 10 toutes les 5 s
db.measurements.distinct("sensorId")                  // [ "S01", ..., "S10" ]
db.measurements.find({ siteId: "site02" }).sort({ ts: -1 }).limit(3)   // chambre de congélation : environ -20 °C
```

**Attendu :**
- chaque document contient `msgId`, `siteId`, `sensorId`, `ts`, `temperature`, `humidity`, `doorOpen` et
  `receivedAt` ;
- `ts` et `receivedAt` sont des dates, pas du texte.

### 4. État de la passerelle

```js
db.gateways.find()
```

**Attendu :** un document par passerelle de site (`_id: "site01"`), avec `status: "online"` et les compteurs
(`forwarded`, `buffered`, `replayed`, `rejected`, `dropped`).

### 5. Porte restée ouverte → incident `door`, puis `temperature`

```bash
curl -X POST http://localhost:4000/faults -H 'Content-Type: application/json' -d '{"sensorId":"S01","fault":"door_open"}'
```

PowerShell :

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:4000/faults' -ContentType 'application/json' `
  -Body '{"sensorId":"S01","fault":"door_open"}'
```

**Attendu** (Compass : collection `incidents`, filtre `{ status: "open" }`) :
- en moins de 5 s, un incident `metric: "door"` pour S01 (`value: 1`) ;
- environ 45 s plus tard, quand la chambre froide dépasse 4 °C, un second incident `metric: "temperature"` pour S01,
  dont la `peakValue` continue de monter ;
- le journal de l'API affiche `Incident opened: S01 door=1.0`, puis `Incident opened: S01 temperature=...`.

### 6. Le congélateur se réchauffe → incident `temperature`

```bash
curl -X POST http://localhost:4000/faults -H 'Content-Type: application/json' -d '{"sensorId":"S05","fault":"temp_rise"}'
```

**Attendu :** après environ 70 s, un incident `metric: "temperature"` pour S05, avec `thresholdMax: -18`.

### 7. Un capteur n'envoie plus rien → incident `silence`

```bash
curl -X POST http://localhost:4000/faults -H 'Content-Type: application/json' -d '{"sensorId":"S08","fault":"silent"}'
```

**Attendu :**
- après 60 à 75 s, un incident `metric: "silence"` pour S08, dont la `value` est le nombre de secondes sans mesure ;
- `peakValue` augmente toutes les 15 s tant que le capteur reste muet ;
- dans `measurements`, la dernière mesure de S08 ne change plus.

### 8. Retour à la normale → les incidents se ferment

```bash
curl -X POST http://localhost:4000/faults/clear
```

**Attendu :**
- les incidents `door` et `temperature` passent à `status: "closed"` avec une date `closedAt`, après 3 mesures
  normales d'affilée (environ 15 s une fois la valeur revenue dans les limites) ;
- l'incident `silence` se ferme dès que S08 envoie de nouveau ;
- `db.incidents.countDocuments({ status: "open" })` finit à `0`.

### 9. Alertes en temps réel par WebSocket

```bash
docker compose exec simulator node alert-e2e.js
```

Le script se connecte à `ws://api:8080/ws/alerts`. Il réchauffe S02 et attend l'alerte, puis il annule la panne et
attend la fermeture.

**Attendu :** `Alert for S02 at 4.x C, ... ms after the reading`, puis `Incident closed at ...`.

Le script commence par annuler **toutes** les pannes : ne pas le lancer pendant les tests 5 à 7.

### 10. Coupure du cloud : aucune mesure perdue

```bash
docker compose stop cloud-broker     # attendre environ 20 s
docker compose start cloud-broker
```

**Attendu :**
- pendant la coupure, Node-RED (http://localhost:1880) affiche `cloud offline | buffered: N`, et N augmente ;
- le journal de l'API affiche `MQTT connection lost` ;
- après le redémarrage, le journal affiche `Reconnected` et `Subscribed`, et Node-RED revient à
  `cloud online | buffered: 0` ;
- `db.measurements.countDocuments({ replayed: true })` a augmenté : les mesures gardées sont arrivées en retard, sans
  perte.

Le redémarrage de la passerelle (`docker compose restart node-red`) se comporte de la même façon : son tampon survit au
redémarrage.

### 11. Doublons et messages invalides

Seuls les comptes des passerelles peuvent publier des mesures, donc le test se fait dans le conteneur du cloud-broker,
avec l'identifiant de la passerelle. Lancer la première commande **deux fois** :

```bash
docker compose exec cloud-broker sh -c 'mosquitto_pub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt \
  -u $GW_USER -P $GW_PASS -q 1 -t coldtrace/site01/S01/telemetry \
  -m "{\"v\":1,\"msgId\":\"test-1\",\"ts\":\"2026-11-02T20:15:00Z\",\"temperature\":2.4}"'

docker compose exec cloud-broker sh -c 'mosquitto_pub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt \
  -u $GW_USER -P $GW_PASS -q 1 -t coldtrace/site01/S01/telemetry -m not-json'
```

**Attendu :**
- `db.measurements.countDocuments({ msgId: "test-1" })` vaut `1` : le second envoi est ignoré ;
- le message invalide apparaît comme `WARNING ... Rejected message ... invalid JSON` dans le journal de l'API ;
- les mesures continuent d'arriver : l'API ne s'est pas arrêtée.

Pour vérifier qu'il n'y a aucun doublon dans toute la base (l'index unique sur `msgId` le garantit), cette requête
doit renvoyer `0` :

```js
db.measurements.aggregate([{ $group: { _id: "$msgId", n: { $sum: 1 } } }, { $match: { n: { $gt: 1 } } }]).toArray().length
```

### 12. Le flux de la passerelle

Ouvrir http://localhost:1880 et suivre le flux de gauche à droite :

```
Sensors (site broker) → MQTT adapter → Check and clean → Forward or buffer → Cloud broker (MQTTS)
```

On peut double-cliquer sur un nœud pour voir son code, puis fermer avec **Cancel**. Ne pas cliquer sur **Deploy** :
cela enregistrerait les modifications dans `gateway-iot/node-red/flows.json`.

### En cas de problème

| Symptôme | Cause probable |
|---|---|
| `curl` sur 8080 renvoie la page d'un autre serveur | Le port 8080 est pris : mettre `API_PORT=18080` dans `.env`, puis `docker compose up -d` |
| Journal de l'API : `MQTT connect ... failed` | Le broker n'est pas encore démarré (l'API réessaie toutes les 5 s), ou `CLOUD_BACKEND_PASSWORD` est faux |
| Journal de l'API : `Connection refused` vers `localhost:27017` | `MONGO_URI` contient `localhost` : dans un conteneur, il faut `mongo` |
| Compass : `Authentication failed` | Il manque `?authSource=coldtrace`, ou l'utilisateur ou le mot de passe est faux |
| Mesures enregistrées mais aucun incident | Le capteur n'est pas dans `sensors` (l'API l'indique une fois par un WARNING `Unknown sensor`) |
| Seuls les incidents `door` et `silence` apparaissent, jamais `temperature` ni `humidity` | Les capteurs n'ont pas de `profile` (WARNING `No thresholds for profile 'null'`). Voir ci-dessous |
| L'API se déconnecte et se reconnecte sans arrêt | Deux API tournent avec le même identifiant MQTT : arrêter celle en trop |

#### Les capteurs n'ont pas de `profile`

**Pourquoi :**
- Le moteur d'alertes trouve les limites de température et d'humidité grâce au `profile` du capteur (`cold` ou
  `freezer`, dans la collection `thresholds`). Sans profil, il ne vérifie que la porte et le silence, qui n'ont pas
  besoin de limites.
- Cela arrive quand les capteurs ont été créés avant le service `dev-seed`, par exemple par un ancien script. En
  effet, `dev-seed` n'ajoute que les capteurs absents et ne modifie jamais ceux qui existent déjà.

**Vérifier** dans mongosh (voir le test 2) :

```js
db.sensors.countDocuments({ profile: { $exists: false } })   // doit valoir 0
```

**Corriger** sans perdre de données. Cela donne aux capteurs du simulateur le profil de `simulator/sensors.json`,
c'est-à-dire S05 à S07 en congélateur et les autres en chambre froide :

```js
db.sensors.updateMany({ _id: { $in: ["S05", "S06", "S07"] }, profile: { $exists: false } }, { $set: { profile: "freezer" } })
db.sensors.updateMany({ profile: { $exists: false } }, { $set: { profile: "cold" } })
```

Pas besoin de redémarrer : l'API relit le capteur à chaque mesure. Une base neuve (`docker compose down -v`, qui efface
aussi les mesures) évite le problème, car `dev-seed` crée alors les capteurs avec leur profil.

## Déploiement

Le déploiement sur le Cloud IaaS se fait uniquement depuis ce dépôt
(`git clone` puis `git pull`).
