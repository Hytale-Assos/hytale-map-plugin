# AGENTS.md

Instructions destinées aux agents de codage (IA) travaillant sur ce dépôt.
Elles sont **impératives** : en cas de conflit entre une demande ponctuelle et ce
document, demander confirmation avant de déroger à une règle ci-dessous.

## Contexte du projet

Plugin serveur Hytale développé par l'association étudiante **HytaleAssos**.

- Langage : Java 25
- Build : Gradle (wrapper inclus), plugin `com.azuredoom.hytale-tools`
- Cible : serveur Hytale `>=0.6.5 <0.7.0`
- Package : `assos.hytale.servermanagement`
- Pas de commande en jeu : toute l'administration passe par l'API REST.
- HTTP : `com.sun.net.httpserver` du JDK (ne jamais ajouter de dépendance HTTP).
- JSON : Gson fourni par le serveur Hytale (ne jamais l'embarquer).

## Commandes de référence

```bash
./gradlew build            # compile + valide le manifest
./gradlew runServer        # serveur de dev
./gradlew updatePluginManifest
```

**Avant toute conclusion de tâche, `./gradlew build` doit passer.** Ne jamais
affirmer qu'un changement fonctionne sans l'avoir compilé. Si aucun test automatisé
n'existe, le signaler explicitement plutôt que de l'inventer.

## Règles de code

- Respecter le style et les conventions des fichiers voisins.
- Pas de commentaire sauf si demandé ; le code doit être lisible par lui-même.
- Utiliser les annotations `@Nonnull` / `@Nullable` aux frontières publiques.
- Les opérations touchant au provider de permissions (`PermissionsModule`,
  `AccessControlModule`) ne sont **pas thread-safe** : toute mutation doit passer
  par `WhitelistService` (executor mono-thread dédié).
- Gérer les cas d'erreur avec les types existants (`ApiException`,
  `PlayerResolutionException`) plutôt que des exceptions génériques.
- Toute nouvelle option de configuration doit être ajoutée à `ManagementConfig`
  avec une valeur par défaut sûre, puis documentée dans le `README.md`.

## Branches

- La branche d'intégration est **`develop`**. Ne jamais travailler directement dessus.
- Chaque fonctionnalité part d'une branche dédiée créée depuis `develop` :
  `git switch -c feat/<sujet> develop` (ex. `feat/whitelist`, `fix/api-reload`).
- Nommage : `feat/<sujet>`, `fix/<sujet>`, `docs/<sujet>`, `chore/<sujet>`,
  en minuscules et tirets.
- Une branche = une seule fonctionnalité ; ne pas mélanger plusieurs sujets.
- Ne jamais pousser en force ni réécrire une branche déjà partagée.

## Commits (style Linux kernel)

- Un commit = **un seul sujet logique**, autonome.
- Messages en **anglais**, impératif, format `type(scope): description courte`.
  Types acceptés : `feat`, `fix`, `refactor`, `docs`, `chore`, `build`, `test`, `perf`.
  Le `scope` identifie la zone touchée (ex. `whitelist`, `api`, `config`, `docs`).
- Sujet ≤ 72 caractères, pas de point final.
- Corps optionnel expliquant **quoi** et **pourquoi**, jamais comment.
- Ne jamais mélanger plusieurs sujets dans un commit.
- Ne jamais committer sans demande explicite de l'utilisateur.
- Inspecter `git status`, `git diff` et `git log` avant chaque commit.

## Interdictions absolues

- **Ne jamais committer de secret** : token API, clé, mot de passe, fichier
  `management_config.json` ou `.bak` généré à l'exécution.
- Ne jamais forcer un push (`--force`), ni réécrire l'historique publié.
- Ne jamais utiliser `--no-verify` (ou équivalent) pour contourner un hook git.
- Ne jamais modifier la configuration git (`user.name`, hooks, etc.).
- Ne jamais ajouter, supprimer ou modifier une dépendance sans validation.
- Ne jamais supprimer ni contourner une protection de sécurité existante
  (garde loopback de l'API, vérification du token, contrôle de permission avant
  déconnexion d'un joueur).
- Ne jamais exécuter de commande destructive (`rm -rf`, `git reset --hard`,
  `git clean -fdx`) sans autorisation explicite.
- Ne pas introduire d'emoji ni de contenu non sollicité dans le code ou les docs.

## Sécurité du plugin

- L'API REST n'écoute qu'en **loopback** par défaut ; un bind distant doit rester
  refusé sauf `AllowRemoteManagement=true`.
- L'authentification par token Bearer doit rester active par défaut.
- Avant de déconnecter un joueur retiré de la whitelist, toujours vérifier
  `AccessControlModule#isAllowedToJoin` (un joueur peut garder l'accès via un groupe).

## Documentation

- Tenir `README.md` à jour à chaque changement de comportement, d'endpoint ou de
  configuration.
- `docs/player-api.md` décrit l'API joueur disponible ; le compléter plutôt que
  de dupliquer l'information ailleurs.
