# Lancer ColdTrace sur ce PC

Ouvrir Docker Desktop et attendre que son moteur soit prêt. Dans PowerShell :

```powershell
cd 'D:\Nouveau dossier\desktop\CoT\ColdTrace-cot\gateway-iot'
docker compose up -d --build
docker compose ps
```

- **Front Office : http://localhost:3000/**
- **Santé du backend : http://localhost:18080/api/health** sur ce PC (`API_PORT=18080` dans `.env`).
- **Node-RED : http://localhost:1880**

Le Front Office est maintenant servi par Nginx depuis `frontends/front-office/`.
L'ancien `/monitor/` de WildFly n'est plus utilisé. Les requêtes REST et WebSocket
passent par le serveur frontend vers le backend, sans configuration CORS supplémentaire.

Le port frontend est configurable avec `FRONT_OFFICE_PORT` dans `gateway-iot/.env`
(3000 par défaut). L'API garde son propre `API_PORT`. Conserver les fichiers `.env`
et certificats existants : il n'est pas nécessaire de les recréer.

Pour les lancements suivants, sans changement de code :

```powershell
docker compose up -d
```

Après une modification de l'interface uniquement, avec le backend déjà démarré :

```powershell
docker compose up -d --build --no-deps front-office
```

Pour voir les logs ou arrêter les services en conservant les données :

```powershell
docker compose logs -f front-office api
# Ctrl+C quitte seulement les logs.
docker compose stop
```

Les boutons Door, Heat, Mute et Reset permettent de tester les incidents. Ils agissent
sur la simulation réelle et créent des incidents dans la base locale.
Le [README du Front Office](../frontends/front-office/README.md) détaille les scénarios.
