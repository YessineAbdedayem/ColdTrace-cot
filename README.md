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
├── api-rest/                       # À venir : Jakarta EE 11
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

## Déploiement

Le déploiement sur le Cloud IaaS se fait uniquement depuis ce dépôt
(`git clone` puis `git pull`).
