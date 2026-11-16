# Lancement local — ColdTrace sur WildFly

Depuis la racine du dépôt, avec Docker Desktop démarré. Les commandes ci-dessous conservent les volumes MongoDB et les
clés IAM existants.

**Un seul WildFly** ([middleware/wildfly](../middleware/wildfly/README.md)) sert tout ColdTrace, avec un hôte virtuel
par nom, configuré par les scripts du cours (`default-host.cli`, `add-host.cli`, `add-api.cli`) et un fichier
`.properties` par environnement :

| Adresse | Hôte virtuel | Contenu |
|---|---|---|
| https://www.coldtrace.test (et https://coldtrace.test) | `default-host` | front-office (PWA) |
| https://admin.coldtrace.test | `admin-host` | back-office |
| https://api.coldtrace.test | `api-host` | `api-1.0.war` : API REST et WebSocket |
| https://iam.coldtrace.test | `iam-host` | `iam-1.0.war` : serveur de connexion |
| https://api.coldtrace.test/ml/, https://mlflow.coldtrace.test | `api-host`, `mlflow-host` | partie MLOps (proxy inverse vers `mlops/`) |

## Le plus simple

```powershell
.\start
```

Le script fait tout, et ne refait pas ce qui existe déjà :
- les mots de passe manquants dans `gateway-iot/.env`, dont `WEB_TLS_PASSWORD` ;
- les certificats (autorité de développement, broker MQTT, certificat HTTPS `*.coldtrace.test`) ;
- la confiance de Windows dans l'autorité de développement (une fenêtre de confirmation la première fois) ;
- la vérification des noms dans le fichier hosts ;
- la construction et le démarrage, et la partie MLOps.

## Préconditions (une fois par poste)

- **Les noms dans le fichier hosts.** PowerShell en administrateur :
  ```powershell
  Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Encoding ASCII -Value "`n127.0.0.1   coldtrace.test www.coldtrace.test api.coldtrace.test iam.coldtrace.test admin.coldtrace.test mlflow.coldtrace.test"
  ipconfig /flushdns
  ```
- **Les comptes IAM.** `IAM_ADMIN_PASSWORD`, `IAM_QUALITY_PASSWORD` et `IAM_OPERATOR_PASSWORD` dans `gateway-iot/.env` :
  - au moins 10 caractères, sans le nom d'utilisateur ;
  - `.\start` les génère s'ils manquent ;
  - ils ne servent qu'à créer les comptes absents.
- **Les ports 80 et 443** de la machine doivent être libres.
- **Java :** Docker fournit JDK 21 et Maven ; le JDK 11 du poste n'est pas utilisé.

## Démarrage à la main

```powershell
docker compose -f gateway-iot/docker-compose.yml config --quiet
docker compose -f gateway-iot/docker-compose.yml up -d --build
docker compose -f gateway-iot/docker-compose.yml logs --tail 60 wildfly
```

Le journal doit afficher `WFLYSRV0025: WildFly ... started`, puis `Deployed "api-1.0.war"` et `Deployed "iam-1.0.war"`.

Pour reconstruire seulement le WildFly (front-office, API ou IAM modifiés) :

```powershell
docker compose -f gateway-iot/docker-compose.yml up -d --build wildfly
```

## Développement avec fichiers montés en lecture seule

```powershell
docker compose -f gateway-iot/docker-compose.yml -f gateway-iot/docker-compose.frontend-dev.yml up -d --build wildfly
```

Seuls les fichiers publics du front-office sont montés, à la place de leurs copies dans l'image. Ne pas monter la racine
du dépôt. Une modification du contenu du shell exige toujours de changer la version du cache dans `sw.js`.

## Vérifications

```powershell
curl.exe --ssl-no-revoke https://api.coldtrace.test/api/health      # {"status":"ok"}
bash gateway-iot/scripts/smoke-test.sh                              # environ 3 minutes, tout doit être PASS
```

`--ssl-no-revoke` : curl sous Windows veut vérifier la révocation du certificat, ce qu'une autorité de développement ne
publie pas. Les navigateurs n'en ont pas besoin.

## Arrêt

```powershell
.\start -Stop
```

Ou `docker compose -f gateway-iot/docker-compose.yml stop`. Les données sont conservées.
