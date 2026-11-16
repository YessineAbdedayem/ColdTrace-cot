# Lancement local — Front Office WildFly/Undertow

Depuis la racine du dépôt, avec Docker Desktop démarré. Les commandes ci-dessous
conservent les volumes MongoDB et les clés IAM existants.

## Préconditions

- Conserver `gateway-iot/.env` et les certificats existants.
- Disposer d'un compte IAM déjà créé, ou faire renseigner les variables
  `IAM_ADMIN_PASSWORD`, `IAM_QUALITY_PASSWORD`, `IAM_OPERATOR_PASSWORD` par le responsable.
  Ces variables servent au bootstrap ; elles ne remplacent pas les mots de passe
  des comptes déjà enregistrés. Aucun secret n'est fourni dans ce document.
- Les secrets IAM ne sont pas renseignés dans le fichier local examiné lors de la migration.
  Aucun secret n'a été modifié pour cette tâche.
- Docker fournit JDK 21/Maven ; le JDK 11 du poste n'est pas adapté aux tests Java.

## Démarrage normal

```powershell
docker compose -f gateway-iot/docker-compose.yml config --quiet
docker compose -f gateway-iot/docker-compose.yml up -d --build
docker compose -f gateway-iot/docker-compose.yml logs --tail 60 front-office api iam
```

Le Front Office est maintenant servi par **WildFly 42/Undertow** sur
**http://localhost:3000/** (`FRONT_OFFICE_PORT`, défaut 3000).
Le port interne est 8080, et non plus 80.
L'API conserve `API_PORT` (18080 sur le poste audité, défaut 8080).
L'IAM conserve `IAM_PORT` (défaut 8081).
Aucun port d'administration WildFly n'est publié.

Pour ne remplacer que le frontend d'une plateforme déjà démarrée :

```powershell
docker compose -f gateway-iot/docker-compose.yml up -d --build --no-deps front-office
```

Le script habituel `.\start` utilise aussi cette nouvelle configuration et peut
démarrer le MLOps. Son comportement préexistant crée des secrets/certificats
manquants : il n'a pas été exécuté pendant cette migration.
Le MLOps reste géré par `mlops/docker-compose.yml`, sans modification.

## Proxy optionnel

Après démarrage de la plateforme, si les noms sont déjà présents dans le fichier hosts :

```powershell
docker compose -f middleware/proxy/docker-compose.yml up -d --force-recreate
```

Le proxy transmet `coldtrace.test` à `front-office:8080`.
Il ne sert pas les fichiers statiques. Les autres domaines restent inchangés.
HTTP sur `coldtrace.test` ne permet pas l'installation PWA : employer localhost,
ou un domaine avec HTTPS de confiance.

## Développement avec fichiers montés en lecture seule

```powershell
docker compose -f gateway-iot/docker-compose.yml -f gateway-iot/docker-compose.frontend-dev.yml up -d --build --no-deps front-office
```

Seuls les fichiers publics sont montés. Ne pas monter la racine du dépôt.
Une modification du contenu du shell exige toujours de changer la version du cache
dans `sw.js`. La migration de serveur seule ne change aucun fichier du shell.

## Arrêt et retour arrière

`docker compose -f gateway-iot/docker-compose.yml stop` conserve les données.

Les commandes précises de retour à Nginx et les résultats de tests figurent dans
[WILDFLY-FRONTEND-MIGRATION.md](WILDFLY-FRONTEND-MIGRATION.md).
